import os, subprocess, tempfile, base64, json
import httpx
from pathlib import Path
from scenedetect import open_video, SceneManager
from scenedetect.detectors import ContentDetector
from faster_whisper import WhisperModel
from .storage import download_file, upload_file

def run(cmd):
    result = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if result.returncode:
        raise RuntimeError(result.stderr[-2500:])
    return result


def plan_scene_order(prompt, scenes, source, root):
    api_key = os.getenv("GEMINI_API_KEY", "").strip()
    if not api_key or not prompt.strip():
        return None
    if len(scenes) > 40:
        raise RuntimeError("Prompt arrangement is limited to 40 detected scenes per video")
    parts = [{"text": "Arrange these video scenes for the user's editing request. Return ONLY JSON with a scene_order array containing every integer index exactly once. Do not omit scenes. User request: " + prompt[:3000]}]
    for index, (start, end) in enumerate(scenes):
        frame = root / f"prompt-frame-{index:03d}.jpg"
        midpoint = (start + end) / 2
        run(["ffmpeg", "-y", "-ss", str(midpoint), "-i", str(source), "-frames:v", "1", "-vf", "scale=320:-1", "-q:v", "6", str(frame)])
        parts.append({"text": f"Scene index {index}; start {start:.2f}s; end {end:.2f}s"})
        parts.append({"inline_data": {"mime_type": "image/jpeg", "data": base64.b64encode(frame.read_bytes()).decode("ascii")}})
    model = os.getenv("GEMINI_VIDEO_MODEL", "gemini-2.5-flash")
    response = httpx.post(
        f"https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent",
        params={"key": api_key},
        json={"contents": [{"role": "user", "parts": parts}],
              "generationConfig": {"temperature": 0.2, "responseMimeType": "application/json"}},
        timeout=120,
    )
    response.raise_for_status()
    text = response.json()["candidates"][0]["content"]["parts"][0]["text"]
    result = json.loads(text)
    order = result.get("scene_order")
    if not isinstance(order, list) or sorted(order) != list(range(len(scenes))):
        raise RuntimeError("Gemini returned an invalid scene_order")
    return order

def process_video(job_id: str, source_key: str, prompt: str, subtitle_language: str, burn_subtitles: bool):
    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)
        source = root / "input-video"
        download_file(source_key, source)
        scenes = []
        try:
            video = open_video(str(source)); manager = SceneManager()
            manager.add_detector(ContentDetector(threshold=27.0, min_scene_len=15))
            manager.detect_scenes(video)
            scenes = [(float(a.get_seconds()), float(b.get_seconds())) for a,b in manager.get_scene_list()]
        except Exception:
            scenes = []
        probe = run(["ffprobe","-v","error","-show_entries","format=duration","-of","default=noprint_wrappers=1:nokey=1",str(source)])
        duration = float(probe.stdout.strip() or 0)
        if not scenes: scenes = [(0.0, duration)]
        scene_order = list(range(len(scenes)))
        prompt_status = "prompt_not_provided" if not prompt.strip() else "chronological_fallback_no_gemini_key"
        if prompt.strip() and os.getenv("GEMINI_API_KEY", "").strip():
            try:
                planned = plan_scene_order(prompt, scenes, source, root)
                if planned is not None:
                    scene_order = planned
                    scenes = [scenes[index] for index in planned]
                    prompt_status = "gemini_scene_arrangement"
            except Exception as exc:
                prompt_status = "failed: " + str(exc)[:300]
        parts = []
        for index, (start, end) in enumerate(scenes):
            if end - start < 0.35: continue
            part = root / f"scene-{index:04d}.mp4"
            run(["ffmpeg","-y","-ss",str(start),"-i",str(source),"-t",str(end-start),"-c:v","libx264","-preset","veryfast","-crf","23","-c:a","aac","-movflags","+faststart",str(part)])
            parts.append(part)
        if not parts: raise RuntimeError("No usable video scenes were detected")
        listing = root / "concat.txt"
        listing.write_text("".join(f"file '{p.as_posix()}'\n" for p in parts))
        concat = root / "joined.mp4"
        run(["ffmpeg","-y","-f","concat","-safe","0","-i",str(listing),"-c","copy","-movflags","+faststart",str(concat)])
        final = concat; subtitle_status = "not_requested"
        if burn_subtitles:
            try:
                model = WhisperModel(os.getenv("WHISPER_MODEL","small"), device="cpu", compute_type="int8")
                segments, info = model.transcribe(str(concat), language=None if subtitle_language=="auto" else subtitle_language, vad_filter=True)
                srt = root / "captions.srt"
                def stamp(t):
                    ms=int(t*1000); h,ms=divmod(ms,3600000); m,ms=divmod(ms,60000); sec,ms=divmod(ms,1000)
                    return f"{h:02}:{m:02}:{sec:02},{ms:03}"
                with srt.open("w",encoding="utf-8") as out:
                    for i, seg in enumerate(segments,1): out.write(f"{i}\n{stamp(seg.start)} --> {stamp(seg.end)}\n{seg.text.strip()}\n\n")
                burned = root / "final.mp4"
                run(["ffmpeg","-y","-i",str(concat),"-vf",f"subtitles={srt.as_posix()}","-c:v","libx264","-preset","veryfast","-crf","23","-c:a","copy","-movflags","+faststart",str(burned)])
                final = burned; subtitle_status = "generated_and_burned"
            except Exception as exc: subtitle_status = "failed: " + str(exc)[:300]
        output_key = f"jobs/{job_id}/final.mp4"
        upload_file(output_key, final, "video/mp4")
        return {"output_key": output_key, "details": {"scenes_detected":len(scenes),"scenes_kept":len(parts),"subtitle_status":subtitle_status,"prompt_received":bool(prompt),"prompt_editing":prompt_status,"scene_order":scene_order,"effects_music_intro_outro":"not_implemented"}}
