using System;
using System.IO;
using System.Linq;
using System.Collections.Generic;
using System.Net.Http;
using System.Threading.Tasks;
using System.Net;
using System.Collections.Concurrent;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Drawing;
using System.Drawing.Imaging;
using System.Windows.Automation;
using System.Speech.Synthesis;

var appData = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "RuminAI", "Companion");
Directory.CreateDirectory(appData);
var tokenPath = Path.Combine(appData, "companion.token");
var token = LoadOrCreateToken(tokenPath);
var rootsPath = Path.Combine(appData, "allowed-roots.json");
var roots = LoadOrCreateRoots(rootsPath);
var permissionsPath = Path.Combine(appData, "permissions.json");
var permissions = LoadOrCreatePermissions(permissionsPath);
var desktopSessionActive = false;
string? activeCode = null;
DateTimeOffset codeExpires = DateTimeOffset.MinValue;
string? phoneToken = null;
var pairingAttempts = new ConcurrentDictionary<string, (DateTimeOffset WindowStart, int Count)>();
var pairingLock = new object();

var builder = WebApplication.CreateBuilder(args);
// Listen on LAN interfaces for same-Wi-Fi phone pairing. Firewall may ask permission.
builder.WebHost.UseUrls("http://0.0.0.0:47831");
var app = builder.Build();
var auditPath = Path.Combine(appData, "audit.jsonl");
var auditGate = new object();

// Audit mutating requests without recording request bodies, credentials, or typed text.
app.Use(async (context, next) =>
{
    await next();
    var method = context.Request.Method;
    if (method is "POST" or "PUT" or "PATCH" or "DELETE")
        AuditTrail.Record(auditPath, auditGate, method, context.Request.Path.Value ?? "/", context.Response.StatusCode,
            context.Connection.RemoteIpAddress?.ToString() ?? "unknown");
});

 // PC speech stays loopback-only until the authenticated encrypted phone relay exists.
app.MapGet("/v1/speech/status", (HttpContext ctx) =>
{
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress)) return Results.Forbid();
    return Results.Ok(new { available = true, outputDevice = "windows-default", state = PcSpeechOutput.State, voices = PcSpeechOutput.InstalledVoices });
});
app.MapPost("/v1/speech/speak", async (HttpContext ctx) =>
{
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress)) return Results.Json(new { error = "secure_phone_relay_not_ready" }, statusCode: 403);
    var body = await ctx.Request.ReadFromJsonAsync<PcSpeechRequest>();
    if (string.IsNullOrWhiteSpace(body?.Text) || body.Text.Length > 3000)
        return Results.BadRequest(new { error = "speech_text_required_or_too_long" });
    try
    {
        var voice = PcSpeechOutput.Speak(body.Text);
        return Results.Ok(new { queued = true, outputDevice = "windows-default", voice });
    }
    catch (Exception)
    {
        return Results.Problem("Windows speech synthesis failed. Check that a Windows speech voice is installed.", statusCode: 503);
    }
});
app.MapPost("/v1/speech/stop", (HttpContext ctx) =>
{
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress)) return Results.Forbid();
    PcSpeechOutput.Stop();
    return Results.Ok(new { stopped = true });
});

app.Use(async (context, next) =>
{
    var remote = context.Connection.RemoteIpAddress;
    if (remote is null || !IsPrivateOrLoopback(remote))
    {
        context.Response.StatusCode = 403;
        return;
    }

    if (context.Request.Method == "OPTIONS")
    {
        AddCors(context);
        context.Response.StatusCode = 204;
        return;
    }

    AddCors(context);
    var path = context.Request.Path;
    if (path == "/health" || path == "/v1/pair/join" || path == "/v1/pair/phone-status")
    {
        await next();
        return;
    }

    // Pair-code generation is allowed only from the PC itself.
    if ((path == "/v1/pair/create" || path == "/v1/permissions/grant" ||
         path == "/v1/desktop/session/start" || path == "/v1/desktop/session/stop") &&
        IsLoopbackAddress(remote))
    {
        await next();
        return;
    }

    // Local dashboard requests are accepted only from loopback. Remote devices still need a paired bearer token.
    if (IsLoopbackAddress(remote))
    {
        await next();
        return;
    }

    var supplied = context.Request.Headers.Authorization.ToString();
    var valid = supplied.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase) &&
        (FixedEquals(supplied[7..], token) ||
         (phoneToken is not null && FixedEquals(supplied[7..], phoneToken)));
    if (!valid)
    {
        context.Response.StatusCode = 401;
        await context.Response.WriteAsJsonAsync(new { error = "unauthorized" });
        return;
    }
    await next();
});

app.MapGet("/v1/audit/recent", (HttpContext ctx, int? limit) =>
{
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress)) return Results.Json(new { error = "local_pc_only" }, statusCode: 403);
    var entries = AuditTrail.ReadRecent(auditPath, auditGate, Math.Clamp(limit ?? 100, 1, 250));
    return Results.Ok(new { count = entries.Count, entries, storage = "local-appdata-jsonl", capturesRequestBodies = false });
});

app.MapGet("/health", () => Results.Ok(new {
    service = "Rumin Windows Companion", status = "ready", version = "0.6.0",
    transport = "local-dashboard-and-private-lan-pairing", capabilities = new[] { "system-info", "allowlisted-folder-listing", "one-time-pairing", "permission-management", "screen-capture", "desktop-input" }
}));

app.MapPost("/v1/pair/create", () =>
{
    lock (pairingLock)
    {
        activeCode = RandomNumberGenerator.GetInt32(100000, 1000000).ToString();
        codeExpires = DateTimeOffset.UtcNow.AddMinutes(5);
        phoneToken = null;
        return Results.Ok(new { code = activeCode, expiresIn = 300, addresses = GetLanIPv4Addresses() });
    }
});

app.MapPost("/v1/pair/join", async (HttpContext ctx) =>
{
    // Short pairing codes are convenient but low entropy; throttle guesses per LAN client.
    var remoteKey = ctx.Connection.RemoteIpAddress?.ToString() ?? "unknown";
    var now = DateTimeOffset.UtcNow;
    var attempts = pairingAttempts.AddOrUpdate(remoteKey,
        _ => (now, 1),
        (_, previous) => now - previous.WindowStart > TimeSpan.FromMinutes(5)
            ? (now, 1)
            : (previous.WindowStart, previous.Count + 1));
    if (attempts.Count > 8) return Results.StatusCode(429);

    var body = await ctx.Request.ReadFromJsonAsync<PairRequest>();
    if (body?.Code is null) return Results.StatusCode(410);
    string issuedToken;
    lock (pairingLock)
    {
        if (activeCode is null || now > codeExpires || !FixedEquals(body.Code, activeCode))
            return Results.StatusCode(410);
        activeCode = null; // atomic one-use claim
        issuedToken = Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();
        phoneToken = issuedToken;
    }
    pairingAttempts.TryRemove(remoteKey, out _);
    return Results.Ok(new { paired = true, accessToken = issuedToken, expiresIn = 3600 });
});

