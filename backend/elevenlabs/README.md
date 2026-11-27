# Rumin AI — ElevenLabs TTS Worker

This is a dedicated Cloudflare Worker for Rumin's normal Voice Chat TTS. It is intentionally separate from the main Rumin Worker so deploying it cannot replace the main app backend. The Android app calls `POST /v1/tts`.

## Secrets (never commit their values)
- `ELEVENLABS_API_KEY`: create in the ElevenLabs dashboard.
- `RUMIN_PROXY_TOKEN`: generate a long random token. Put the same token in Rumin Settings → Backend Access Token.

## Cloudflare Git deployment
The folder contains `wrangler.toml` and deploys as the separate Worker `rumin-elevenlabs-tts`.
In the Cloudflare Worker Git build settings, select branch `feature/openphoneuse-agent-port`, set Root directory to `/backend/elevenlabs`, leave Build command empty, and use Deploy command `npx wrangler deploy`. Do not point this configuration at the existing `rumin-ai-3-0` Worker.

After the first successful deployment, add both secrets in the new Worker's Settings → Variables and Secrets:
- `ELEVENLABS_API_KEY`
- `RUMIN_PROXY_TOKEN`

Alternatively, from this folder with Wrangler authenticated:
```sh
npx wrangler secret put ELEVENLABS_API_KEY
npx wrangler secret put RUMIN_PROXY_TOKEN
npx wrangler deploy
```

In Rumin Settings, set the new Worker HTTPS URL, the matching access token, an available premade/authorized voice ID, and model `eleven_flash_v2_5`.

The Worker validates a bearer token, keeps the ElevenLabs key server-side, limits text to 5,000 characters, and returns MP3 audio. It does not store speech text. Use only voices your ElevenLabs plan is permitted to access. This proxy is for Rumin's normal Voice Chat; Gemini Live Voice remains separate.
