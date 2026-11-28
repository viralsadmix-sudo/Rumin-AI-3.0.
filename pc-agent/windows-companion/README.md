# Rumin Windows Companion — 0.6.1 (Windows 10/11)

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
- Gemini API planner endpoint that returns a validated preview for a strict allowlist; it never executes actions.
- Local, user-started desktop input session: mouse click by screen coordinates, scroll, Unicode text typing, and a small allowlist of keys.
- Permission-gated primary-display screenshot endpoint (JPEG, no-cache) while the local desktop session is active.
- Batched local agent execution for up to eight supported actions in one request.
- Windows-native speech output through the PC default audio device, with status and stop endpoints; speech endpoints remain loopback-only until the secure phone relay is implemented.
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
- Multi-step Gemini planner with screen-context feedback and full approval orchestration; current endpoint only returns a constrained preview.
- Rich document/PDF/image print preview, print settings, and multi-page print layout.
- Secure remote phone-to-PC speech routing (blocked until encrypted relay is implemented), microphone/camera streaming, arbitrary settings changes, and background service mode.
- Signed installer, auto-update, and production certificate/key management.

## Build on Windows 10/11
Install the .NET 8 SDK, then run:

```powershell
dotnet build .\RuminCompanion.csproj -c Release
dotnet run --project .\RuminCompanion.csproj
```

The Companion listens on port 47831. Confirm locally at `http://127.0.0.1:47831/health`. Local speech endpoints: `GET /v1/speech/status`, `POST /v1/speech/speak` with `{\"text\":\"...\"}`, and `POST /v1/speech/stop`. Speech plays through the Windows default audio output. These routes reject remote LAN clients until the secure phone relay is ready.

## Local data
- Token: `%LOCALAPPDATA%\RuminAI\Companion\companion.token`
- Approved roots: `%LOCALAPPDATA%\RuminAI\Companion\allowed-roots.json`
- Permissions: `%LOCALAPPDATA%\RuminAI\Companion\permissions.json`

Do not share tokens or commit local configuration files. This is a development build, not a production-secure remote-control product. Keep Windows Firewall enabled and do not expose port 47831 to the public internet.


## Phone pairing check (0.6.1)

1. Run `RuminCompanion.exe` on the Windows PC and keep its console window open.
2. On that same PC, open `http://127.0.0.1:47831/health`. It must return JSON with `"status":"ready"` and version `0.6.1`.
3. Open the PC Agent Dashboard in Chrome or Edge on that same Windows PC. A phone browser's `127.0.0.1` is the phone itself, not the PC.
4. Create the one-time code from the dashboard. Open the displayed `http://<PC-LAN-IP>:47831/pair?code=...` link on the phone while both devices are on the same private Wi-Fi.
5. Pairing status will update on the PC dashboard. This is browser pairing only; it does not enable remote PC actions in the Android Rumin app. Secure relay and native Android client remain disabled.

If the health URL does not load, the Companion is not running/listening or Windows Firewall is blocking it. Allow Private networks only; never expose port 47831 to the public internet.