app.MapGet("/v1/pair/phone-status", () => Results.Ok(new {
    paired = phoneToken is not null, codeActive = activeCode is not null && DateTimeOffset.UtcNow <= codeExpires
}));


app.MapGet("/v1/agent/capabilities", () => Results.Ok(new {
    service = "Rumin Windows Companion",
    version = "0.6.0",
    platform = "Windows",
    permissions = permissions,
    desktopSessionActive,
    available = new[] { "system-info", "allowlisted-folder-listing", "app-discovery", "app-launch", "screen-capture", "mouse-click", "mouse-scroll", "keyboard-type", "keyboard-shortcuts", "batched-agent-actions", "local-agent-memory", "approved-file-create-move-recycle", "printer-enumeration", "confirmed-text-print", "windows-ui-automation-tree", "confirmed-ui-automation-actions", "local-audit-log", "gemini-planner-preview", "windows-speaker-tts" },
    notYetAvailable = new[] { "screen-ocr", "microphone-stream", "camera", "secure-phone-relay", "gemini-planner" }
}));

app.MapGet("/v1/permissions", (HttpContext ctx) => !IsLoopbackAddress(ctx.Connection.RemoteIpAddress) ? Results.Json(new { error = "local_pc_only" }, statusCode: 403) : Results.Ok(new {
    permissions,
    note = "Permissions are granted by the PC user from the local dashboard. Windows does not provide one universal accessibility permission."
}));

app.MapPost("/v1/permissions/grant", async (HttpContext ctx) => {
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress!)) return Results.Forbid();
    var body = await ctx.Request.ReadFromJsonAsync<PermissionRequest>();
    if (body?.Capability is null || !permissions.ContainsKey(body.Capability))
        return Results.BadRequest(new { error = "unknown_capability" });
    permissions[body.Capability] = body.Enabled;
    SavePermissions(permissionsPath, permissions);
    if (!body.Enabled && body.Capability == "screen-control") desktopSessionActive = false;
    return Results.Ok(new { capability = body.Capability, enabled = body.Enabled, permissions });
});

app.MapGet("/v1/apps/list", (HttpContext ctx) => {
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress)) return Results.Json(new { error = "secure_phone_relay_not_ready" }, statusCode: 403);
    if (!permissions.GetValueOrDefault("app-discovery")) return Results.Json(new { error = "permission_required", capability = "app-discovery" }, statusCode: 403);
    return Results.Ok(DiscoverStartMenuApps().Select(x => new { id = x.Id, name = x.Name }).ToArray());
});

app.MapPost("/v1/apps/open", async (HttpContext ctx) => {
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress)) return Results.Json(new { error = "secure_phone_relay_not_ready" }, statusCode: 403);
    if (!permissions.GetValueOrDefault("app-launch")) return Results.Json(new { error = "permission_required", capability = "app-launch" }, statusCode: 403);
    var body = await ctx.Request.ReadFromJsonAsync<AppLaunchRequest>();
    if (string.IsNullOrWhiteSpace(body?.AppId)) return Results.BadRequest(new { error = "app_id_required" });
    var selected = DiscoverStartMenuApps().FirstOrDefault(x => FixedEquals(x.Id, body.AppId));
    if (selected is null) return Results.NotFound(new { error = "app_not_found_or_catalog_changed" });
    try {
        Process.Start(new ProcessStartInfo { FileName = selected.Path, UseShellExecute = true });
        return Results.Ok(new { opened = true, app = selected.Name });
    } catch (Exception ex) when (ex is InvalidOperationException or System.ComponentModel.Win32Exception) {
        return Results.Problem("Windows could not launch the selected Start Menu shortcut.", statusCode: 500);
    }
});


app.MapGet("/v1/desktop/screenshot", (HttpContext ctx) =>
{
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress))
        return Results.Json(new { error = "local_pc_only" }, statusCode: 403);
    if (!desktopSessionActive || !permissions.GetValueOrDefault("screen-control") ||
        !permissions.GetValueOrDefault("screen-capture"))
        return Results.Json(new { error = "screen_capture_not_authorized" }, statusCode: 403);
    try
    {
        var bounds = System.Windows.Forms.Screen.PrimaryScreen?.Bounds;
        if (bounds is null || bounds.Value.Width <= 0 || bounds.Value.Height <= 0)
            return Results.Problem("No interactive Windows display is available.", statusCode: 503);
        using var bitmap = new Bitmap(bounds.Value.Width, bounds.Value.Height);
        using (var graphics = Graphics.FromImage(bitmap))
            graphics.CopyFromScreen(bounds.Value.Left, bounds.Value.Top, 0, 0, bitmap.Size, CopyPixelOperation.SourceCopy);
        using var stream = new MemoryStream();
        bitmap.Save(stream, ImageFormat.Jpeg);
        ctx.Response.Headers.CacheControl = "no-store";
        return Results.File(stream.ToArray(), "image/jpeg");
    }
    catch (Exception)
    {
        return Results.Problem("Screen capture failed. Check that the Windows desktop is unlocked and interactive.", statusCode: 500);
    }
});

app.MapPost("/v1/desktop/session/start", (HttpContext ctx) => {
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress!)) return Results.Forbid();
    if (!permissions.GetValueOrDefault("screen-control")) return Results.Json(new { error = "permission_required", capability = "screen-control" }, statusCode: 403);
    desktopSessionActive = true;
    return Results.Ok(new { active = true, message = "Desktop control session enabled locally. Stop it from this PC at any time." });
});
app.MapPost("/v1/desktop/session/stop", (HttpContext ctx) => {
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress!)) return Results.Forbid();
    desktopSessionActive = false;
    return Results.Ok(new { active = false });
});

app.MapPost("/v1/desktop/input", async (HttpContext ctx) => {
    if (!desktopSessionActive || !permissions.GetValueOrDefault("screen-control"))
        return Results.Json(new { error = "desktop_session_not_authorized" }, statusCode: 403);
    // Until the encrypted phone relay is shipped, input injection is restricted to the local PC.
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress!))
        return Results.Json(new { error = "remote_control_requires_secure_relay" }, statusCode: 403);
    var body = await ctx.Request.ReadFromJsonAsync<DesktopInputRequest>();
    if (body is null || string.IsNullOrWhiteSpace(body.Action)) return Results.BadRequest(new { error = "action_required" });
    try {
        switch (body.Action.ToLowerInvariant()) {
            case "click":
                var width = NativeInput.GetSystemMetrics(0); var height = NativeInput.GetSystemMetrics(1);
                if (body.X < 0 || body.Y < 0 || body.X >= width || body.Y >= height)
                    return Results.BadRequest(new { error = "coordinates_out_of_bounds", width, height });
                NativeInput.Click(body.X, body.Y);
                break;
            case "scroll":
                NativeInput.Scroll(Math.Clamp(body.Delta, -1200, 1200));
                break;
            case "type":
                if (string.IsNullOrEmpty(body.Text) || body.Text.Length > 2000)
                    return Results.BadRequest(new { error = "text_length_invalid" });
                NativeInput.TypeText(body.Text);
                break;
            case "key":
                if (!NativeInput.PressAllowedKey(body.Key ?? ""))
                    return Results.BadRequest(new { error = "key_not_allowed" });
                break;
            default: return Results.BadRequest(new { error = "unsupported_input_action" });
        }
        return Results.Ok(new { executed = true, action = body.Action });
    } catch (Exception) {
        return Results.Problem("Windows rejected the requested input action.", statusCode: 500);
    }
});


