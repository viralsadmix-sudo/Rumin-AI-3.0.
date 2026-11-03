Rumin AI Studio Backend - initial integration

Included in this backend increment:
- FastAPI health endpoint and bearer-token protected API.
- Managed Redis/RQ queue for long-running video jobs.
- S3-compatible object storage for uploads and exported media.
- FFmpeg scene detection/segment extraction and MP4 assembly.
- Optional Whisper transcription and burned-in subtitles.
- ElevenLabs voice clone upload with explicit consent confirmation.
- ElevenLabs multilingual TTS returning a short-lived MP3 download URL.

Not yet implemented / not to be advertised as complete:
- Prompt-driven AI scene rearrangement (prompt is accepted, but scene ordering remains chronological).
- Motion graphics, animated templates, generated intro/outro, background music and sound-effect mixing.
- Subtitle editing UI and subtitle sidecar export.
- Android Studio screens and native file-picker/export integration.
- End-to-end tests against a real ElevenLabs account and managed storage.
- Production user accounts, per-user quotas, billing, malware scanning and abuse controls.

Managed deployment:
Deploy backend/ as two services from this repository:
1. API service: Dockerfile backend/Dockerfile, root directory backend, command uvicorn app.main:app --host 0.0.0.0 --port $PORT.
2. Worker service: same image, root directory backend, command rq worker --url "$REDIS_URL" rumin-video.
3. Attach managed Redis and S3-compatible storage (Cloudflare R2 or AWS S3).
4. Add every variable in backend/.env.example as secret/environment variables in the hosting dashboard. Never commit real keys.

Local development requires Python 3.11+, FFmpeg and ffprobe. Create a virtualenv, install requirements, copy .env.example to .env, then run uvicorn app.main:app --reload --port 8080 and rq worker --url "$REDIS_URL" rumin-video.

Video uploads default to 500 MB. Whisper downloads its model on first run and needs sufficient worker RAM/CPU. This is an integration foundation, not a production launch approval. Use HTTPS, private buckets, lifecycle cleanup, per-user authorization and abuse controls before public launch.
