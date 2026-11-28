# Rumin AI 3.0 — PC Agent Dashboard

This is the existing PC Agent dashboard for the Rumin AI 3.0 project. The current UI/navigation design is retained; the new work connects its pages to the Windows Companion.

## Current working areas
- Overview: checks whether the local Windows Companion is reachable and loads PC status/capabilities.
- System Diagnostics: tests health, system info, capabilities, and permission endpoints; reports failed endpoints instead of guessing.
- PC Permissions: separate opt-in toggles for app discovery, app launch, approved-folder read access, local mouse/keyboard control, and screen capture; unavailable features are clearly marked as not implemented.
- Windows Apps: discovers Start Menu shortcuts and opens a selected app after permission is granted.
- Files & Photos: browser image preview plus read-only listing of approved Documents/Pictures folders (explicit opt-in; disabled by default).
- Screen Control: local session start/stop, permission-gated desktop screenshot, coordinate click, scroll, Unicode typing, and a small allowlisted key set.
- Agent execution: one local batch request can execute up to eight supported deterministic actions, reducing request overhead for short tasks.\n- Windows UI Automation: reads a bounded control tree and supports explicitly confirmed Invoke/SetValue actions; password values are excluded.\n- Safety Center: reads a local JSONL audit trail of mutating API requests (timestamp, route, status and client address only); request bodies, API keys and typed text are not recorded. The log is capped at 500 events.

## Windows Companion

Current Companion source version: **0.6.0** (source has changed; rebuild and restart the Windows Companion before using the updated endpoints). Rebuild/restart the Companion after updating its source; the browser dashboard and Windows executable are separate pieces and both must be current.
Build the Windows-side executable from `pc-agent/windows-companion/RuminCompanion.csproj`. Install the .NET 8 runtime if using the framework-dependent build, then start the Companion on the PC. It listens on port 47831.

The hosted browser dashboard must be opened on the same Windows PC for the current local-control milestone. Chrome/Edge may request Local Network Access permission; allow it for this site. If Windows Firewall prompts, allow Private networks only. The PC user must grant each permission explicitly; all capabilities now default to off, including approved-folder read access. The screen-control session is local and can be stopped at any time. Screen capture is a separate opt-in permission and returns a no-cache JPEG snapshot only while the local session is active.

## Not complete yet
The Phone Connect page currently provides a local one-time browser-pairing prototype only; it does not connect the Android Rumin app or enable remote PC actions. The dashboard also removes third-party QR image hosting so pairing codes/links are not sent to an external QR service. The local audit history is available at `/v1/audit/recent` from the Safety Center.

This is not yet the full phone-controlled Universal PC Agent. Android-to-PC native pairing, encrypted relay, remote execution, screen OCR, encrypted Android-to-PC relay, remote execution, and a live Gemini tool loop are not complete. UI Automation is available only in a local, permission-gated session; file write/move/recycle and confirmed text printing have local endpoints, but have not yet been validated on a real Windows machine in this environment. The Companion intentionally blocks remote app/file/desktop actions until secure device transport is implemented.

## Troubleshooting `Failed to fetch`

1. Open the dashboard in Chrome/Edge on the **same Windows PC** that runs the Companion. On Android, `127.0.0.1` points to the phone, not the PC.
2. Start the Companion and open `http://127.0.0.1:47831/health` in that same browser. A JSON response with `status: ready` confirms the local process is listening.
3. Return to the HTTPS dashboard, click **Connect / Check this PC**, and allow the browser's Local Network Access prompt.
4. If the health URL does not load, the Companion is not listening on port 47831 or Windows security software is blocking it. If health loads but the dashboard fails, check browser site permissions and reload.

## Safety
- No arbitrary PowerShell/CMD execution endpoint.
- App launch is limited to discovered Start Menu shortcuts.
- File access is read-only and limited to configured roots.
- Mouse/keyboard input requires a local permission and active local session.
- Sensitive/destructive operations must require explicit confirmation before future implementation.
- Do not expose port 47831 to the public internet or share Companion tokens.
