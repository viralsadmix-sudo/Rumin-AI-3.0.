# RUMIN AI 3.0 — Agent System Roadmap (Six Pillars)

**Product objective:** Make Gemini the reasoning/planning brain of Rumin while Rumin's Android-native layer executes only registered, permission-gated actions and verifies their results. The objective is reliable instruction-following and real task completion—not a larger chat UI or a prompt that merely tells Gemini to act.

## Current baseline (audited 2026-10-31)

- `MainActivity.requestAi()` calls Gemini Interactions API with `model` and plain text `input`; it does not send a `tools` declaration or process `function_call` steps.
- `index.html` routes many common phrases through a local `runLocalCommand()` phrase matcher before sending the remaining request to Gemini.
- `AgentCore.kt` is present on main and provides a first tool registry, one-action plan, execution lifecycle, callback result check, and bounded in-memory event list.
- The Agent Core is currently reached from the UI's native command bridge; Gemini itself does not yet choose or invoke its tools.
- Saved facts are still primarily managed in browser localStorage on main. An encrypted SQLite/Android Keystore MemoryManager experiment exists only on a development branch and has not been validated/merged.
- GitHub Actions generated release v3.0.159 (2,313,768 bytes) on 2026-10-30. A green CI build proves compilation/package generation only; it does not prove installation, microphone operation, Gemini tool calling, accessibility actions, or reliable execution on the Redmi Note 8.

## Non-negotiable architecture

```
User (text / voice)
        |
        v
Conversation + Context Manager
        |
        v
Gemini 3.8 Flash — Function Calling
        |
        v
Agent Planner / Policy Gate
        |
        v
Tool Registry (allow-listed schemas)
        |
        v
Android Tool Executor
        |
        v
Native result + evidence
        |
        v
Gemini receives function_result
        |
        v
Verified final answer to user
```

Gemini proposes a structured tool call; it does not directly control Android. The app validates the tool name, arguments, permissions, risk class, and user approval before execution. The native result is returned to Gemini, which then explains the actual result. If execution did not happen or cannot be verified, Rumin must say so.

## Pillar 1 — Foundation (P0; block all other feature expansion)

### Components
1. **AgentCore:** one entry point for text and voice tasks.
2. **Tool Registry:** canonical schemas, risk level, required permissions, supported Android versions, timeout, and result schema for every tool.
3. **Planner:** convert user intent into one or more typed tool calls; ask a short clarification when arguments are ambiguous.
4. **Policy/Permission Gate:** reject unknown tools; check runtime, Accessibility, Notification Listener, and special access before execution.
5. **Executor:** execute one tool at a time initially; use bounded timeouts and cancellation; never block the main/UI thread.
6. **Verifier:** distinguish "intent dispatched" from "action confirmed". Opening an external app can be confirmed only to the extent Android reports the launch; message sending, call completion, or a changed UI needs stronger evidence.
7. **Gemini Tool Loop:** send function declarations to Gemini Interactions API; parse `function_call` steps; execute locally; send `function_result` with matching `call_id`; request final answer. Cap tool rounds and reject malformed/unknown calls.
8. **Task state:** `queued → planning → awaiting_approval → running → verifying → succeeded/failed/needs_user`.

### First safe tool set
- `open_app`: allow-listed app IDs for YouTube, Facebook, WhatsApp, Settings, Camera, Maps, etc.
- `device_navigation`: home/back/recents/notifications/quick settings, subject to Accessibility availability.
- `inspect_screen`: read visible text and UI structure; no click or typing.
- `volume_control`: bounded media-volume changes.
- `create_alarm_or_timer`: hand off to Android clock UI and report handoff, not silently claim a saved alarm.
- `open_settings_page`: open a specific Android settings screen.

Calls, SMS, message sending, destructive actions, purchases, permission changes, and data export/deletion must use a confirmation flow. Never expose a generic shell, arbitrary code execution, unrestricted URL launch, or unrestricted tap tool to Gemini.

### Foundation acceptance tests
- A normal question does not invoke a tool.
- "Open YouTube" produces a valid function call, launches the allow-listed app/fallback, returns a result to Gemini, and gives a truthful final answer.
- Unknown tool/action is rejected and logged.
- Missing permission returns a clear next step; it does not loop or pretend success.
- A malformed argument, duplicate task ID, timeout, app-not-installed case, or executor exception returns a structured failure.
- A second tool call is not started until the first result is received (initial release).
- Text mode never unexpectedly speaks; Live mode uses the same task/policy/execution path.

## Pillar 2 — Memory (P1, after foundation tool loop)

### Separate memory types
- **Conversation history:** recent chat transcript, bounded retention.
- **User-approved facts:** explicit "remember this" facts and preferences.
- **Task memory:** task status, last result, retry count, and recovery checkpoint.
- **Screen/action context:** short-lived, expires automatically; do not store screenshots or sensitive screen content by default.

### Implementation
- Use SQLite for structured storage; encrypt user-approved facts with an Android Keystore AES-GCM key.
- Store minimal data; redact API keys, passwords, OTPs, payment details, and message bodies from logs.
- Provide list, correct, delete-one, export, and clear-all controls.
- Import existing localStorage facts once, deduplicate, verify counts, and retain a rollback backup until migration is confirmed.
- Add explicit retention and backup policy. Keystore-bound encrypted data may not be portable across reinstall/device reset; explain this rather than promising automatic restoration.

