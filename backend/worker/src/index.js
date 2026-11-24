const JSON_HEADERS = { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" };

function json(data, status = 200, headers = {}) {
  return new Response(JSON.stringify(data), { status, headers: { ...JSON_HEADERS, ...headers } });
}

function isRetryable(status) {
  return status === 408 || status === 413 || status === 429 || status === 500 || status === 502 || status === 503 || status === 504;
}

function extractGeminiText(data) {
  if (typeof data.output_text === "string" && data.output_text.trim()) return data.output_text.trim();
  const out = [];
  const walk = (items) => {
    if (!Array.isArray(items)) return;
    for (const item of items) {
      if (!item || typeof item !== "object") continue;
      if (item.type === "text" && typeof item.text === "string") out.push(item.text);
      walk(item.content);
    }
  };
  walk(data.output);
  walk(data.steps);
  return out.join("\n").trim();
}

async function callGemini(messages, env) {
  const system = messages.filter(m => m.role === "system").map(m => m.content).join("\n");
  const conversation = messages.filter(m => m.role !== "system")
    .map(m => (m.role === "assistant" ? "রুমিন: " : "ব্যবহারকারী: ") + m.content).join("\n");
  const input = [system, conversation].filter(Boolean).join("\n\n");
  const res = await fetch("https://generativelanguage.googleapis.com/v1beta/interactions", {
    method: "POST",
    headers: { "content-type": "application/json", "x-goog-api-key": env.GEMINI_API_KEY },
    body: JSON.stringify({ model: env.GEMINI_MODEL || "gemini-3.8-flash", input }),
    signal: AbortSignal.timeout(20000)
  });
  const raw = await res.text();
  if (!res.ok) return { ok: false, status: res.status, detail: raw.slice(0, 1200) };
  let data;
  try { data = JSON.parse(raw); } catch { return { ok: false, status: 502, detail: "Invalid Gemini JSON response" }; }
  const text = extractGeminiText(data);
  return text ? { ok: true, text, provider: "gemini" } : { ok: false, status: 502, detail: "Gemini returned an empty response" };
}

async function callOpenAICompatible(provider, baseUrl, model, key, messages) {
  const res = await fetch(baseUrl, {
    method: "POST",
    headers: { "content-type": "application/json", authorization: "Bearer " + key },
    body: JSON.stringify({ model, messages, temperature: 0.7 }),
    signal: AbortSignal.timeout(20000)
  });
  const raw = await res.text();
  if (!res.ok) return { ok: false, status: res.status, detail: raw.slice(0, 1200) };
  let data;
  try { data = JSON.parse(raw); } catch { return { ok: false, status: 502, detail: "Invalid provider JSON response" }; }
  const text = data.choices?.[0]?.message?.content;
  return typeof text === "string" && text.trim()
    ? { ok: true, text: text.trim(), provider }
    : { ok: false, status: 502, detail: "Provider returned an empty response" };
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") {
      return new Response(null, { status: 204, headers: {
        "access-control-allow-origin": env.CORS_ORIGIN || "*",
        "access-control-allow-methods": "POST, OPTIONS",
        "access-control-allow-headers": "authorization, content-type",
        "access-control-max-age": "86400"
      }});
    }
    const cors = { "access-control-allow-origin": env.CORS_ORIGIN || "*" };
    if (url.pathname === "/health" && request.method === "GET") return json({ ok: true, service: "rumin-ai-gateway" }, 200, cors);
    if (url.pathname !== "/v1/chat" || request.method !== "POST") return json({ error: "Not found" }, 404, cors);
    const auth = request.headers.get("authorization") || "";
    if (!env.APP_ACCESS_TOKEN || auth !== "Bearer " + env.APP_ACCESS_TOKEN) return json({ error: "Unauthorized" }, 401, cors);

    const length = Number(request.headers.get("content-length") || 0);
    if (length > 64000) return json({ error: "Request too large" }, 413, cors);
    let payload;
    try { payload = await request.json(); } catch { return json({ error: "Invalid JSON" }, 400, cors); }
    if (!Array.isArray(payload.messages) || payload.messages.length < 1 || payload.messages.length > 40) {
      return json({ error: "messages must contain 1–40 entries" }, 400, cors);
    }
    const messages = payload.messages.map(m => ({
      role: m?.role === "assistant" ? "assistant" : m?.role === "system" ? "system" : "user",
      content: String(m?.content || "").slice(0, 16000)
    }));
    if (!messages.some(m => m.role === "user")) return json({ error: "A user message is required" }, 400, cors);

    const providers = [];
    if (env.GEMINI_API_KEY) providers.push(() => callGemini(messages, env));
    if (env.GROQ_API_KEY) providers.push(() => callOpenAICompatible(
      "groq", "https://api.groq.com/openai/v1/chat/completions",
      env.GROQ_MODEL || "openai/gpt-oss-20b", env.GROQ_API_KEY, messages
    ));
    if (env.OPENROUTER_API_KEY) providers.push(() => callOpenAICompatible(
      "openrouter", "https://openrouter.ai/api/v1/chat/completions",
      env.OPENROUTER_MODEL || "openrouter/free", env.OPENROUTER_API_KEY, messages
    ));
    if (!providers.length) return json({ error: "No provider keys configured" }, 503, cors);

    const failures = [];
    for (const call of providers) {
      let result;
      try { result = await call(); }
      catch (e) { result = { ok: false, status: 502, detail: String(e?.message || e).slice(0, 500) }; }
      if (result.ok) return json({ ok: true, text: result.text, provider: result.provider, fallback_used: failures.length > 0 }, 200, cors);
      failures.push({ status: result.status, detail: result.detail });
      if (!isRetryable(result.status)) {
        return json({ error: "Provider rejected the request", provider_status: result.status, detail: result.detail, fallback_attempted: failures.length > 1 }, 502, cors);
      }
    }
    return json({ error: "All configured providers are unavailable or rate-limited", providers: failures }, 503, cors);
  }
};
