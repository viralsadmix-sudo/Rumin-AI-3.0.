export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    if (request.method === "OPTIONS") return new Response(null, { status: 204, headers: cors() });
    if (url.pathname === "/health") return json({ service: "Rumin PC Agent Pairing Relay", status: "ready", pairing: true, commandRelay: false });
    if (url.pathname === "/") return new Response("Rumin PC Agent Pairing Relay", {
      headers: { "content-type": "text/plain; charset=utf-8", "cache-control": "no-store" }
    });
    if (!url.pathname.startsWith("/api/")) return json({ error: "not_found" }, 404);
    const id = env.PAIRING.idFromName("rumin-pairing-v1");
    return env.PAIRING.get(id).fetch(request);
  }
};

export class PairingRoom {
  constructor(ctx, env) { this.ctx = ctx; this.env = env; }

  async fetch(request) {
    const url = new URL(request.url);
    const path = url.pathname;

    if (request.method === "POST" && path === "/api/pair/create") {
      // Cryptographically random 6-digit short-lived code; retry collisions.
      let code, record;
      for (let i = 0; i < 5; i++) {
        const bytes = crypto.getRandomValues(new Uint32Array(1));
        code = String(100000 + (bytes[0] % 900000));
        if (!(await this.ctx.storage.get("pair:" + code))) break;
        code = null;
      }
      if (!code) return json({ error: "try_again" }, 503);
      const session = crypto.randomUUID();
      const pcSecret = randomToken();
      record = { session, pcSecret, created: Date.now(), phone: false, pc: false, used: false };
      await this.ctx.storage.put("pair:" + code, record, { expirationTtl: 300 });
      await this.ctx.storage.put("session:" + session, record, { expirationTtl: 3600 });
      return json({ code, session, pcSecret, expiresIn: 300 });
    }

    if (request.method === "POST" && path === "/api/pair/join") {
      const body = await request.json().catch(() => ({}));
      const code = String(body.code || "");
      if (!/^\d{6}$/.test(code)) return json({ error: "invalid_code" }, 400);
      const pair = await this.ctx.storage.get("pair:" + code);
      if (!pair || pair.used || Date.now() - pair.created > 300000)
        return json({ error: "code_expired_or_used" }, 410);
      pair.phone = true;
      pair.used = true;
      const phoneSecret = randomToken();
      pair.phoneSecret = phoneSecret;
      await this.ctx.storage.put("pair:" + code, pair, { expirationTtl: 300 });
      await this.ctx.storage.put("session:" + pair.session, pair, { expirationTtl: 3600 });
      return json({ paired: true, session: pair.session, phoneSecret });
    }

    if (request.method === "GET" && path === "/api/pair/status") {
      const session = url.searchParams.get("session") || "";
      const pair = await this.ctx.storage.get("session:" + session);
      if (!pair) return json({ paired: false }, 404);
      const auth = request.headers.get("authorization") || "";
      if (!safeEqual(auth, "Bearer " + pair.pcSecret)) return json({ error: "unauthorized" }, 401);
      return json({ paired: !!pair.phone, confirmed: !!pair.pc });
    }

    if (request.method === "POST" && path === "/api/pair/confirm") {
      const body = await request.json().catch(() => ({}));
      const session = String(body.session || "");
      const pair = await this.ctx.storage.get("session:" + session);
      if (!pair) return json({ error: "session_not_found" }, 404);
      const auth = request.headers.get("authorization") || "";
      if (!safeEqual(auth, "Bearer " + pair.pcSecret)) return json({ error: "unauthorized" }, 401);
      pair.pc = true;
      await this.ctx.storage.put("session:" + session, pair, { expirationTtl: 3600 });
      return json({ confirmed: true });
    }

    // Relay transport is intentionally disabled until device-specific
    // authorization, signed messages, replay protection and approval UX ship.
    if (path === "/api/relay/send" || path === "/api/relay/poll")
      return json({ error: "relay_transport_not_enabled_yet" }, 501);

    return json({ error: "not_found" }, 404);
  }
}

function randomToken() {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return Array.from(bytes, b => b.toString(16).padStart(2, "0")).join("");
}
function safeEqual(a, b) {
  const aa = new TextEncoder().encode(a), bb = new TextEncoder().encode(b);
  if (aa.length !== bb.length) return false;
  let diff = 0;
  for (let i = 0; i < aa.length; i++) diff |= aa[i] ^ bb[i];
  return diff === 0;
}
function cors() {
  return {
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET,POST,OPTIONS",
    "access-control-allow-headers": "content-type,authorization",
    "access-control-max-age": "86400",
    "vary": "Origin"
  };
}
function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: { ...cors(), "content-type": "application/json", "cache-control": "no-store" }
  });
}