### Acceptance tests
- Save → app restart → retrieve; duplicate fact updates instead of multiplying.
- Delete one fact; clear all; export and re-import.
- Corrupt DB/key error gives recovery guidance, not silent data loss.
- No secrets appear in logs or model prompts unless required for the current task.

## Pillar 3 — Screen (P2)

- Use Android AccessibilityService as the primary UI observation/action channel; use Notification Listener only for notification tasks the user has authorized.
- Separate **observe** from **act**: inspect first, summarize relevant controls, then ask for confirmation before consequential taps.
- Represent screen elements as stable snapshots with text, content description, class, bounds, clickable/enabled state, and a short-lived node reference.
- Add stale-screen detection: re-inspect after navigation, loading, app switch, or timeout before tapping.
- Do not promise arbitrary control of secure, protected, DRM, banking, or apps that block accessibility.
- Never collect or store full screen content continuously. Use just-in-time inspection and redact sensitive fields.

### Acceptance tests
- Inspect current screen and accurately report visible labels.
- Tap a visible, allow-listed low-risk control only after explicit user instruction.
- If node disappears or screen changes, abort and re-inspect.
- Text input into passwords, OTPs, payment fields, or private messages is blocked or requires explicit high-risk confirmation.

## Pillar 4 — Recovery (P3)

- Persist a task checkpoint before each tool call.
- Classify errors: permission missing, app absent, network timeout, Gemini error, stale UI, unsupported action, user cancellation, transient system failure.
- Retry only idempotent operations; use capped exponential backoff and total deadline.
- Never automatically retry calls, SMS, payments, posting, deletion, or other non-idempotent actions.
- After a crash/restart, show interrupted tasks and ask whether to resume when resumption could have side effects.
- Keep a safe stop/cancel action available.

### Acceptance tests
- Simulated timeout, missing permission, unavailable app, duplicate callback, process restart, and stale UI.
- Retry budget is bounded; no infinite loop.
- No duplicate message/call/post after retry.
- User sees exact failure reason and next safe step.

## Pillar 5 — GitHub (P4)

- Keep GitHub automation separate from Android phone tools.
- Use least-privilege repository permissions; work on feature branches; inspect diffs; run tests/build; create PRs.
- Never push straight to main or merge/release without the user's explicit approval for that change.
- Store no GitHub token in chat history, logs, app localStorage, or source code.
- Build artifact must be traceable to commit SHA, workflow run, versionCode, and release tag.

### Acceptance tests
- Read repository status; create isolated branch; propose a minimal diff; run CI; show diff and checks.
- Merge and publish only after explicit approval.
- Failed workflow produces diagnostics and never auto-edits production source.

## Pillar 6 — Logs (P5, instrumentation begins with Foundation)

- Use structured events: task_id, timestamp, user intent summary, model/tool name, sanitized arguments, approval state, permission state, execution start/end, result, verification evidence, retry count, and error class.
- Redact secrets and sensitive payloads before persistence.
- Store logs in bounded SQLite with rotation/export/clear controls.
- Keep an audit trail for destructive/external actions, including the user's confirmation.
- Add a diagnostic view: recent tasks, failed tasks, permission status, Gemini latency, tool latency, and build/version information.

### Acceptance tests
- Every task has a complete start/end or interrupted record.
- Tool arguments never contain API keys, OTPs, or full message bodies in logs.
- User can inspect and clear logs.
- Logs distinguish "command handed off" from "effect verified".

## Delivery sequence

| Stage | Scope | Exit condition |
|---|---|---|
| 0 | Baseline audit + regression tests | Existing chat/voice/build behavior documented |
| 1 | Gemini Function Calling + one safe native tool | "Open YouTube" works end-to-end with truthful result |
| 2 | Foundation hardening + text/Live shared executor | Same tools behave consistently in text and Live |
| 3 | Encrypted Memory + migration | Save/restart/retrieve/delete tests pass |
| 4 | Screen observe then controlled act | Stale-screen and confirmation tests pass |
| 5 | Recovery checkpoints and safe retries | No duplicate side effects in failure tests |
| 6 | Persistent Logs + diagnostic screen | Full task trace available and redacted |
| 7 | GitHub workflow integration | PR/CI flow is least-privilege and approval-gated |
| 8 | Device acceptance + release | Tested on target Redmi Note 8; signed/versioned APK installed and core scenarios pass |

## Release gate — do not call a build successful based on CI alone

A release candidate must pass:
1. GitHub assembleRelease and lint/static checks.
2. APK artifact exists and SHA/version are recorded.
3. Install/upgrade on the target Android device.
4. App launches; API key save/test succeeds.
5. Text question/answer succeeds.
6. Text command "Open YouTube" executes through Gemini tool calling.
7. Accessibility screen inspection works after permission is enabled.
8. Live voice capture and audio response are tested separately.
9. App restart retains the expected history/memory.
10. No false success claims, duplicate actions, or unhandled crash in the above tests.

## Current next action

Implement and validate **Gemini Function Calling → AgentCore → native tool result → Gemini final response** with only the low-risk `open_app` and `inspect_screen` tools first. Do not expand the command catalogue or add visual features until this end-to-end loop passes.