app.MapPost("/v1/agent/execute", async (HttpContext ctx) => {
    // Batch execution reduces HTTP/model round trips for short, deterministic PC tasks.
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress))
        return Results.Json(new { error = "secure_phone_relay_not_ready" }, statusCode: 403);
    var body = await ctx.Request.ReadFromJsonAsync<AgentBatchRequest>();
    if (body?.Actions is null || body.Actions.Count is < 1 or > 8)
        return Results.BadRequest(new { error = "actions_must_contain_1_to_8_items" });

    var results = new List<object>();
    for (var index = 0; index < body.Actions.Count; index++)
    {
        var action = body.Actions[index];
        try
        {
            switch (action.Tool?.ToLowerInvariant())
            {
                case "open_app":
                    if (!permissions.GetValueOrDefault("app-launch"))
                        return Results.Json(new { error = "permission_required", capability = "app-launch", completed = results }, statusCode: 403);
                    var appItem = DiscoverStartMenuApps().FirstOrDefault(x => FixedEquals(x.Id, action.AppId ?? ""));
                    if (appItem is null)
                        return Results.Json(new { error = "app_not_found", failedIndex = index, completed = results }, statusCode: 404);
                    Process.Start(new ProcessStartInfo { FileName = appItem.Path, UseShellExecute = true });
                    results.Add(new { tool = "open_app", success = true, app = appItem.Name });
                    break;
                case "click":
                    if (!desktopSessionActive || !permissions.GetValueOrDefault("screen-control"))
                        return Results.Json(new { error = "desktop_session_not_authorized", failedIndex = index, completed = results }, statusCode: 403);
                    var w = NativeInput.GetSystemMetrics(0); var h = NativeInput.GetSystemMetrics(1);
                    if (action.X < 0 || action.Y < 0 || action.X >= w || action.Y >= h)
                        return Results.Json(new { error = "coordinates_out_of_bounds", failedIndex = index, completed = results }, statusCode: 400);
                    NativeInput.Click(action.X, action.Y);
                    results.Add(new { tool = "click", success = true });
                    break;
                case "scroll":
                    if (!desktopSessionActive || !permissions.GetValueOrDefault("screen-control"))
                        return Results.Json(new { error = "desktop_session_not_authorized", failedIndex = index, completed = results }, statusCode: 403);
                    NativeInput.Scroll(Math.Clamp(action.Delta, -1200, 1200));
                    results.Add(new { tool = "scroll", success = true });
                    break;
                case "type_text":
                    if (!desktopSessionActive || !permissions.GetValueOrDefault("screen-control"))
                        return Results.Json(new { error = "desktop_session_not_authorized", failedIndex = index, completed = results }, statusCode: 403);
                    if (string.IsNullOrEmpty(action.Text) || action.Text.Length > 2000)
                        return Results.BadRequest(new { error = "text_length_invalid", failedIndex = index, completed = results });
                    NativeInput.TypeText(action.Text);
                    results.Add(new { tool = "type_text", success = true, characters = action.Text.Length });
                    break;
                case "press_key":
                    if (!desktopSessionActive || !permissions.GetValueOrDefault("screen-control"))
                        return Results.Json(new { error = "desktop_session_not_authorized", failedIndex = index, completed = results }, statusCode: 403);
                    if (!NativeInput.PressAllowedKey(action.Key ?? ""))
                        return Results.BadRequest(new { error = "key_not_allowed", failedIndex = index, completed = results });
                    results.Add(new { tool = "press_key", success = true });
                    break;
                default:
                    return Results.BadRequest(new { error = "unsupported_tool", tool = action.Tool, failedIndex = index, completed = results });
            }
        }
        catch (Exception)
        {
            return Results.Problem("Windows rejected an agent action.", statusCode: 500);
        }
    }
    return Results.Ok(new { success = true, count = results.Count, results });
});


// Windows UI Automation: read the visible control tree and invoke only a selected,
 // user-confirmed control. Password values are never returned or written.

