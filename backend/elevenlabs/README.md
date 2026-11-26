# Rumin AI — ElevenLabs TTS Worker

Deploy this folder as a Cloudflare Worker. The Android app calls `POST /v1/tts`.

## Secrets (never commit their values)
- `ELEVENLABS_API_KEY`: create in the ElevenLabs dashboard.
- `RUMIN_PROXY_TOKEN`: generate a long random token. Put the same token in Rumin Settings → Backend Access Token.

## Deploy
1. Install Wrangler and authenticate: `npx wrangler login`.
2. From this folder, create a Worker or use an existing Worker name.
3. Add secrets:
   - `npx wrangler secret put ELEVENLABS_API_KEY`
   - `npx wrangler secret put RUMIN_PROXY_TOKEN`
4. Deploy `worker.js` with Wrangler.
5. In Rumin Settings, set the Worker HTTPS URL, the matching access token, an available premade/authorized voice ID, and model `eleven_flash_v2_5`.

The Worker validates a bearer token, keeps the ElevenLabs key server-side, limits text to 5,000 characters, and returns MP3 audio. It does not store speech text. Use only voices your ElevenLabs plan is permitted to access. This proxy is for Rumin's normal Voice Chat; Gemini Live Voice remains separate.
