# Rumin Windows Companion — MVP 0.1.0

This is the first Windows-side foundation for Rumin AI 3.0 PC Agent.

## What it does
- Runs a small local HTTP API bound only to `127.0.0.1:47831`.
- Generates a random local bearer token on first launch and stores it under the current Windows user's LocalAppData folder.
- Exposes a health endpoint.
- Exposes basic system information behind bearer authentication.
- Lists entries (maximum 200) only in the configured Documents and Pictures roots.
- Stores the allowed-root map in `%LOCALAPPDATA%\RuminAI\Companion\allowed-roots.json`.

## What it deliberately does not do
- No LAN or internet listener.
- No remote phone pairing or relay yet.
- No arbitrary PowerShell, CMD, or process execution.
- No file create, edit, move, overwrite, or delete.
- No screen capture, mouse, keyboard, printer, or background monitoring.

## Build on Windows 10
Install the .NET 8 SDK, then run:

```powershell
dotnet build .\RuminCompanion.csproj -c Release
dotnet run --project .\RuminCompanion.csproj
```

Open `http://127.0.0.1:47831/health` on the same PC to confirm the service is running.

Authenticated endpoints require:
`Authorization: Bearer <token>`

The token is stored locally at:
`%LOCALAPPDATA%\RuminAI\Companion\companion.token`

Do not share this token or commit it to Git. The health endpoint intentionally returns no secret.

## Security boundary
This is a local development MVP, not the final paired-device transport. Do not expose port 47831 to the LAN or internet. Browser dashboard and Android pairing are not connected to this API yet. A reviewed authenticated relay and device approval flow must be implemented before remote control is enabled.