// Gemini planner: generates a constrained preview only. It never executes actions.
app.MapPost("/v1/agent/plan", async (HttpContext ctx) =>
{
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress)) return Results.Json(new { error = "local_pc_only" }, statusCode: 403);
    var body = await ctx.Request.ReadFromJsonAsync<GeminiPlanRequest>();
    if (body is null || string.IsNullOrWhiteSpace(body.ApiKey) || body.ApiKey.Length > 256 ||
        string.IsNullOrWhiteSpace(body.Task) || body.Task.Length > 2000)
        return Results.BadRequest(new { error = "api_key_and_task_required" });
    var model = string.IsNullOrWhiteSpace(body.Model) ? "gemini-2.5-flash" : body.Model.Trim();
    if (!System.Text.RegularExpressions.Regex.IsMatch(model, @"^gemini-[a-zA-Z0-9.-]{1,80}$"))
        return Results.BadRequest(new { error = "invalid_gemini_model" });
    const string instruction = "You are Rumin PC Agent Planner. Return ONLY JSON: {\"summary\":string,\"actions\":[...]}. Allowed actions: open_app {tool:'open_app',appId:string}; click {tool:'click',x:int,y:int}; scroll {tool:'scroll',delta:int}; type_text {tool:'type_text',text:string}; press_key {tool:'press_key',key:string}. Maximum 8 actions. Never propose shell, PowerShell, arbitrary URLs, passwords, payment, account deletion, security changes, or destructive file actions. If the request is unsafe or lacks information, return an empty actions array and explain in summary. This is a preview; do not claim anything was executed.";
    var requestJson = JsonSerializer.Serialize(new {
        contents = new[] { new { parts = new[] { new { text = instruction + "\nUser task: " + body.Task } } } },
        generationConfig = new { responseMimeType = "application/json", temperature = 0.1 }
    });
    try
    {
        using var http = new HttpClient { Timeout = TimeSpan.FromSeconds(45) };
        using var response = await http.PostAsync(
            "https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + Uri.EscapeDataString(body.ApiKey),
            new StringContent(requestJson, Encoding.UTF8, "application/json"));
        var raw = await response.Content.ReadAsStringAsync();
        if (!response.IsSuccessStatusCode) return Results.Json(new { error = "gemini_request_failed", status = (int)response.StatusCode }, statusCode: 502);
        using var doc = JsonDocument.Parse(raw);
        var text = doc.RootElement.GetProperty("candidates")[0].GetProperty("content").GetProperty("parts")[0].GetProperty("text").GetString();
        if (string.IsNullOrWhiteSpace(text)) return Results.Problem("Gemini returned an empty plan.", statusCode: 502);
        using var planDoc = JsonDocument.Parse(text);
        var root = planDoc.RootElement;
        var summary = root.TryGetProperty("summary", out var summaryNode) ? summaryNode.GetString() ?? "" : "";
        if (!root.TryGetProperty("actions", out var actionNodes) || actionNodes.ValueKind != JsonValueKind.Array || actionNodes.GetArrayLength() > 8)
            return Results.BadRequest(new { error = "invalid_plan_schema" });
        var actions = new List<object>();
        foreach (var item in actionNodes.EnumerateArray())
        {
            var tool = item.TryGetProperty("tool", out var toolNode) ? toolNode.GetString() : null;
            switch (tool)
            {
                case "open_app":
                    var appId = item.TryGetProperty("appId", out var appNode) ? appNode.GetString() : null;
                    if (string.IsNullOrWhiteSpace(appId) || !DiscoverStartMenuApps().Any(x => FixedEquals(x.Id, appId)))
                        return Results.BadRequest(new { error = "planner_proposed_unknown_app" });
                    actions.Add(new { tool, appId });
                    break;
                case "click":
                    if (!item.TryGetProperty("x", out var xNode) || !xNode.TryGetInt32(out var x) ||
                        !item.TryGetProperty("y", out var yNode) || !yNode.TryGetInt32(out var y) ||
                        x < 0 || y < 0 || x >= NativeInput.GetSystemMetrics(0) || y >= NativeInput.GetSystemMetrics(1))
                        return Results.BadRequest(new { error = "planner_proposed_invalid_coordinates" });
                    actions.Add(new { tool, x, y });
                    break;
                case "scroll":
                    if (!item.TryGetProperty("delta", out var deltaNode) || !deltaNode.TryGetInt32(out var delta) || delta == 0 || Math.Abs((long)delta) > 1200)
                        return Results.BadRequest(new { error = "planner_proposed_invalid_scroll" });
                    actions.Add(new { tool, delta });
                    break;
                case "type_text":
                    var typed = item.TryGetProperty("text", out var textNode) ? textNode.GetString() : null;
                    if (string.IsNullOrEmpty(typed) || typed.Length > 1000)
                        return Results.BadRequest(new { error = "planner_proposed_invalid_text" });
                    actions.Add(new { tool, text = typed });
                    break;
                case "press_key":
                    var key = item.TryGetProperty("key", out var keyNode) ? keyNode.GetString() : null;
                    if (!NativeInput.IsAllowedKey(key ?? ""))
                        return Results.BadRequest(new { error = "planner_proposed_disallowed_key" });
                    actions.Add(new { tool, key });
                    break;
                default: return Results.BadRequest(new { error = "planner_proposed_disallowed_tool" });
            }
        }
        return Results.Ok(new { previewOnly = true, summary, actions });
    }
    catch (JsonException) { return Results.BadRequest(new { error = "gemini_returned_invalid_json" }); }
    catch (TaskCanceledException) { return Results.Json(new { error = "gemini_timeout" }, statusCode: 504); }
    catch (Exception) { return Results.Problem("Gemini planner failed. Check network, model availability and API key.", statusCode: 502); }
});

app.MapGet("/v1/desktop/uia/tree", (HttpContext ctx, int? maxNodes, int? maxDepth) =>
{
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress)) return Results.Json(new { error = "local_pc_only" }, statusCode: 403);
    if (!desktopSessionActive || !permissions.GetValueOrDefault("screen-control") || !permissions.GetValueOrDefault("ui-automation"))
        return Results.Json(new { error = "ui_automation_not_authorized" }, statusCode: 403);
    try
    {
        var limit = Math.Clamp(maxNodes ?? 120, 1, 250);
        var depthLimit = Math.Clamp(maxDepth ?? 4, 1, 6);
        var items = new List<object>();
        var root = AutomationElement.RootElement;
        var walker = TreeWalker.ControlViewWalker;
        void Visit(AutomationElement node, int depth)
        {
            if (items.Count >= limit || depth > depthLimit) return;
            try
            {
                var c = node.Current;
                var runtime = node.GetRuntimeId();
                items.Add(new {
                    runtimeId = runtime,
                    name = c.IsPassword ? "" : (c.Name ?? ""),
                    automationId = c.AutomationId ?? "",
                    controlType = c.ControlType?.ProgrammaticName ?? "",
                    className = c.ClassName ?? "",
                    isEnabled = c.IsEnabled,
                    isOffscreen = c.IsOffscreen,
                    isPassword = c.IsPassword,
                    depth
                });
                for (var child = walker.GetFirstChild(node); child is not null && items.Count < limit; child = walker.GetNextSibling(child))
                    Visit(child, depth + 1);
            }
            catch (ElementNotAvailableException) { }
        }
        Visit(root, 0);
        return Results.Ok(new { count = items.Count, truncated = items.Count >= limit, items });
    }
    catch (Exception) { return Results.Problem("Windows UI Automation tree could not be read. Unlock the desktop and retry.", statusCode: 500); }
});

app.MapPost("/v1/desktop/uia/action", async (HttpContext ctx) =>
{
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress)) return Results.Json(new { error = "local_pc_only" }, statusCode: 403);
    if (!desktopSessionActive || !permissions.GetValueOrDefault("screen-control") || !permissions.GetValueOrDefault("ui-automation"))
        return Results.Json(new { error = "ui_automation_not_authorized" }, statusCode: 403);
    var body = await ctx.Request.ReadFromJsonAsync<UiAutomationActionRequest>();
    if (body?.Confirm != true) return Results.BadRequest(new { error = "explicit_action_confirmation_required" });
    if (body.RuntimeId is null || body.RuntimeId.Length is < 1 or > 32) return Results.BadRequest(new { error = "invalid_runtime_id" });
    if (body.Action is not ("invoke" or "set_value")) return Results.BadRequest(new { error = "unsupported_uia_action" });
    if (body.Action == "set_value" && (body.Value is null || body.Value.Length > 1000)) return Results.BadRequest(new { error = "value_length_invalid" });
    try
    {
        var target = FindAutomationElement(body.RuntimeId);
        if (target is null) return Results.NotFound(new { error = "uia_element_not_found_refresh_tree" });
        if (!target.Current.IsEnabled || target.Current.IsPassword) return Results.BadRequest(new { error = "element_disabled_or_password_protected" });
        if (body.Action == "invoke")
        {
            if (target.TryGetCurrentPattern(InvokePattern.Pattern, out var pattern) && pattern is InvokePattern invoke)
                invoke.Invoke();
            else return Results.BadRequest(new { error = "invoke_pattern_unavailable" });
        }
        else
        {
            if (target.TryGetCurrentPattern(ValuePattern.Pattern, out var pattern) && pattern is ValuePattern value)
                value.SetValue(body.Value!);
            else return Results.BadRequest(new { error = "value_pattern_unavailable" });
        }
        return Results.Ok(new { executed = true, action = body.Action, name = target.Current.Name });
    }
    catch (ElementNotAvailableException) { return Results.Conflict(new { error = "element_changed_refresh_tree" }); }
    catch (Exception) { return Results.Problem("Windows rejected the UI Automation action.", statusCode: 500); }
});

