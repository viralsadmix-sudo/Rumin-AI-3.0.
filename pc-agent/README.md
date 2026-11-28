# Rumin AI 3.0 — PC Agent Dashboard

This is the existing PC Agent dashboard for the Rumin AI 3.0 project. The current UI/navigation design is retained; the new work connects its pages to the Windows Companion.

## Current working areas
- Overview: checks whether the local Windows Companion is reachable.
- PC Permissions: separate opt-in toggles for app discovery, app launch, approved-folder read access, and local mouse/keyboard control.
- Windows Apps: discovers Start Menu shortcuts and opens a selected app after permission is granted.
- Files & Photos: browser image preview plus read-only listing of approved Documents/Pictures folders.
- Screen Control: local session start/stop, permission-gated desktop screenshot, coordinate click, scroll, Unicode typing, and a small allowlisted key set.
- Agent execution: one local batch request can execute up to eight supported deterministic actions, reducing request overhead for short tasks.

## Windows Companion
Build the Windows-side executable from `pc-agent/windows-companion/RuminCompanion.csproj`. Install the .NET 8 runtime if using the framework-dependent build, then start the Companion on the PC. It listens on port 47831.

The browser dashboard must be opened on the same Windows PC for the current local-control milestone. The PC user must grant each permission explicitly. The screen-control session is local and can be stopped at any time. Screen capture is a separate opt-in permission and returns a no-cache JPEG snapshot only while the local session is active.

## Not complete yet
This is not yet the full phone-controlled Universal PC Agent. Android-to-PC native pairing, encrypted relay, remote execution, screen OCR/UI Automation, Gemini tool loop, file write/copy/move/delete, printer control, and microphone/camera features are not complete. The Companion intentionally blocks remote app/file/desktop actions until secure device transport is implemented.

## Safety
- No arbitrary PowerShell/CMD execution endpoint.
- App launch is limited to discovered Start Menu shortcuts.
- File access is read-only and limited to configured roots.
- Mouse/keyboard input requires a local permission and active local session.
- Sensitive/destructive operations must require explicit confirmation before future implementation.
- Do not expose port 47831 to the public internet or share Companion tokens.
