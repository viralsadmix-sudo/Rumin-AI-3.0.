# Rumin AI 3.0 — Universal Personal Agent Roadmap

## Goal

Turn Rumin into a user-controlled Android personal agent that understands natural Bengali/English instructions, plans multi-step tasks, operates supported phone UI/native APIs, observes the result after each action, and reports only what it can verify.

This roadmap extends the existing Gemini/Groq Agent Core. It does not replace the Live Voice implementation.

## Current verified foundation in this branch

- Gemini Agent Runner with function calling and a shared Native Executor.
- Groq fallback adapter using the same tool definitions/executor.
- User-enabled Accessibility Service for reading visible UI text, clicking exact labels, entering text in a focused field, scrolling, swiping, and supported global navigation.
- Screenshot capture is request/action-scoped, not continuous; protected windows and some app surfaces may block it.
- Encrypted app-private memory storage using Android Keystore AES-GCM, with chat history and explicitly saved facts in the WebView memory flow.
- App launching, screen inspection/interactions, navigation, volume, Settings deep links, Clock alarm/timer prefill, dialer opening, and SMS draft composition.
- Safety limits: no automatic calls, SMS sending, delete, payment, purchase, transfer, or permission granting.
- Redacted bounded action audit and interrupted-task recovery without automatic replay.

## Changes in this implementation step

1. The Gemini tool loop is limited to one tool call per model round. The next action must be planned after the prior result and, for screen-changing actions, a fresh screen observation.
2. The maximum planning/observation rounds are raised from 4 to 8 so a task can complete more sequential steps without allowing parallel UI actions.
3. The Universal Personal Agent behavior rules are included in the Gemini system prompt.

## Important current gaps (not yet implemented)

- Native encrypted memory is not yet directly exposed to Gemini/Groq as structured read/write tools. The current WebView memory UI can save explicitly requested facts, but the agent does not yet have a safe native memory retrieval/write tool contract.
- The current planner is still mostly model-led tool selection plus a one-active-action guard; it is not yet a durable dependency graph with resumable user-approved plans.
- Action outcomes still need a formal state model: accepted, completed, verified, needs_confirmation, failed, and unknown. A non-empty verification label alone must not be treated as proof of task completion.
- The current screen tools cover generic accessibility interactions, not every app-specific workflow. Some apps expose incomplete or no accessibility trees.
- Contacts, calendar, notification actions, direct calling/SMS sending, file management, and background scheduling are not generally available through the current tool set.
- Groq does not receive screenshot image input in the current adapter; visual understanding is limited to the Gemini path.
- Full device testing on the target Redmi Note 8 / Pixel Experience ROM is still required.

## Implementation phases

### Phase 1 — Safe sequential task execution
- Keep one UI-changing action in flight at a time.
- After each UI-changing action, wait for the UI to settle, capture/read the current screen, and re-plan.
- Never replay an action with an unknown outcome.
- Stop safely at missing permissions, ambiguous targets, or confirmation boundaries.

### Phase 2 — Personal memory integration
- Add native tools to retrieve, explicitly save, and inspect user-approved facts from the encrypted store.
- Keep memory disabled when the user turns it off; do not silently infer permanent facts.
- Add a separate, explicit setting controlling whether saved facts may be sent to the selected cloud AI provider as prompt context. Default that sharing setting to off.
- Reject credentials, OTPs, payment data, and unnecessary sensitive personal data from saved facts.
- Add tests for save, retrieval, disabled memory, corrupted encrypted data, and uninstall/backup behavior.

### Phase 3 — Typed plan and outcome model
- Represent each task step with a stable ID, dependencies, risk level, required permission, confirmation requirement, preconditions, postconditions, timeout, and retry policy.
- Use explicit outcome states: accepted, completed, verified, needs_confirmation, failed, unknown.
- Treat only an observed postcondition as verified. An Android intent being dispatched is not proof that the user-visible goal is complete.
- Persist only redacted task metadata; never persist message bodies, secrets, or raw screen text in the audit log.

### Phase 4 — Android capability expansion
- Add contacts lookup and contact disambiguation with the Android Contacts Provider.
- Add calendar read/create/update with explicit confirmation for writes.
- Add notification summaries/actions only with Notification Listener permission and narrowly scoped actions.
- Keep calls and SMS as reviewable drafts until the user explicitly approves a separate send flow.
- Add media controls, app shortcuts, clipboard, file picking, and system settings only where Android APIs and user permissions support them.
- Do not claim full control over every app: app security, protected windows, custom canvases, and Android policy can prevent automation.

### Phase 5 — Screen understanding and robust UI control
- Prefer AccessibilityNodeInfo labels, roles, bounds, enabled/clickable/focusable state, and scrollability over coordinates.
- Use screenshots only when needed and available; coordinate taps must be grounded in the latest screenshot and a visible, non-sensitive target.
- Re-read the screen after every UI mutation and verify the expected state.
- Add safeguards for login, OTP, passwords, payment, purchase, delete, send, account changes, and permission dialogs.

### Phase 6 — Test, release, and device validation
- Add automated tests for Bengali/English intent variations, multi-step sequencing, ambiguity, denied permissions, stale screens, duplicate prevention, unknown outcomes, and confirmation gates.
- Build the release APK in GitHub Actions and publish an artifact only after a successful build.
- Test on the target Redmi Note 8 / Pixel Experience device with Accessibility enabled and disabled, network loss, app process death, and protected app screens.
- Keep Gemini Live Voice code and audio/session flow isolated unless a separate voice-specific fix is requested and tested.

## Acceptance tests

1. "YouTube খোলো, আমার বলা channel খুঁজে দাও" opens YouTube, observes the screen, then proceeds only from visible controls.
2. "এই পেজটা পড়ে নিচে নামাও, তারপর কী আছে বলো" inspects, scrolls once, re-inspects, and summarizes only visible evidence.
3. "রহিমকে কাল আসতে বলো" asks for recipient disambiguation if needed, creates a draft, and never sends it.
4. "সকাল ৭টায় অ্যালার্ম দাও" opens a prefilled Clock alarm and clearly asks the user to save it.
5. A missing Accessibility permission results in a Settings route/instructions, never a claim of success.
6. If an action times out after starting, Rumin reports unknown outcome and does not repeat it automatically.
7. With memory disabled or cloud-memory sharing disabled, saved facts are not injected into cloud AI prompts.
8. A multi-step task never executes two UI-changing tool calls from the same stale observation.

## Release rule

Do not describe the Universal Personal Agent as complete until the relevant phases have passed automated checks and target-device tests. Do not claim an APK is ready unless a successful GitHub Actions artifact is available.
