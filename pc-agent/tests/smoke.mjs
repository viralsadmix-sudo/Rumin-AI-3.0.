import fs from "node:fs";
import assert from "node:assert/strict";

const html = fs.readFileSync(new URL("../index.html", import.meta.url), "utf8");
const scripts = [...html.matchAll(/<script\b[^>]*>([\s\S]*?)<\/script>/gi)].map(x => x[1]).filter(x => x.trim());
assert.ok(scripts.length > 0, "dashboard must contain an application script");
for (const [i, script] of scripts.entries()) {
  try { new Function(script); } catch (error) {
    throw new Error("Dashboard inline script " + (i + 1) + " failed syntax validation: " + error.message);
  }
}
const ids = [...html.matchAll(/\bid="([^"]+)"/g)].map(x => x[1]);
assert.equal(new Set(ids).size, ids.length, "HTML id attributes must be unique");
const idSet = new Set(ids);
for (const match of html.matchAll(/getElementById\(["']([^"']+)["']\)/g)) {
  assert.ok(idSet.has(match[1]), "JavaScript references missing static element #" + match[1]);
}
for (const match of html.matchAll(/data-page="([^"]+)"/g)) {
  assert.ok(idSet.has(match[1]), "Navigation points to missing page #" + match[1]);
}
assert.ok(html.includes('id="diagnostics"'), "system diagnostics page must exist");
assert.ok(html.includes('id="runDiagnostics"'), "diagnostics action must exist");
assert.ok(!html.includes("api.qrserver.com"), "pairing data must not be sent to a third-party QR service");

const worker = fs.readFileSync(new URL("../../backend/pc-agent/worker.js", import.meta.url), "utf8");
assert.ok(worker.includes("if (!/^\\d{6}$/.test(code))"), "pairing code must validate exactly six digits");
assert.ok(worker.includes('url.pathname === "/health"'), "relay health endpoint must exist");
assert.ok(worker.includes("relay_transport_not_enabled_yet"), "relay must remain explicitly disabled until secure transport is implemented");
console.log("PC dashboard smoke tests passed: JS syntax, DOM references, navigation, diagnostics, pairing privacy and relay safety.");
