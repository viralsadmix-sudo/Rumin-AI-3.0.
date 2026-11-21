# Rumin AI — Clean Rebuild Architecture

Status: foundation plan, 2026-11-21

## Product decision

Build Android-first as a native Android application. Do not start with a PWA: the first release needs reliable microphone/audio handling and user-authorized Android Accessibility actions, which a browser app cannot provide consistently. A PC companion can be designed after the Android foundation is stable.

The existing repository is an Android Gradle project with a WebView UI and Kotlin native bridge. The backup branch `backup/pre-rebuild-2026-11-21` preserves the pre-rebuild source snapshot.

## Product scope — phase 1 only

1. Personal Agent — first priority
   - Natural-language task understanding.
   - Explicit task planning and step-by-step execution.
   - Tool registry with strict allow-list.
   - User confirmation for sensitive actions.
   - Observe the screen, act, then verify the result.
   - Honest status: never claim an action succeeded without a verified result.
3. Live Voice — deferred until Agent and phone-control tests pass
   - Full-duplex microphone input and spoken response.
   - Interruption / barge-in handling.
   - Clear listening, thinking, speaking, connecting, and error states.
   - Audio lifecycle cleanup and diagnostics.
2. Local Memory — foundational support for the Agent
   - User-approved facts and task context.
   - Conversation history with clear retention controls.
   - Local persistence; secrets are never committed to the repository.

Out of scope for phase 1: video studio, voice cloning, social publishing, unrelated utility menus, and PC automation.

## Proposed Android architecture

- `app`: application entry point, lifecycle, permissions, navigation.
- `core/model`: task, message, memory, tool, and execution-state models.
- `core/agent`: planner, policy/confirmation gate, executor, observer, verifier.
- `core/voice`: audio capture/playback, realtime transport, interruption handling.
- `core/memory`: local persistence and user-controlled memory operations.
- `core/network`: provider transport, timeout/retry policy, error mapping.
- `feature/home`: minimal Rumin home and voice controls.
- `feature/chat`: text conversation and task progress.
- `feature/settings`: provider/API configuration and permission status.

Keep UI separate from agent execution and audio. No AI-generated text may directly invoke arbitrary Android intents or shell commands; all actions must pass through the registered tool policy.

## Current implementation status (2026-11-21)\n\n- Foundation branch now contains Agent contracts, a sequential runtime with approval checks and a separate verification state, local approved memory, and a minimal native shell.\n- An opt-in Android Accessibility service is being introduced with explicit disclosure. It does not retain or forward accessibility events.\n- Initial phone tools are allow-listed and confirmation-gated: read visible screen text, click a unique visible text match, scroll the active window, and type into the focused editable field. These tools must be registered by the Agent host; they are not yet exposed as a complete natural-language phone assistant.\n- Screen reads and UI actions remain sensitive. The app must show the planned target and obtain user approval before invoking them. Tool success is not equivalent to verified task success.\n- The language-model planner, task UI, persistent task audit, and on-device verification are still required.\n\n## Safety and reliability rules

- Ask before calls, messages, deleting data, payments, or other external/high-impact actions.
- Accessibility is opt-in and must be explained clearly.
- Never collect or upload screen content or notifications unless the user explicitly requests a feature that needs it.
- Store API credentials only in protected device storage; never put real keys in source, logs, or GitHub Actions output.
- All actions have task IDs, bounded timeouts, audit events, and explicit success/failure verification.
- Stop microphone, playback, and network resources cleanly when the user stops a session or the app leaves the active lifecycle.

## Build sequence

1. Replace the old app source on `main` only after the backup branch is verified.
2. Establish a minimal native app shell and repeatable CI build.
3. Add local memory and conversation model.
4. Implement the Agent planner, policy gate, sequential executor, task state, and verification.
5. Add the opt-in Accessibility bridge and confirmation-gated screen/read/tap/type/scroll tools.
6. Connect an actual language-model planner and task UI; never pretend a placeholder planner is intelligent.
7. Test on the target Android phone, including denied permission, ambiguous targets, failed actions, and cancellation.
8. Add Live Voice only after Agent tasks and phone-control flows pass tests.
9. Plan PC support as a separate companion after Android phase 1 is stable.

## Current-source findings to carry into the redesign

- Existing app uses a single WebView HTML file and a large `MainActivity.kt` bridge.
- Existing `AgentCore.kt` has an allow-listed tool registry and in-memory task logging, but planning is currently one action at a time and logs are not persistent.
- Existing Accessibility service can read visible text and click/type/scroll, but needs stronger lifecycle, node recycling, scope, and verification handling.
- Existing Gemini Live implementation uses raw PCM audio and WebSocket transport, but the UI's normal `startVoice()` path currently starts Android SpeechRecognizer rather than invoking the native Gemini Live session.
- Existing app also has separate text-chat API paths, local TTS, API-key settings, app shortcuts, and unrelated link utilities. These will not be carried into phase 1 unless they are required by the new Personal Agent or Live Voice.
- Existing release workflow builds an APK using the debug signing configuration; this is suitable only for testing, not a production release process.