AutomationElement? FindAutomationElement(int[] runtimeId)
{
    var walker = TreeWalker.ControlViewWalker;
    var queue = new Queue<AutomationElement>();
    queue.Enqueue(AutomationElement.RootElement);
    var visited = 0;
    while (queue.Count > 0 && visited++ < 1000)
    {
        var current = queue.Dequeue();
        try
        {
            if (current.GetRuntimeId().SequenceEqual(runtimeId)) return current;
            for (var child = walker.GetFirstChild(current); child is not null; child = walker.GetNextSibling(child))
                queue.Enqueue(child);
        }
        catch (ElementNotAvailableException) { }
    }
    return null;
}


app.MapGet("/v1/system/info", (HttpContext ctx) => !IsLoopbackAddress(ctx.Connection.RemoteIpAddress) ? Results.Json(new { error = "local_pc_only" }, statusCode: 403) : Results.Ok(new {
    computerName = Environment.MachineName,
    operatingSystem = Environment.OSVersion.VersionString,
    architecture = System.Runtime.InteropServices.RuntimeInformation.OSArchitecture.ToString(),
    userProfile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
    companionVersion = "0.6.0"
}));


var memoryPath = Path.Combine(appData, "agent-memory.json");
var memoryLock = new object();
var memoryItems = LoadMemory(memoryPath);

app.MapGet("/v1/memory", (HttpContext ctx) =>
{
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress)) return Results.Json(new { error = "local_pc_only" }, statusCode: 403);
    if (!permissions.GetValueOrDefault("agent-memory")) return Results.Json(new { error = "permission_required", capability = "agent-memory" }, statusCode: 403);
    lock (memoryLock) return Results.Ok(new { items = memoryItems, count = memoryItems.Count });
});
app.MapPost("/v1/memory", async (HttpContext ctx) =>
{
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress)) return Results.Json(new { error = "local_pc_only" }, statusCode: 403);
    if (!permissions.GetValueOrDefault("agent-memory")) return Results.Json(new { error = "permission_required", capability = "agent-memory" }, statusCode: 403);
    var body = await ctx.Request.ReadFromJsonAsync<MemoryWriteRequest>();
    if (body is null || body.Consent != true) return Results.BadRequest(new { error = "explicit_consent_required" });
    if (string.IsNullOrWhiteSpace(body.Key) || body.Key.Length > 80 || !System.Text.RegularExpressions.Regex.IsMatch(body.Key, @"^[\p{L}\p{N}_. -]+$"))
        return Results.BadRequest(new { error = "invalid_memory_key" });
    if (body.Value is null || body.Value.Length > 4000) return Results.BadRequest(new { error = "memory_value_too_long" });
    lock (memoryLock)
    {
        memoryItems[body.Key.Trim()] = body.Value;
        SaveMemory(memoryPath, memoryItems);
    }
    return Results.Ok(new { saved = true, key = body.Key.Trim(), count = memoryItems.Count });
});
app.MapDelete("/v1/memory/{key}", (HttpContext ctx, string key) =>
{
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress)) return Results.Json(new { error = "local_pc_only" }, statusCode: 403);
    if (!permissions.GetValueOrDefault("agent-memory")) return Results.Json(new { error = "permission_required", capability = "agent-memory" }, statusCode: 403);
    lock (memoryLock)
    {
        var removed = memoryItems.Remove(key);
        if (removed) SaveMemory(memoryPath, memoryItems);
        return Results.Ok(new { removed, count = memoryItems.Count });
    }
});

app.MapPost("/v1/files/write", async (HttpContext ctx) =>
{
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress)) return Results.Json(new { error = "local_pc_only" }, statusCode: 403);
    if (!permissions.GetValueOrDefault("file-write")) return Results.Json(new { error = "permission_required", capability = "file-write" }, statusCode: 403);
    var body = await ctx.Request.ReadFromJsonAsync<FileWriteRequest>();
    if (body is null || body.Confirm != true) return Results.BadRequest(new { error = "explicit_confirmation_required" });
    if (body.Content is null || Encoding.UTF8.GetByteCount(body.Content) > 1_000_000) return Results.BadRequest(new { error = "content_limit_1mb" });
    var path = ResolveApprovedPath(body.RootId, body.RelativePath, roots);
    if (path is null) return Results.BadRequest(new { error = "path_outside_approved_roots_or_reparse_point" });
    if (File.Exists(path) && body.Overwrite != true) return Results.Conflict(new { error = "overwrite_confirmation_required" });
    try {
        Directory.CreateDirectory(Path.GetDirectoryName(path)!);
        File.WriteAllText(path, body.Content, new UTF8Encoding(false));
        return Results.Ok(new { written = true, name = Path.GetFileName(path), bytes = Encoding.UTF8.GetByteCount(body.Content) });
    } catch (UnauthorizedAccessException) { return Results.Problem("Approved folder write access denied.", statusCode: 403); }
      catch (IOException) { return Results.Problem("File write failed.", statusCode: 500); }
});
app.MapPost("/v1/files/move", async (HttpContext ctx) =>
{
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress)) return Results.Json(new { error = "local_pc_only" }, statusCode: 403);
    if (!permissions.GetValueOrDefault("file-write")) return Results.Json(new { error = "permission_required", capability = "file-write" }, statusCode: 403);
    var body = await ctx.Request.ReadFromJsonAsync<FileMoveRequest>();
    if (body is null || body.Confirm != true) return Results.BadRequest(new { error = "explicit_confirmation_required" });
    var source = ResolveApprovedPath(body.RootId, body.SourceRelativePath, roots);
    var destination = ResolveApprovedPath(body.RootId, body.DestinationRelativePath, roots);
    if (source is null || destination is null || !File.Exists(source)) return Results.BadRequest(new { error = "invalid_file_path" });
    if (File.Exists(destination)) return Results.Conflict(new { error = "destination_exists" });
    try { Directory.CreateDirectory(Path.GetDirectoryName(destination)!); File.Move(source, destination); return Results.Ok(new { moved = true, name = Path.GetFileName(destination) }); }
    catch (UnauthorizedAccessException) { return Results.Problem("Approved folder move access denied.", statusCode: 403); }
    catch (IOException) { return Results.Problem("File move failed.", statusCode: 500); }
});
app.MapPost("/v1/files/delete", async (HttpContext ctx) =>
{
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress)) return Results.Json(new { error = "local_pc_only" }, statusCode: 403);
    if (!permissions.GetValueOrDefault("file-write")) return Results.Json(new { error = "permission_required", capability = "file-write" }, statusCode: 403);
    var body = await ctx.Request.ReadFromJsonAsync<FileDeleteRequest>();
    if (body is null || body.Confirm != true) return Results.BadRequest(new { error = "explicit_confirmation_required" });
    var path = ResolveApprovedPath(body.RootId, body.RelativePath, roots);
    if (path is null || !File.Exists(path)) return Results.NotFound(new { error = "file_not_found" });
    try {
        Microsoft.VisualBasic.FileIO.FileSystem.DeleteFile(path, Microsoft.VisualBasic.FileIO.UIOption.OnlyErrorDialogs, Microsoft.VisualBasic.FileIO.RecycleOption.SendToRecycleBin);
        return Results.Ok(new { deleted = true, recycleBin = true, name = Path.GetFileName(path) });
    } catch (UnauthorizedAccessException) { return Results.Problem("Approved folder delete access denied.", statusCode: 403); }
      catch (IOException) { return Results.Problem("File delete failed.", statusCode: 500); }
});

