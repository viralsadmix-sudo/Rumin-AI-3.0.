# Rumin AI 3.0 — Universal Agent Contract

## Product contract

Rumin is a goal-directed agent, not a collection of memorized voice commands and not an app launcher limited to a fixed catalog. The user states an outcome in ordinary language. Rumin interprets the intent, inspects the available context, chooses tools dynamically, plans the work, asks for approval before acting, executes, observes the result, recovers from failures, and verifies completion.

The same task model must work across Android and the paired Windows PC. The Windows Companion is an execution environment, not a separate assistant with a second command vocabulary.

## Required task lifecycle

1. **Understand** — preserve the user's actual goal, constraints, language, and conversation context. Ask one focused question only when a missing fact blocks safe progress.
2. **Discover** — query the live capability registry and inspect relevant screen, UI Automation tree, files selected by the user, application catalog, and browser context. Never assume a tool exists merely because a model proposed it.
3. **Plan** — create a multi-step plan with dependencies, expected observations, reversible/irreversible effects, and the exact permissions each step needs. Plans must not be restricted to a hard-coded list of app names or phrases.
4. **Preview and approve** — show the user the intended effects and request explicit approval before execution. Approval is scoped to the displayed plan and expires if the plan materially changes. A new approval is required for a newly introduced destructive, external, financial, privacy-sensitive, or irreversible action.
5. **Execute** — invoke typed, validated tools. Never execute model-generated shell/PowerShell text or arbitrary code. Tool adapters may use supported Windows APIs, UI Automation, browser automation, OCR, filesystem APIs, and input simulation according to permission.
6. **Observe** — after each meaningful action, collect fresh evidence: UIA tree, OCR text and bounds, screenshot, browser state, file metadata, or operation result.
7. **Adapt** — if the observed state differs from the expected state, re-plan from the new evidence. Do not blindly repeat a failed action.
8. **Verify** — distinguish attempted, executed, and verified. Report success only when evidence demonstrates the requested outcome. Otherwise report exactly what remains incomplete.

## Capability model (dynamic, not a fixed app list)

The Companion must expose a versioned capability registry. Each capability describes:
- stable capability ID and adapter/version;
- input/output JSON schema;
- required permission(s) and whether local user interaction is required;
- risk class (read-only, reversible write, external side effect, destructive, financial);
- supported Windows/session requirements;
- timeout, cancellation, and verification strategy.

The planner may select any capability present in the live registry. It must not invent capabilities. New adapters can be added without changing the natural-language command vocabulary. Application discovery is contextual evidence, not the boundary of what Rumin can do.

Initial adapter families:
- screen capture + OCR (text, confidence, bounding boxes);
- Windows UI Automation (control tree, invoke/value/selection/scroll patterns);
- mouse, keyboard, and scroll for controls without usable UIA patterns;
- browser automation with visible session and origin-aware permissions;
- approved-root file operations and file metadata/preview;
- installed application discovery and launch;
- print preview and confirmed print jobs;
- task history and user-approved local memory.

## Approval and safety policy

- All permissions default to off and are granted by the Windows user.
- Every task has a visible pause/stop control and an audit record.
- Read-only inspection may run after the user starts a task and grants the relevant access.
- File create/edit/overwrite/move, print, upload, message send/post/share, software install, settings changes, and other external or consequential actions require a preview and explicit approval.
- Delete, payment, purchase, account/security changes, and other high-impact actions require a separate just-in-time confirmation at the point of action; a general task approval is insufficient.
- Approval never authorizes unrelated actions or a materially changed plan.
- No UAC bypass, credential extraction, hidden monitoring, public exposure of the local Companion port, or arbitrary model-generated command execution.
- Browser automation must respect the user's logged-in session and site security controls; never ask the model to reveal passwords or authentication tokens.

## PC-to-phone transport requirements

The phone must not connect to the current development HTTP LAN endpoints for remote control. Before remote execution is enabled, implement authenticated encryption, device identity, replay protection, bounded message sizes, rate limits, session expiry, revocation, and explicit pairing confirmation on both devices. Pairing/signalling alone is not a secure command relay.

## Reliability and recovery

Each step records its plan ID, tool ID, arguments (with secrets redacted), approval ID, start/end time, result, and evidence reference. Retries must be bounded and idempotency-aware. A task can be cancelled between steps. After interruption or reconnection, Rumin must reconcile observed PC state before resuming; it must not assume that an interrupted action failed or succeeded.

## Acceptance tests

1. A user can ask in natural Bengali or English for an outcome without knowing tool names or command syntax.
2. A task involving an application not named in any hard-coded list can be planned using live screen/app discovery and available adapters.
3. The planner receives the live capability registry and cannot execute a tool absent from it.
4. The user sees a concrete plan and approves it before any write or external side effect.
5. If the screen changes unexpectedly, Rumin observes again and adapts rather than repeating stale coordinates.
6. OCR returns text, confidence, and screen bounds; the planner can use those bounds as evidence, not as guaranteed click targets.
7. UI Automation is preferred where a suitable control pattern exists; coordinate input is a fallback for an active, user-authorized session.
8. A task that fails halfway reports completed and remaining steps separately and can safely resume after state reconciliation.
9. Completion is marked verified only when the final state or output is checked.
10. Existing Android Live Voice, chat, memory, visual design, and phone-agent features continue to work unchanged.
11. The phone can control the PC only after secure native pairing and encrypted transport are implemented and tested.
12. A real Windows 10 test covers at least an unfamiliar desktop application, browser workflow, file operation, and print preview.

## Current implementation gap

The existing repository already contains an Android natural-language phone agent and a Windows Companion prototype with local screen capture, input, UI Automation, approved-root file operations, local memory, and confirmed plain-text printing. Those are useful adapters, but they do not yet constitute the requested Universal PC Agent.

The remaining core work is the OCR + screen-context observation loop, a live capability registry consumed by the planner, end-to-end plan approval/execution/re-observation/replanning, secure Android-to-PC transport, and real Windows 10 end-to-end testing. Do not describe the dashboard or current local-only prototype as completion of the Universal Agent.
