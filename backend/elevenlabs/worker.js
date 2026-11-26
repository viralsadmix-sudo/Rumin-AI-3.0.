export default {
  async fetch(request, env) {
    const cors = {
      "Access-Control-Allow-Origin": "*",
      "Access-Control-Allow-Methods": "POST, OPTIONS",
      "Access-Control-Allow-Headers": "Content-Type, Authorization"
    };
    if (request.method === "OPTIONS") return new Response(null, { headers: cors });
    const url = new URL(request.url);
    if (request.method !== "POST" || url.pathname !== "/v1/tts") {
      return Response.json({ error: "Not found" }, { status: 404, headers: cors });
    }
    if (!env.ELEVENLABS_API_KEY || !env.RUMIN_PROXY_TOKEN) {
      return Response.json({ error: "Backend secrets are not configured" }, { status: 503, headers: cors });
    }
    if (request.headers.get("Authorization") !== `Bearer ${env.RUMIN_PROXY_TOKEN}`) {
      return Response.json({ error: "Unauthorized" }, { status: 401, headers: cors });
    }
    let body;
    try { body = await request.json(); }
    catch { return Response.json({ error: "Invalid JSON" }, { status: 400, headers: cors }); }
    const text = typeof body.text === "string" ? body.text.trim() : "";
    const voiceId = typeof body.voice_id === "string" ? body.voice_id.trim() : "";
    const modelId = typeof body.model_id === "string" ? body.model_id : "eleven_flash_v2_5";
    if (!text || text.length > 5000 || !/^[A-Za-z0-9_-]{8,64}$/.test(voiceId)) {
      return Response.json({ error: "Invalid text or voice_id" }, { status: 400, headers: cors });
    }
    const upstream = await fetch(`https://api.elevenlabs.io/v1/text-to-speech/${encodeURIComponent(voiceId)}?output_format=mp3_44100_128`, {
      method: "POST",
      headers: { "xi-api-key": env.ELEVENLABS_API_KEY, "Content-Type": "application/json", "Accept": "audio/mpeg" },
      body: JSON.stringify({
        text,
        model_id: modelId,
        language_code: "bn",
        voice_settings: { stability: 0.5, similarity_boost: 0.75 }
      })
    });
    if (!upstream.ok) {
      const detail = (await upstream.text()).slice(0, 1000);
      return Response.json({ error: "ElevenLabs request failed", detail }, { status: upstream.status, headers: cors });
    }
    return new Response(upstream.body, {
      status: 200,
      headers: { ...cors, "Content-Type": "audio/mpeg", "Cache-Control": "no-store" }
    });
  }
};