app.MapGet("/v1/printers", (HttpContext ctx) =>
{
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress)) return Results.Json(new { error = "local_pc_only" }, statusCode: 403);
    if (!permissions.GetValueOrDefault("printing")) return Results.Json(new { error = "permission_required", capability = "printing" }, statusCode: 403);
    try {
        var printers = System.Drawing.Printing.PrinterSettings.InstalledPrinters.Cast<string>().Select(name => new {
            name, isDefault = string.Equals(name, new System.Drawing.Printing.PrinterSettings().PrinterName, StringComparison.OrdinalIgnoreCase)
        }).ToArray();
        return Results.Ok(new { printers });
    } catch (Exception) { return Results.Problem("Windows printer enumeration failed.", statusCode: 500); }
});
app.MapPost("/v1/print/text", async (HttpContext ctx) =>
{
    if (!IsLoopbackAddress(ctx.Connection.RemoteIpAddress)) return Results.Json(new { error = "local_pc_only" }, statusCode: 403);
    if (!permissions.GetValueOrDefault("printing")) return Results.Json(new { error = "permission_required", capability = "printing" }, statusCode: 403);
    var body = await ctx.Request.ReadFromJsonAsync<PrintTextRequest>();
    if (body is null || body.Confirm != true) return Results.BadRequest(new { error = "explicit_print_confirmation_required" });
    if (string.IsNullOrWhiteSpace(body.PrinterName) || body.Text is null || body.Text.Length is < 1 or > 20000)
        return Results.BadRequest(new { error = "printer_and_text_required_max_20000_chars" });
    try {
        var settings = new System.Drawing.Printing.PrinterSettings { PrinterName = body.PrinterName };
        if (!settings.IsValid) return Results.NotFound(new { error = "printer_not_found" });
        using var document = new System.Drawing.Printing.PrintDocument();
        document.PrinterSettings = settings;
        using var reader = new StringReader(body.Text);
        document.PrintPage += (_, e) => {
            using var font = new Font("Arial", 10);
            var layout = new RectangleF(45, 45, e.MarginBounds.Width, e.MarginBounds.Height);
            var remaining = reader.ReadToEnd();
            e.Graphics!.DrawString(remaining, font, Brushes.Black, layout);
            e.HasMorePages = false;
        };
        document.Print();
        return Results.Ok(new { submitted = true, printer = body.PrinterName });
    } catch (Exception) { return Results.Problem("Windows could not submit the print job.", statusCode: 500); }
});

app.MapGet("/v1/files/roots", (HttpContext ctx) => !IsLoopbackAddress(ctx.Connection.RemoteIpAddress) ? Results.Json(new { error = "secure_phone_relay_not_ready" }, statusCode: 403) : !permissions.GetValueOrDefault("file-read") ? Results.Json(new { error = "permission_required", capability = "file-read" }, statusCode: 403) : Results.Ok(roots.Select(x => new {
    id = x.Key, displayName = x.Value, access = "read-only-listing"
})));
app.MapGet("/v1/files/list/{rootId}", (HttpContext ctx, string rootId) => !IsLoopbackAddress(ctx.Connection.RemoteIpAddress) ? Results.Json(new { error = "secure_phone_relay_not_ready" }, statusCode: 403) : !permissions.GetValueOrDefault("file-read") ? Results.Json(new { error = "permission_required", capability = "file-read" }, statusCode: 403) : ListRoot(rootId));

IResult ListRoot(string rootId)
{
    var match = roots.FirstOrDefault(x => string.Equals(x.Key, rootId, StringComparison.OrdinalIgnoreCase));
    if (string.IsNullOrEmpty(match.Key)) return Results.NotFound(new { error = "unknown_root" });
    var path = Environment.ExpandEnvironmentVariables(match.Value);
    if (!Directory.Exists(path)) return Results.Ok(new { root = rootId, exists = false, items = Array.Empty<object>() });
    try
    {
        var items = Directory.EnumerateFileSystemEntries(path).Take(200).Select(entry =>
        {
            FileSystemInfo info = Directory.Exists(entry) ? new DirectoryInfo(entry) : new FileInfo(entry);
            return new { name = info.Name, kind = info is DirectoryInfo ? "directory" : "file", lastModifiedUtc = info.LastWriteTimeUtc };
        }).ToArray();
        return Results.Ok(new { root = rootId, exists = true, items });
    }
    catch (UnauthorizedAccessException) { return Results.Problem("Approved folder access denied.", statusCode: 403); }
    catch (IOException) { return Results.Problem("Approved folder could not be read.", statusCode: 500); }
}

app.MapGet("/pair", (string? code) => Results.Content("""
<!doctype html><html lang="bn"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Rumin Phone Pairing</title><body style="font:16px system-ui;background:#0b091b;color:#fff;padding:28px">
<h2>Rumin AI · Phone Pairing</h2><p id="s">Pairing যাচাই হচ্ছে…</p>
<script>
(async()=>{const code=new URLSearchParams(location.search).get('code');const s=document.getElementById('s');
if(!code){s.textContent='Pairing code নেই।';return}
try{const r=await fetch('/v1/pair/join',{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify({code})});
const d=await r.json();if(!r.ok)throw Error(d.error||'Code invalid or expired');
localStorage.setItem('ruminCompanionUrl',location.origin);localStorage.setItem('ruminCompanionToken',d.accessToken);
s.textContent='ফোন এই PC Companion-এর সঙ্গে যুক্ত হয়েছে। এখন Rumin Android app integration অপেক্ষমাণ।';
}catch(e){s.textContent='Pairing ব্যর্থ: '+e.message}
})();
</script></body></html>
""", "text/html; charset=utf-8"));
app.MapGet("/", () => Results.Text("Rumin Windows Companion 0.6.0 · same-Wi-Fi pairing · /health", "text/plain"));
Console.WriteLine("Rumin Windows Companion 0.6.0");
Console.WriteLine("LAN endpoint: http://0.0.0.0:47831 (private networks only)");
Console.WriteLine("Local bearer token: " + tokenPath);
Console.WriteLine("Approved roots: " + rootsPath);
Console.WriteLine("Pairing codes expire in 5 minutes and are one-use. File access is permission-gated; delete sends files to Recycle Bin.");
await app.RunAsync();

