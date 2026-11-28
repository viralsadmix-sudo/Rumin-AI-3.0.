using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;
using System.Diagnostics;
using System.Runtime.InteropServices;

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

var builder = WebApplication.CreateBuilder(args);
// Listen on LAN interfaces for same-Wi-Fi phone pairing. Firewall may ask permission.
builder.WebHost.UseUrls("http://0.0.0.0:47831");
var app = builder.Build();

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
        IPAddress.IsLoopback(remote))
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

app.MapGet("/health", () => Results.Ok(new {
    service = "Rumin Windows Companion", status = "ready", version = "0.2.0",
    transport = "same-wifi-lan", capabilities = new[] { "system-info", "allowlisted-folder-listing", "one-time-pairing" }
}));

app.MapPost("/v1/pair/create", () =>
{
    activeCode = RandomNumberGenerator.GetInt32(100000, 1000000).ToString();
    codeExpires = DateTimeOffset.UtcNow.AddMinutes(5);
    phoneToken = null;
    return Results.Ok(new { code = activeCode, expiresIn = 300, addresses = GetLanIPv4Addresses() });
});

app.MapPost("/v1/pair/join", async (HttpContext ctx) =>
{
    var body = await ctx.Request.ReadFromJsonAsync<PairRequest>();
    if (body?.Code is null || activeCode is null || DateTimeOffset.UtcNow > codeExpires ||
        !FixedEquals(body.Code, activeCode))
        return Results.StatusCode(410);
    activeCode = null; // one use only
    phoneToken = Convert.ToHexString(RandomNumberGenerator.GetBytes(32)).ToLowerInvariant();
    return Results.Ok(new { paired = true, accessToken = phoneToken, expiresIn = 3600 });
});

app.MapGet("/v1/pair/phone-status", () => Results.Ok(new {
    paired = phoneToken is not null, codeActive = activeCode is not null && DateTimeOffset.UtcNow <= codeExpires
}));


app.MapGet("/v1/agent/capabilities", () => Results.Ok(new {
    service = "Rumin Windows Companion",
    version = "0.3.0",
    platform = "Windows",
    permissions = permissions,
    desktopSessionActive,
    available = new[] { "system-info", "allowlisted-folder-listing", "app-discovery", "app-launch", "mouse-click", "mouse-scroll", "keyboard-type", "keyboard-shortcuts" },
    notYetAvailable = new[] { "screen-capture", "screen-ocr", "printer-control", "file-write", "file-move", "file-delete", "microphone-stream", "camera" }
}));

app.MapGet("/v1/permissions", () => Results.Ok(new {
    permissions,
    note = "Permissions are granted by the PC user from the local dashboard. Windows does not provide one universal accessibility permission."
}));

app.MapPost("/v1/permissions/grant", async (HttpContext ctx) => {
    if (!IPAddress.IsLoopback(ctx.Connection.RemoteIpAddress!)) return Results.Forbid();
    var body = await ctx.Request.ReadFromJsonAsync<PermissionRequest>();
    if (body?.Capability is null || !permissions.ContainsKey(body.Capability))
        return Results.BadRequest(new { error = "unknown_capability" });
    permissions[body.Capability] = body.Enabled;
    SavePermissions(permissionsPath, permissions);
    if (!body.Enabled && body.Capability == "screen-control") desktopSessionActive = false;
    return Results.Ok(new { capability = body.Capability, enabled = body.Enabled, permissions });
});

app.MapGet("/v1/apps/list", () => {
    if (!permissions.GetValueOrDefault("app-discovery")) return Results.Json(new { error = "permission_required", capability = "app-discovery" }, statusCode: 403);
    return Results.Ok(DiscoverStartMenuApps().Select(x => new { id = x.Id, name = x.Name }).ToArray());
});

app.MapPost("/v1/apps/open", async (HttpContext ctx) => {
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

app.MapPost("/v1/desktop/session/start", (HttpContext ctx) => {
    if (!IPAddress.IsLoopback(ctx.Connection.RemoteIpAddress!)) return Results.Forbid();
    if (!permissions.GetValueOrDefault("screen-control")) return Results.Json(new { error = "permission_required", capability = "screen-control" }, statusCode: 403);
    desktopSessionActive = true;
    return Results.Ok(new { active = true, message = "Desktop control session enabled locally. Stop it from this PC at any time." });
});
app.MapPost("/v1/desktop/session/stop", (HttpContext ctx) => {
    if (!IPAddress.IsLoopback(ctx.Connection.RemoteIpAddress!)) return Results.Forbid();
    desktopSessionActive = false;
    return Results.Ok(new { active = false });
});

app.MapPost("/v1/desktop/input", async (HttpContext ctx) => {
    if (!desktopSessionActive || !permissions.GetValueOrDefault("screen-control"))
        return Results.Json(new { error = "desktop_session_not_authorized" }, statusCode: 403);
    // Until the encrypted phone relay is shipped, input injection is restricted to the local PC.
    if (!IPAddress.IsLoopback(ctx.Connection.RemoteIpAddress!))
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

app.MapGet("/v1/system/info", () => Results.Ok(new {
    computerName = Environment.MachineName,
    operatingSystem = Environment.OSVersion.VersionString,
    architecture = System.Runtime.InteropServices.RuntimeInformation.OSArchitecture.ToString(),
    userProfile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
    companionVersion = "0.2.0"
}));

app.MapGet("/v1/files/roots", () => Results.Ok(roots.Select(x => new {
    id = x.Key, displayName = x.Value, access = "read-only-listing"
})));
app.MapGet("/v1/files/list/{rootId}", (string rootId) => ListRoot(rootId));

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
app.MapGet("/", () => Results.Text("Rumin Windows Companion 0.2.0 · same-Wi-Fi pairing · /health", "text/plain"));
Console.WriteLine("Rumin Windows Companion 0.2.0");
Console.WriteLine("LAN endpoint: http://0.0.0.0:47831 (private networks only)");
Console.WriteLine("Local bearer token: " + tokenPath);
Console.WriteLine("Approved roots: " + rootsPath);
Console.WriteLine("Pairing codes expire in 5 minutes and are one-use. File access is read-only.");
await app.RunAsync();

static void AddCors(HttpContext c)
{
    c.Response.Headers["Access-Control-Allow-Origin"] = "*";
    c.Response.Headers["Access-Control-Allow-Methods"] = "GET,POST,OPTIONS";
    c.Response.Headers["Access-Control-Allow-Headers"] = "content-type,authorization";
    c.Response.Headers["Access-Control-Allow-Private-Network"] = "true";
}
static bool IsPrivateOrLoopback(IPAddress ip)
{
    if (IPAddress.IsLoopback(ip)) return true;
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

static Dictionary<string, bool> LoadOrCreatePermissions(string path)
{
    var defaults = new Dictionary<string, bool> {
        ["app-discovery"] = false, ["app-launch"] = false,
        ["file-read"] = true, ["screen-control"] = false,
        ["printing"] = false, ["file-write"] = false
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
record StartMenuApp(string Id, string Name, string Path);
record PermissionRequest(string? Capability, bool Enabled);
record AppLaunchRequest(string? AppId);
record DesktopInputRequest(string? Action, int X, int Y, int Delta, string? Text, string? Key);

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

record PairRequest(string? Code);
