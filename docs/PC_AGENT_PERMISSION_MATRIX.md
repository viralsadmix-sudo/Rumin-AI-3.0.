# Rumin PC Agent — Windows Permission Matrix

This matrix defines what the PC user grants to Rumin. Windows does not expose one universal permission equivalent to Android Accessibility; permissions are capability-specific and some protected Windows surfaces remain unavailable.

| Capability | User consent / boundary | Current implementation |
|---|---|---|
| Discover Start Menu apps | Enable App discovery in PC Permissions | Implemented: Start Menu shortcuts only |
| Launch selected apps | Enable App launch; app must be in returned catalog | Implemented: allowlisted shortcut ID, no arbitrary shell command |
| Read folders | Enable Read approved folders; access limited to configured roots | Implemented: read-only listing of Documents and Pictures |
| Mouse and keyboard | Enable Mouse and keyboard control, then start a local session | Implemented locally: click, scroll, Unicode typing, allowlisted keys |
| Screen capture / OCR / UI Automation | Separate visible desktop session and user consent | Not implemented |
| File create/edit/copy/move/delete | Explicit action approval, root boundary, backup/undo | Not implemented; permission flag reserved |
| Printing | Select printer/document, preview, confirm final print | Not implemented; permission flag reserved |
| Microphone / camera | Windows privacy controls and visible capture indicator | Not implemented |
| Windows settings / administrator tasks | Windows UAC or Settings consent; cannot silently bypass | Not implemented |
| Phone-to-PC remote execution | Device identity, encrypted relay, replay protection, revoke | Pairing prototype only; remote desktop input is deliberately blocked |

## Agent execution contract

1. Gemini interprets the user's request and produces a typed action plan.
2. The Companion validates each requested tool against the capability registry and approved roots.
3. The PC user approves any high-impact action; screen control requires an active local session.
4. The Companion returns structured results and, once screen capture/OCR is implemented, fresh screen state.
5. The agent repeats until the task is verified or reports the exact blocker.

Unknown tool names, arbitrary shell text, path traversal, and unapproved operations must be rejected. Sending messages, deleting/overwriting files, installing software, printing, purchases, and changing security settings require explicit confirmation. A user can stop the desktop-control session immediately from the local dashboard.

## Current milestone

The current branch adds a permission registry, Start Menu discovery/launch, approved-folder listing, and local mouse/keyboard control. It is a development milestone, not yet the complete phone-controlled universal PC agent. The encrypted phone relay, screen understanding, OCR/UI Automation, file write operations, printing, and device-side confirmation loop remain future work.
