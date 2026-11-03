Rumin AI Studio Backend - initial integration

Included in this backend increment:
- FastAPI health endpoint and bearer-token protected API.
- Managed Redis/RQ queue for long-running video jobs.
- S3-compatible object storage with streaming uploads/downloads for large media.
- FFmpeg scene detection/segment extraction and MP4 assembly.
- Optional Whisper transcription and burned-in subtitles.
- ElevenLabs voice clone upload with explicit consent confirmation.
- ElevenLabs multilingual TTS returning a short-lived MP3 download URL. Bengali/English language is inferred by the multilingual model from the text; speed and style are configurable.

Not yet implemented / not to be advertised as complete:
- Prompt-driven AI scene rearrangement (prompt is accepted, but scene ordering remains chronological).
- Motion graphics, animated templates, generated intro/outro, background music and sound-effect mixing.
- Subtitle editing UI and subtitle sidecar export.
- Android video preview/export-to-gallery integration.
- End-to-end tests against a real ElevenLabs account and managed storage.
- Production user accounts, per-user quotas, billing, malware scanning and abuse controls.

Managed deployment (recommended managed stack: Railway + Upstash Redis + Cloudflare R2):
1. Create an API service from this repository. Set service root directory to backend and use the included Dockerfile. Set start command to: uvicorn app.main:app --host 0.0.0.0 --port $PORT
2. Create a second service from the same repository with root directory backend and the same Dockerfile. Override start command to: rq worker --url "$REDIS_URL" rumin-video
3. Attach a managed Redis database (Upstash or Railway Redis). Set REDIS_URL to its complete redis:// or rediss:// connection URL.
4. Create a private Cloudflare R2 bucket and an S3 API token. Set S3_ENDPOINT_URL to https://<account-id>.r2.cloudflarestorage.com, S3_BUCKET, S3_ACCESS_KEY_ID, S3_SECRET_ACCESS_KEY and S3_REGION=auto.
5. Set API_BEARER_TOKEN to a long random secret and ELEVENLABS_API_KEY to your own ElevenLabs key in BOTH services. Set ALLOWED_ORIGINS=* for the Android WebView local-file origin. CORS credentials are disabled; Studio API calls still require the bearer token.
6. Add every variable from backend/.env.example to the managed hosting dashboard. Never commit real keys. Keep the R2 bucket private.

Local development requires Python 3.11+, FFmpeg and ffprobe. Create a virtualenv, install requirements.txt, copy .env.example to .env, then run uvicorn app.main:app --reload --port 8080 and rq worker --url "$REDIS_URL" rumin-video.

Video uploads default to 500 MB and are streamed to object storage. Whisper downloads its model on first run and needs sufficient worker RAM/CPU. The API bearer token is a temporary shared-secret integration guard, not production multi-user authentication. Before public release add per-user authentication/authorization, quotas, rate limiting, request audit logs, upload scanning, lifecycle cleanup and secrets rotation. Use HTTPS for every endpoint.
