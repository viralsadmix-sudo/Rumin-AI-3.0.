# Rumin AI Free-first Gateway

A Cloudflare Worker gateway for Rumin AI 3.0. Provider order is Gemini → Groq → OpenRouter free router. It falls through on quota/rate-limit and transient server errors (408/413/429/5xx), not on invalid credentials or malformed requests.

## Deploy

1. Create a Cloudflare Worker and deploy this folder (or connect this repository with the Worker root directory set to `backend/worker`).
2. Add secrets in Worker Settings → Variables and Secrets:
   - `APP_ACCESS_TOKEN` — a long random token used by the Android app to authenticate.
   - `GEMINI_API_KEY` — optional.
   - `GROQ_API_KEY` — optional; create at https://console.groq.com/keys.
   - `OPENROUTER_API_KEY` — optional; create at https://openrouter.ai/keys.
3. Optional variables:
   - `GEMINI_MODEL` (default `gemini-3.8-flash`)
   - `GROQ_MODEL` (default `openai/gpt-oss-20b`)
   - `OPENROUTER_MODEL` (default `openrouter/free`)
   - `CORS_ORIGIN` (restrict to the app's WebView origin if applicable; native requests do not require CORS)
4. Deploy and test `GET /health`, then POST `/v1/chat` with `Authorization: Bearer <APP_ACCESS_TOKEN>` and JSON `{"messages":[{"role":"user","content":"আসসালামু আলাইকুম"}]}`.

## Security and limits

- Provider keys stay in Worker secrets; never put provider keys in the APK or WebView localStorage.
- The app access token is still a client credential. For a public production app, add per-user authentication and Cloudflare rate limiting before distributing it widely.
- Free tiers are quota-limited and can change. This gateway cannot guarantee unlimited free inference.
- This gateway is for text/chat requests. Gemini Live Voice remains a separate direct Live API path and is not silently rerouted through this chat endpoint.
- Request/response bodies are not logged by this Worker.