static void AddCors(HttpContext c)
{
    // GitHub Pages (HTTPS) calls this local HTTP endpoint from the same Windows PC.
    // Keep the response compatible with CORS + Chromium Private Network Access preflights.
    c.Response.Headers["Access-Control-Allow-Origin"] = "*";
    c.Response.Headers["Access-Control-Allow-Methods"] = "GET,POST,OPTIONS";
    c.Response.Headers["Access-Control-Allow-Headers"] = "content-type,authorization";
    c.Response.Headers["Access-Control-Allow-Private-Network"] = "true";
    c.Response.Headers.Append("Vary", "Origin");
    c.Response.Headers.Append("Vary", "Access-Control-Request-Method");
    c.Response.Headers.Append("Vary", "Access-Control-Request-Headers");
    c.Response.Headers.Append("Vary", "Access-Control-Request-Private-Network");
}
static bool IsLoopbackAddress(IPAddress? ip)
{
    if (ip is null) return false;
    if (ip.IsIPv4MappedToIPv6) ip = ip.MapToIPv4();
    return IPAddress.IsLoopback(ip);
}
static bool IsPrivateOrLoopback(IPAddress ip)
{
    if (IsLoopbackAddress(ip)) return true;
    if (ip.IsIPv4MappedToIPv6) ip = ip.MapToIPv4();
    if (ip.AddressFamily == AddressFamily.InterNetwork)
    {
        var b = ip.GetAddressBytes();
        return b[0] == 10 || (b[0] == 172 && b[1] >= 16 && b[1] <= 31) ||
               (b[0] == 192 && b[1] == 168) || (b[0] == 169 && b[1] == 254);
    }
    return ip.IsIPv6LinkLocal || ip.IsIPv6SiteLocal || IPAddress.IsLoopback(ip);
}
static bool FixedEquals(string a, string b)
{
    var aa = Encoding.UTF8.GetBytes(a); var bb = Encoding.UTF8.GetBytes(b);
    return aa.Length == bb.Length && CryptographicOperations.FixedTimeEquals(aa, bb);
}
static string[] GetLanIPv4Addresses() => NetworkInterface.GetAllNetworkInterfaces()
    .Where(n => n.OperationalStatus == OperationalStatus.Up)
    .SelectMany(n => n.GetIPProperties().UnicastAddresses)
    .Where(a => a.Address.AddressFamily == AddressFamily.InterNetwork && !IPAddress.IsLoopback(a.Address))
    .Select(a => a.Address.ToString()).Distinct().ToArray();
static string LoadOrCreateToken(string path)
{
    if (File.Exists(path)) { var existing = File.ReadAllText(path).Trim(); if (existing.Length >= 40) return existing; }
    var value = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32)).TrimEnd('=').Replace('+', '-').Replace('/', '_');
    File.WriteAllText(path, value); return value;
}
static Dictionary<string, string> LoadOrCreateRoots(string path)
{
    if (File.Exists(path)) try {
        var parsed = JsonSerializer.Deserialize<Dictionary<string, string>>(File.ReadAllText(path));
        if (parsed is { Count: > 0 }) return parsed;
    } catch (JsonException) { }
    var defaults = new Dictionary<string, string> {
        ["documents"] = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments),
        ["pictures"] = Environment.GetFolderPath(Environment.SpecialFolder.MyPictures)
    };
    File.WriteAllText(path, JsonSerializer.Serialize(defaults, new JsonSerializerOptions { WriteIndented = true }));
    return defaults;
}


static Dictionary<string, string> LoadMemory(string path)
{
    try { return JsonSerializer.Deserialize<Dictionary<string, string>>(File.ReadAllText(path)) ?? new(); }
    catch (Exception ex) when (ex is IOException or JsonException) { return new(); }
}
static void SaveMemory(string path, Dictionary<string, string> items)
{
    var temp = path + ".tmp";
    File.WriteAllText(temp, JsonSerializer.Serialize(items, new JsonSerializerOptions { WriteIndented = true }));
    File.Move(temp, path, true);
}
static string? ResolveApprovedPath(string? rootId, string? relativePath, Dictionary<string, string> roots)
{
    if (string.IsNullOrWhiteSpace(rootId) || string.IsNullOrWhiteSpace(relativePath) || Path.IsPathRooted(relativePath)) return null;
    var root = roots.FirstOrDefault(x => string.Equals(x.Key, rootId, StringComparison.OrdinalIgnoreCase));
    if (string.IsNullOrWhiteSpace(root.Key)) return null;
    var basePath = Path.GetFullPath(Environment.ExpandEnvironmentVariables(root.Value));
    var fullPath = Path.GetFullPath(Path.Combine(basePath, relativePath));
    var prefix = basePath.TrimEnd(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar) + Path.DirectorySeparatorChar;
    if (!fullPath.StartsWith(prefix, StringComparison.OrdinalIgnoreCase)) return null;
    var current = basePath;
    var relative = Path.GetRelativePath(basePath, fullPath);
    foreach (var part in relative.Split(Path.DirectorySeparatorChar, Path.AltDirectorySeparatorChar))
    {
        current = Path.Combine(current, part);
        if (File.Exists(current) || Directory.Exists(current))
            if ((File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0) return null;
    }
    return fullPath;
}

static Dictionary<string, bool> LoadOrCreatePermissions(string path)
{
    var defaults = new Dictionary<string, bool> {
        ["app-discovery"] = false, ["app-launch"] = false,
        ["file-read"] = false, ["screen-control"] = false,
        ["printing"] = false, ["file-write"] = false, ["screen-capture"] = false,
        ["agent-memory"] = false, ["ui-automation"] = false
    };
    if (File.Exists(path)) try {
        var saved = JsonSerializer.Deserialize<Dictionary<string, bool>>(File.ReadAllText(path));
        if (saved is not null) foreach (var key in defaults.Keys)
            if (saved.TryGetValue(key, out var value)) defaults[key] = value;
    } catch (JsonException) { }
    SavePermissions(path, defaults);
    return defaults;
}
static void SavePermissions(string path, Dictionary<string, bool> permissions) =>
    File.WriteAllText(path, JsonSerializer.Serialize(permissions, new JsonSerializerOptions { WriteIndented = true }));
static List<StartMenuApp> DiscoverStartMenuApps()
{
    var dirs = new[] {
        Environment.GetFolderPath(Environment.SpecialFolder.StartMenu),
        Environment.GetFolderPath(Environment.SpecialFolder.CommonStartMenu)
    }.Where(Directory.Exists).Distinct(StringComparer.OrdinalIgnoreCase);
    var result = new Dictionary<string, StartMenuApp>();
    foreach (var dir in dirs) {
        IEnumerable<string> links;
        try { links = Directory.EnumerateFiles(dir, "*.lnk", SearchOption.AllDirectories).Take(500).ToArray(); }
        catch (UnauthorizedAccessException) { continue; }
        foreach (var path in links) {
            var name = Path.GetFileNameWithoutExtension(path);
            if (string.IsNullOrWhiteSpace(name)) continue;
            var id = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(path.ToLowerInvariant()))).ToLowerInvariant()[..24];
            result[id] = new StartMenuApp(id, name, path);
        }
    }
    return result.Values.OrderBy(x => x.Name, StringComparer.OrdinalIgnoreCase).Take(250).ToList();
}
static class AuditTrail
{
    public static void Record(string path, object gate, string method, string route, int status, string remote)
    {
        try
        {
            var entry = JsonSerializer.Serialize(new {
                timestamp = DateTimeOffset.UtcNow,
                method,
                route = route.Length > 120 ? route[..120] : route,
                status,
                remote = remote.Length > 64 ? remote[..64] : remote
            });
            lock (gate)
            {
                Directory.CreateDirectory(Path.GetDirectoryName(path)!);
                File.AppendAllText(path, entry + Environment.NewLine, Encoding.UTF8);
                var lines = File.ReadAllLines(path);
                if (lines.Length > 500)
                    File.WriteAllLines(path, lines.TakeLast(500), Encoding.UTF8);
            }
        }
        catch (IOException) { }
        catch (UnauthorizedAccessException) { }
    }

