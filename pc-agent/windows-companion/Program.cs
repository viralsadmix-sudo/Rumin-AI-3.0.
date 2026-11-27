using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

var appData = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "RuminAI", "Companion");
Directory.CreateDirectory(appData);
var tokenPath = Path.Combine(appData, "companion.token");
var token = LoadOrCreateToken(tokenPath);
var rootsPath = Path.Combine(appData, "allowed-roots.json");
var roots = LoadOrCreateRoots(rootsPath);
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
    if (path == "/v1/pair/create" && IPAddress.IsLoopback(remote))
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
record PairRequest(string? Code);
