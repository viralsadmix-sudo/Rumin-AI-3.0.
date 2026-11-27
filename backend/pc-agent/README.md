# Rumin PC Agent Pairing Relay

This is the signalling/short-message relay service for pairing the PC dashboard and phone client. It is separate from GitHub Pages; static hosting alone cannot coordinate two devices.

## Deploy
Create a new Cloudflare Worker named `rumin-pc-agent-relay`, connect this repository/branch `feature/openphoneuse-agent-port`, set the Worker root directory to `backend/pc-agent`, and deploy with Wrangler. Do not reuse the ElevenLabs Worker. The Durable Object binding and migration are defined in `wrangler.toml`.

## API
- `POST /api/pair/create` returns a six-digit, five-minute pairing code and session ID.
- `POST /api/pair/join` with `{"code":"123456"}` joins a phone client.
- `GET /api/pair/status?session=...` checks whether the phone joined.
- `POST /api/pair/confirm` confirms the PC side.
- `POST /api/relay/send` and `GET /api/relay/poll` carry small JSON messages after both sides are paired.

## Important security and implementation limits
This first relay milestone is a pairing/signalling transport, not an end-to-end encrypted command channel. It does not authorize PC actions. Before production commands are enabled, add authenticated device identities, cryptographic key agreement, message signatures, replay protection, rate limiting, expiry/cleanup, and explicit per-action approval. Never send files, passwords, or unrestricted shell commands through this relay. The Android app still needs a native pairing screen/QR scanner and authenticated client implementation before the phone Rumin app can use it.
