# Rumin Windows Companion — 0.6.0 (Windows 10/11)

The Companion is the Windows-side execution layer for the existing Rumin AI 3.0 PC Agent. It is a .NET 8 ASP.NET Core process; it is not yet a signed installer or a Windows service.

## Current functions
- Local health and capability status.
- Per-client rate limiting for one-time pairing-code guesses (8 attempts per five-minute window).
- Separate, opt-in permissions stored in `%LOCALAPPDATA%\RuminAI\Companion\permissions.json`.
- Start Menu shortcut discovery and launch by an ID returned by the local app catalog (no arbitrary command-line endpoint).
- Listing of approved Documents and Pictures roots, with the allowed-root map stored in `allowed-roots.json`.
- Permission-gated text-file create/overwrite and file move inside approved roots; file delete sends regular files to the Windows Recycle Bin. Paths are canonicalized and reparse-point traversal is rejected.
- Local Agent Memory stored in `%LOCALAPPDATA%\\RuminAI\\Companion\\agent-memory.json`; writes require explicit user consent and the memory permission.
- Windows printer enumeration and explicitly confirmed plain-text print jobs.
- Opt-in Windows UI Automation control-tree inspection (bounded depth/node count; password values redacted).
- Explicitly confirmed UIA Invoke and Value actions on enabled, non-password controls.
- Local, user-started desktop input session: mouse click by screen coordinates, scroll, Unicode text typing, and a small allowlist of keys.
- Permission-gated primary-display screenshot endpoint (JPEG, no-cache) while the local desktop session is active.
- Batched local agent execution for up to eight supported actions in one request.
- Desktop input and permission changes are restricted to loopback/local dashboard requests in this build. A phone bearer token cannot remotely control the desktop over the current plain-HTTP LAN transport.

## Permission model
The dashboard's **PC Permissions** page lets the Windows user separately enable:
- App discovery
- App launch
- Read approved folders
- Mouse and keyboard control
- Screen capture (separate opt-in; local only)
- Printing (printer discovery and confirmed plain-text jobs)
- File write/move/delete (approved roots only; delete sends files to Recycle Bin)
- Local Agent Memory (non-encrypted JSON; do not store secrets)
- Windows UI Automation (control tree and confirmed control actions)

All permissions default to off. The screen-control permission is not enough by itself: the user must also start a local desktop-control session. Screen capture additionally requires the separate screen-capture permission. The session can be stopped from the dashboard at any time.

Windows does not have one universal Android-style Accessibility toggle for every desktop capability. Some actions use Windows APIs; OCR, microphone, and camera features still need their own implementation and consent. UI Automation has a separate opt-in permission. UAC secure desktop and protected system surfaces cannot be bypassed.

## Not implemented yet
- Remote phone-to-PC execution through a secure encrypted relay and native Android pairing client.
- OCR and AI screen feedback loop.
- Gemini natural-language planner/tool loop and approval orchestration.
- Rich document/PDF/image print preview, print settings, and multi-page print layout.
- Microphone/camera streaming, arbitrary settings changes, and background service mode.
- Signed installer, auto-update, and production certificate/key management.

## Build on Windows 10/11
Install the .NET 8 SDK, then run:

```powershell
dotnet build .\RuminCompanion.csproj -c Release
dotnet run --project .\RuminCompanion.csproj
```

The Companion listens on port 47831. Confirm locally at `http://127.0.0.1:47831/health`; the JSON should report version `0.6.0` after updating.

## Local data
- Token: `%LOCALAPPDATA%\RuminAI\Companion\companion.token`
- Approved roots: `%LOCALAPPDATA%\RuminAI\Companion\allowed-roots.json`
- Permissions: `%LOCALAPPDATA%\RuminAI\Companion\permissions.json`

Do not share tokens or commit local configuration files. This is a development build, not a production-secure remote-control product. Keep Windows Firewall enabled and do not expose port 47831 to the public internet.
