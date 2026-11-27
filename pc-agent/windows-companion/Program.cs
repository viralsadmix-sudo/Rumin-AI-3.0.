using System.Diagnostics;
using System.Security.Cryptography;
using System.Text;
using System.Text.Json;

var appData = Path.Combine(
    Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),
    "RuminAI", "Companion");
Directory.CreateDirectory(appData);

var tokenPath = Path.Combine(appData, "companion.token");
var token = LoadOrCreateToken(tokenPath);
var rootsPath = Path.Combine(appData, "allowed-roots.json");
var roots = LoadOrCreateRoots(rootsPath);

var builder = WebApplication.CreateBuilder(args);
builder.WebHost.UseUrls("http://127.0.0.1:47831");
var app = builder.Build();

// Loopback-only service. Do not change this to 0.0.0.0 without a reviewed
// authenticated transport and explicit network security design.
app.Use(async (context, next) =>
{
    if (context.Connection.RemoteIpAddress is not { } remoteIp || !System.Net.IPAddress.IsLoopback(remoteIp))
    {
        context.Response.StatusCode = StatusCodes.Status403Forbidden;
        return;
    }

    if (context.Request.Path == "/health")
    {
        await next();
        return;
    }

    var supplied = context.Request.Headers.Authorization.ToString();
    if (!supplied.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase) ||
        !CryptographicOperations.FixedTimeEquals(
            Encoding.UTF8.GetBytes(supplied[7..]),
            Encoding.UTF8.GetBytes(token)))
    {
        context.Response.StatusCode = StatusCodes.Status401Unauthorized;
        await context.Response.WriteAsJsonAsync(new { error = "unauthorized" });
        return;
    }

    await next();
});

app.MapGet("/health", () => Results.Ok(new
{
    service = "Rumin Windows Companion",
    status = "ready",
    version = "0.1.0",
    transport = "loopback-only",
    capabilities = new[] { "system-info", "allowlisted-folder-listing" }
}));

app.MapGet("/v1/system/info", (HttpContext context) => Results.Ok(new
{
    computerName = Environment.MachineName,
    operatingSystem = Environment.OSVersion.VersionString,
    architecture = System.Runtime.InteropServices.RuntimeInformation.OSArchitecture.ToString(),
    userProfile = Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
    companionVersion = "0.1.0"
}));

app.MapGet("/v1/files/roots", () => Results.Ok(roots.Select(x => new
{
    id = x.Key,
    displayName = x.Value,
    access = "read-only-listing"
})));

app.MapGet("/v1/files/list/{rootId}", (string rootId) => ListRoot(rootId));

IResult ListRoot(string rootId)
{
    var match = roots.FirstOrDefault(x =>
        string.Equals(x.Key, rootId, StringComparison.OrdinalIgnoreCase));
    if (string.IsNullOrEmpty(match.Key))
        return Results.NotFound(new { error = "unknown_root" });

    var path = Environment.ExpandEnvironmentVariables(match.Value);
    if (!Directory.Exists(path))
        return Results.Ok(new { root = rootId, exists = false, items = Array.Empty<object>() });

    try
    {
        var items = Directory.EnumerateFileSystemEntries(path)
            .Take(200)
            .Select(entry =>
            {
                var info = Directory.Exists(entry)
                    ? (FileSystemInfo)new DirectoryInfo(entry)
                    : new FileInfo(entry);
                return new
                {
                    name = info.Name,
                    kind = info is DirectoryInfo ? "directory" : "file",
                    lastModifiedUtc = info.LastWriteTimeUtc
                };
            }).ToArray();

        return Results.Ok(new { root = rootId, exists = true, items });
    }
    catch (UnauthorizedAccessException)
    {
        return Results.Problem("Access to this approved folder was denied.", statusCode: 403);
    }
    catch (IOException)
    {
        return Results.Problem("The approved folder could not be read.", statusCode: 500);
    }
}

app.MapGet("/", () => Results.Text(
    "Rumin Windows Companion is running on loopback. Use /health for status.",
    "text/plain"));

Console.WriteLine("Rumin Windows Companion 0.1.0");
Console.WriteLine("Local-only endpoint: http://127.0.0.1:47831");
Console.WriteLine($"Local access token stored at: {tokenPath}");
Console.WriteLine($"Approved folder list stored at: {rootsPath}");
Console.WriteLine("No remote pairing, shell execution, file writes, or screen capture is enabled.");
await app.RunAsync();

static string LoadOrCreateToken(string path)
{
    if (File.Exists(path))
    {
        var existing = File.ReadAllText(path).Trim();
        if (existing.Length >= 40) return existing;
    }

    var value = Convert.ToBase64String(RandomNumberGenerator.GetBytes(32))
        .TrimEnd('=').Replace('+', '-').Replace('/', '_');
    File.WriteAllText(path, value);
    return value;
}

static Dictionary<string, string> LoadOrCreateRoots(string path)
{
    if (File.Exists(path))
    {
        try
        {
            var parsed = JsonSerializer.Deserialize<Dictionary<string, string>>(
                File.ReadAllText(path));
            if (parsed is { Count: > 0 }) return parsed;
        }
        catch (JsonException) { }
    }

    var defaults = new Dictionary<string, string>
    {
        ["documents"] = Environment.GetFolderPath(Environment.SpecialFolder.MyDocuments),
        ["pictures"] = Environment.GetFolderPath(Environment.SpecialFolder.MyPictures)
    };
    File.WriteAllText(path, JsonSerializer.Serialize(defaults,
        new JsonSerializerOptions { WriteIndented = true }));
    return defaults;
}
