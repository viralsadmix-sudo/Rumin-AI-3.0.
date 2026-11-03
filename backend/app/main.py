import uuid
from fastapi import FastAPI, Depends, File, Form, HTTPException, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from redis import Redis
from rq import Queue
import httpx
from .config import settings
from .security import require_bearer
from .storage import put_bytes, signed_url

app = FastAPI(title="Rumin AI Studio API", version="0.1.0")
s = settings()
if s.allowed_origins:
    app.add_middleware(CORSMiddleware, allow_origins=[x.strip() for x in s.allowed_origins.split(",") if x.strip()], allow_credentials=False, allow_methods=["GET","POST"], allow_headers=["Authorization","Content-Type"])

def queue():
    return Queue("rumin-video", connection=Redis.from_url(settings().redis_url))

@app.get("/health")
def health():
    return {"ok": True, "service": "rumin-studio-api"}

@app.post("/v1/video/jobs", dependencies=[Depends(require_bearer)])
async def create_video_job(file: UploadFile = File(...), prompt: str = Form(""), subtitle_language: str = Form("auto"), burn_subtitles: bool = Form(True)):
    allowed = {"video/mp4","video/quicktime","video/x-matroska","video/webm"}
    if file.content_type not in allowed:
        raise HTTPException(415, "Upload MP4, MOV, MKV or WebM video")
    limit = settings().max_upload_mb * 1024 * 1024
    data = await file.read(limit + 1)
    if len(data) > limit:
        raise HTTPException(413, f"Maximum upload size is {settings().max_upload_mb} MB")
    job_id = str(uuid.uuid4())
    source_key = f"jobs/{job_id}/source"
    put_bytes(source_key, data, file.content_type or "video/mp4")
    job = queue().enqueue("app.worker.process_video", job_id, source_key, prompt[:4000], subtitle_language[:16], burn_subtitles, job_id=job_id, job_timeout=14400, result_ttl=86400, failure_ttl=86400)
    return {"job_id": job_id, "queue_id": job.id, "status": "queued"}

@app.get("/v1/video/jobs/{job_id}", dependencies=[Depends(require_bearer)])
def video_job_status(job_id: str):
    from rq.job import Job
    try:
        job = Job.fetch(job_id, connection=Redis.from_url(settings().redis_url))
    except Exception:
        raise HTTPException(404, "Job not found")
    result = job.result if job.is_finished else None
    payload = {"job_id": job_id, "status": job.get_status(), "error": str(job.exc_info)[-1500:] if job.is_failed else None}
    if isinstance(result, dict) and result.get("output_key"):
        payload["download_url"] = signed_url(result["output_key"])
        payload["details"] = result.get("details", {})
    return payload

@app.post("/v1/voice/clone", dependencies=[Depends(require_bearer)])
async def clone_voice(name: str = Form(...), consent_confirmed: bool = Form(...), sample: UploadFile = File(...)):
    if consent_confirmed is not True:
        raise HTTPException(400, "Explicit voice-owner consent is required")
    if sample.content_type not in {"audio/mpeg","audio/mp3","audio/wav","audio/x-wav","audio/mp4","audio/m4a"}:
        raise HTTPException(415, "Upload an MP3, WAV or M4A voice sample")
    data = await sample.read(25 * 1024 * 1024 + 1)
    if len(data) > 25 * 1024 * 1024:
        raise HTTPException(413, "Voice sample exceeds 25 MB")
    if len(data) < 20000:
        raise HTTPException(400, "Voice sample is too small")
    s = settings()
    async with httpx.AsyncClient(timeout=120) as client:
        response = await client.post("https://api.elevenlabs.io/v1/voices/add", headers={"xi-api-key": s.elevenlabs_api_key}, data={"name": name[:80], "description": "User-authorized voice clone for Rumin AI"}, files={"files": (sample.filename or "voice-sample.wav", data, sample.content_type)})
    if response.status_code >= 400:
        raise HTTPException(response.status_code, "ElevenLabs voice cloning request failed: " + response.text[:500])
    result = response.json()
    return {"voice_id": result.get("voice_id"), "name": name, "provider": "elevenlabs", "requires_verification": result.get("requires_verification", False)}

@app.post("/v1/voice/tts", dependencies=[Depends(require_bearer)])
async def text_to_speech(text: str = Form(...), voice_id: str = Form(...), language: str = Form("bn"), speed: float = Form(1.0), style: float = Form(0.0)):
    if not text.strip() or len(text) > 5000:
        raise HTTPException(400, "Text must contain 1–5000 characters")
    if not 0.7 <= speed <= 1.2 or not 0.0 <= style <= 1.0:
        raise HTTPException(400, "Speed must be 0.7–1.2 and style 0–1")
    s = settings()
    body = {"text": text, "model_id": s.elevenlabs_model_id, "voice_settings": {"stability": 0.5, "similarity_boost": 0.75, "style": style, "use_speaker_boost": True, "speed": speed}}
    async with httpx.AsyncClient(timeout=120) as client:
        response = await client.post(f"https://api.elevenlabs.io/v1/text-to-speech/{voice_id}", headers={"xi-api-key": s.elevenlabs_api_key, "Accept": "audio/mpeg", "Content-Type": "application/json"}, json=body)
    if response.status_code >= 400:
        raise HTTPException(response.status_code, "ElevenLabs TTS request failed: " + response.text[:500])
    key = f"tts/{uuid.uuid4()}.mp3"
    put_bytes(key, response.content, "audio/mpeg")
    return {"audio_url": signed_url(key), "format": "mp3", "expires_in": 3600}