    public static List<JsonElement> ReadRecent(string path, object gate, int limit)
    {
        lock (gate)
        {
            if (!File.Exists(path)) return new List<JsonElement>();
            var result = new List<JsonElement>();
            foreach (var line in File.ReadLines(path).TakeLast(limit))
                try { result.Add(JsonDocument.Parse(line).RootElement.Clone()); }
                catch (JsonException) { }
            return result;
        }
    }
}

record StartMenuApp(string Id, string Name, string Path);
record PermissionRequest(string? Capability, bool Enabled);
record AppLaunchRequest(string? AppId);
record DesktopInputRequest(string? Action, int X, int Y, int Delta, string? Text, string? Key);
record AgentBatchRequest(List<AgentAction>? Actions);
record AgentAction(string? Tool, int X = 0, int Y = 0, int Delta = 0, string? Text = null, string? Key = null, string? AppId = null);

static class NativeInput
{
    private const uint InputMouse = 0, InputKeyboard = 1;
    private const uint MouseMove = 0x0001, LeftDown = 0x0002, LeftUp = 0x0004, Wheel = 0x0800;
    private const uint KeyUp = 0x0002, Unicode = 0x0004;
    [StructLayout(LayoutKind.Sequential)] private struct INPUT { public uint type; public InputUnion U; }
    [StructLayout(LayoutKind.Explicit)] private struct InputUnion {
        [FieldOffset(0)] public MOUSEINPUT mi;
        [FieldOffset(0)] public KEYBDINPUT ki;
    }
    [StructLayout(LayoutKind.Sequential)] private struct MOUSEINPUT {
        public int dx, dy; public uint mouseData, dwFlags, time; public UIntPtr dwExtraInfo;
    }
    [StructLayout(LayoutKind.Sequential)] private struct KEYBDINPUT {
        public ushort wVk, wScan; public uint dwFlags, time; public UIntPtr dwExtraInfo;
    }
    [DllImport("user32.dll", SetLastError = true)] private static extern uint SendInput(uint count, INPUT[] inputs, int size);
    [DllImport("user32.dll")] public static extern int GetSystemMetrics(int index);
    [DllImport("user32.dll")] private static extern bool SetCursorPos(int x, int y);
    private static void Send(INPUT input) {
        if (SendInput(1, new[] { input }, Marshal.SizeOf<INPUT>()) != 1)
            throw new InvalidOperationException("SendInput failed");
    }
    public static void Click(int x, int y) {
        if (!SetCursorPos(x, y)) throw new InvalidOperationException("Cursor positioning failed");
        Send(new INPUT { type = InputMouse, U = new InputUnion { mi = new MOUSEINPUT { dwFlags = LeftDown } } });
        Send(new INPUT { type = InputMouse, U = new InputUnion { mi = new MOUSEINPUT { dwFlags = LeftUp } } });
    }
    public static void Scroll(int delta) =>
        Send(new INPUT { type = InputMouse, U = new InputUnion { mi = new MOUSEINPUT { mouseData = unchecked((uint)delta), dwFlags = Wheel } } });
    public static void TypeText(string value) {
        foreach (var ch in value) {
            Send(new INPUT { type = InputKeyboard, U = new InputUnion { ki = new KEYBDINPUT { wScan = ch, dwFlags = Unicode } } });
            Send(new INPUT { type = InputKeyboard, U = new InputUnion { ki = new KEYBDINPUT { wScan = ch, dwFlags = Unicode | KeyUp } } });
        }
    }
    public static bool IsAllowedKey(string key) =>
        key.ToUpperInvariant() is "ENTER" or "ESC" or "TAB" or "BACKSPACE" or "SPACE" or "UP" or "DOWN" or "LEFT" or "RIGHT" or "HOME" or "END" or "DELETE";
    public static bool PressAllowedKey(string key) {
        var vk = key.ToUpperInvariant() switch {
            "ENTER" => (ushort)0x0D, "ESC" => (ushort)0x1B, "TAB" => (ushort)0x09,
            "BACKSPACE" => (ushort)0x08, "SPACE" => (ushort)0x20,
            "UP" => (ushort)0x26, "DOWN" => (ushort)0x28,
            "LEFT" => (ushort)0x25, "RIGHT" => (ushort)0x27,
            "HOME" => (ushort)0x24, "END" => (ushort)0x23,
            "DELETE" => (ushort)0x2E, _ => (ushort)0
        };
        if (vk == 0) return false;
        Send(new INPUT { type = InputKeyboard, U = new InputUnion { ki = new KEYBDINPUT { wVk = vk } } });
        Send(new INPUT { type = InputKeyboard, U = new InputUnion { ki = new KEYBDINPUT { wVk = vk, dwFlags = KeyUp } } });
        return true;
    }
}


record MemoryWriteRequest(string? Key, string? Value, bool Consent = false);
record FileWriteRequest(string? RootId, string? RelativePath, string? Content, bool Confirm = false, bool Overwrite = false);
record FileMoveRequest(string? RootId, string? SourceRelativePath, string? DestinationRelativePath, bool Confirm = false);
record FileDeleteRequest(string? RootId, string? RelativePath, bool Confirm = false);
record PrintTextRequest(string? PrinterName, string? Text, bool Confirm = false);
record UiAutomationActionRequest(int[]? RuntimeId, string? Action, string? Value = null, bool Confirm = false);
record GeminiPlanRequest(string? ApiKey, string? Task, string? Model = null);

record PairRequest(string? Code);
record PcSpeechRequest(string? Text);

static class PcSpeechOutput
{
    private static readonly object Gate = new();
    private static readonly SpeechSynthesizer Synthesizer = CreateSynthesizer();

    private static SpeechSynthesizer CreateSynthesizer()
    {
        var synthesizer = new SpeechSynthesizer();
        synthesizer.Volume = 100;
        synthesizer.Rate = 0;
        return synthesizer;
    }

    public static string State
    {
        get { lock (Gate) return Synthesizer.State.ToString(); }
    }

    public static string[] InstalledVoices
    {
        get
        {
            lock (Gate)
                return Synthesizer.GetInstalledVoices().Where(v => v.Enabled).Select(v => v.VoiceInfo.Name).ToArray();
        }
    }

    public static string? Speak(string text)
    {
        lock (Gate)
        {
            Synthesizer.SpeakAsyncCancelAll();
            var voice = Synthesizer.GetInstalledVoices().FirstOrDefault(v => v.Enabled)?.VoiceInfo.Name;
            if (!string.IsNullOrWhiteSpace(voice)) Synthesizer.SelectVoice(voice);
            Synthesizer.SpeakAsync(text);
            return voice;
        }
    }

    public static void Stop()
    {
        lock (Gate) Synthesizer.SpeakAsyncCancelAll();
    }
}
