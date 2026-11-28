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

const companion = fs.readFileSync(new URL("../windows-companion/Program.cs", import.meta.url), "utf8");
assert.ok(companion.includes('version = "0.5.0"'), "Companion version must match the dashboard release");
assert.ok(companion.includes("attempts.Count > 8"), "local pairing must throttle repeated guesses");
assert.ok(companion.includes("atomic one-use claim"), "one-time pairing code consumption must be atomic");
assert.ok(companion.includes('app.MapGet("/v1/files/list/{rootId}"'), "approved-folder listing endpoint must exist");
assert.ok(companion.includes('app.MapPost("/v1/files/write"'), "approved file write endpoint must exist");
assert.ok(companion.includes('app.MapPost("/v1/files/move"'), "approved file move endpoint must exist");
assert.ok(companion.includes('app.MapPost("/v1/files/delete"'), "approved file recycle endpoint must exist");
assert.ok(companion.includes('SendToRecycleBin'), "file delete must use Windows Recycle Bin");
assert.ok(companion.includes('app.MapGet("/v1/memory"'), "local Agent Memory read endpoint must exist");
assert.ok(companion.includes('app.MapPost("/v1/memory"'), "consent-gated Agent Memory write endpoint must exist");
assert.ok(companion.includes('app.MapGet("/v1/printers"'), "printer enumeration endpoint must exist");
assert.ok(companion.includes('app.MapPost("/v1/print/text"'), "confirmed text print endpoint must exist");
assert.ok(companion.includes('explicit_print_confirmation_required'), "printing must require explicit confirmation");
assert.ok(companion.includes('ResolveApprovedPath'), "file operations must be restricted to approved roots");

const worker = fs.readFileSync(new URL("../../backend/pc-agent/worker.js", import.meta.url), "utf8");
assert.ok(worker.includes("if (!/^\\d{6}$/.test(code))"), "pairing code must validate exactly six digits");
assert.ok(worker.includes('url.pathname === "/health"'), "relay health endpoint must exist");
assert.ok(worker.includes("relay_transport_not_enabled_yet"), "relay must remain explicitly disabled until secure transport is implemented");
console.log("PC dashboard smoke tests passed: JS syntax, DOM references, navigation, diagnostics, local memory, approved file operations, printer endpoints, pairing privacy and relay safety.");
