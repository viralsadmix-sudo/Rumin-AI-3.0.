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
assert.ok(html.includes('id="safety"'), "Safety Center page must exist");
assert.ok(html.includes('data-group-toggle="pc-hub"'), "PC Control Hub sidebar group must exist");
for (const page of ["connect", "files", "print", "screen", "memory", "safety"]) {
  assert.ok(html.includes('data-page="' + page + '"'), "PC Control Hub must link to " + page);
}
assert.ok(!html.includes("Local pairing prototype</h3>"), "module cards must not remain on the Overview page");
assert.ok(html.includes('id="openSafetyPermissions"'), "Safety Center must link to PC Permissions");

assert.ok(html.includes('id="runDiagnostics"'), "diagnostics action must exist");
assert.ok(!html.includes("api.qrserver.com"), "pairing data must not be sent to a third-party QR service");

const companion = fs.readFileSync(new URL("../windows-companion/Program.cs", import.meta.url), "utf8");
assert.ok(companion.includes('version = "0.6.1"'), "Companion version must match the dashboard release");
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
assert.ok(companion.includes("using System.Speech.Synthesis;"), "Windows speech synthesis must be referenced");
assert.ok(companion.includes('app.MapGet("/v1/speech/status"'), "PC speaker status endpoint must exist");
assert.ok(companion.includes('app.MapPost("/v1/speech/speak"'), "PC speaker speak endpoint must exist");
assert.ok(companion.includes('app.MapPost("/v1/speech/stop"'), "PC speaker stop endpoint must exist");
assert.ok(companion.includes('outputDevice = "windows-default"'), "speech must target the Windows default audio output");
assert.ok(companion.includes("SpeakAsyncCancelAll"), "PC speech must support cancelling queued/current output");
const project = fs.readFileSync(new URL("../windows-companion/RuminCompanion.csproj", import.meta.url), "utf8");
assert.ok(project.includes('PackageReference Include="System.Speech"'), "Windows speech synthesis package must be installed");
assert.ok(companion.includes('if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress)) return Results.Json(new { error = "secure_phone_relay_not_ready" }, statusCode: 403);'), "remote speech must remain blocked until secure relay exists");
assert.ok(companion.includes('app.MapGet("/pair"'), "phone pairing landing page must exist");
assert.ok(companion.includes('localStorage.setItem(\'ruminCompanionToken\',d.accessToken)'), "phone pairing page must retain the returned browser pairing token");
assert.ok(companion.includes('version = "0.6.1"'), "Companion health version must match 0.6.1");
assert.ok(companion.includes('app.MapGet("/v1/audit/recent"'), "local audit history endpoint must exist");
assert.ok(companion.includes("static class AuditTrail"), "audit trail writer must exist");
assert.ok(companion.includes("TakeLast(500)"), "audit log must be bounded to the latest 500 events");
assert.ok(companion.includes("capturesRequestBodies = false"), "audit endpoint must disclose that request bodies are not captured");
assert.ok(html.includes('id="loadSafetyAudit"'), "Safety Center must expose audit history loading");
assert.ok(html.includes('id="safetyAuditItems"'), "Safety Center must render recent audit events");


const worker = fs.readFileSync(new URL("../../backend/pc-agent/worker.js", import.meta.url), "utf8");
assert.ok(worker.includes("if (!/^\\d{6}$/.test(code))"), "pairing code must validate exactly six digits");
assert.ok(worker.includes('url.pathname === "/health"'), "relay health endpoint must exist");
assert.ok(worker.includes("relay_transport_not_enabled_yet"), "relay must remain explicitly disabled until secure transport is implemented");
console.log("PC dashboard smoke tests passed: JS syntax, DOM references, navigation, diagnostics, local memory, approved file operations, printer endpoints, pairing privacy and relay safety.");
