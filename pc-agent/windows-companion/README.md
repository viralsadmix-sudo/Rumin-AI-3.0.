# Rumin Windows Companion — 0.3.0 (Windows 10)

The Companion is the Windows-side execution layer for the existing Rumin AI 3.0 PC Agent. It is a .NET 8 ASP.NET Core process; it is not yet a signed installer or a Windows service.

## Current functions
- Local health and capability status.
- Separate, opt-in permissions stored in `%LOCALAPPDATA%\RuminAI\Companion\permissions.json`.
- Start Menu shortcut discovery and launch by an ID returned by the local app catalog (no arbitrary command-line endpoint).
- Read-only listing of Documents and Pictures roots, with the allowed-root map stored in `allowed-roots.json`.
- Local, user-started desktop input session: mouse click by screen coordinates, scroll, Unicode text typing, and a small allowlist of keys.
- Desktop input and permission changes are restricted to loopback/local dashboard requests in this build. A phone bearer token cannot remotely control the desktop over the current plain-HTTP LAN transport.

## Permission model
The dashboard's **PC Permissions** page lets the Windows user separately enable:
- App discovery
- App launch
- Read approved folders
- Mouse and keyboard control
- Printing (reserved; not implemented)
- File write/edit/move (reserved; not implemented)

All permissions except read-only folder listing default to off. The screen-control permission is not enough by itself: the user must also start a local desktop-control session. The session can be stopped from the dashboard at any time.

Windows does not have one universal Android-style Accessibility toggle for every desktop capability. Some actions use Windows APIs; future UI Automation, screen capture, OCR, printing, microphone, and camera features need their own implementation and consent. UAC secure desktop and protected system surfaces cannot be bypassed.

## Not implemented yet
- Remote phone-to-PC execution through a secure encrypted relay.
- Screen capture, OCR, UI Automation tree reading, and AI screen feedback loop.
- File create/edit/copy/move/delete, recycle-bin recovery, and user-selected root management UI.
- Printer enumeration, print preview, and confirmed printing.
- Microphone/camera streaming, arbitrary settings changes, and background service mode.
- Signed installer, auto-update, and production certificate/key management.

## Build on Windows 10
Install the .NET 8 SDK, then run:

```powershell
dotnet build .\RuminCompanion.csproj -c Release
dotnet run --project .\RuminCompanion.csproj
```

The Companion listens on port 47831. Confirm locally at `http://127.0.0.1:47831/health`.

## Local data
- Token: `%LOCALAPPDATA%\RuminAI\Companion\companion.token`
- Approved roots: `%LOCALAPPDATA%\RuminAI\Companion\allowed-roots.json`
- Permissions: `%LOCALAPPDATA%\RuminAI\Companion\permissions.json`

Do not share tokens or commit local configuration files. This is a development build, not a production-secure remote-control product. Keep Windows Firewall enabled and do not expose port 47831 to the public internet.
