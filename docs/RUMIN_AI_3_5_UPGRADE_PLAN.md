# Rumin AI 3.5 — Safe Upgrade Plan

## Repository strategy

The existing Android repository remains the source of truth:

- Repository: https://github.com/viralsadmix-sudo/Rumin-AI-3.0.
- Protected working base: `main`
- Feature branch: `feature/rumin-ai-3.5-foundation`
- Branch base commit at creation: `f8a04b8ab2ad359b90dfa1c2d6d089adf7a8919f`

All changes in this phase must be made on the feature branch. Do not force-push, delete existing files, or merge into main before review and regression testing.

## Existing source inventory verified

- `app/src/main/assets/index.html` — existing Bengali WebView UI, styling, browser-side chat/provider settings and interaction logic.
- `app/src/main/java/com/ruminai/app/MainActivity.kt` — Android WebView host, native bridge, permissions, Android speech/TTS and Live Voice lifecycle.
- `AgentCore.kt` — first-stage typed tool registry, one-action planner, execution lifecycle and bounded in-memory audit events.
- `GeminiLiveVoice.kt` — Gemini Live WebSocket/audio implementation.
- `RuminAccessibilityService.kt` — screen inspection and basic accessibility actions.
- `RuminNotificationListenerService.kt` — registered notification listener service; currently does not store or forward notifications.
- `AndroidManifest.xml` — app permissions and service declarations.
- `.github/workflows/build-apk.yml` — cloud APK build/release workflow.
- `.github/workflows/self-repair-diagnostics.yml` — diagnostics workflow that does not modify source code.

## Preservation rules

1. Do not replace the existing `index.html` or `MainActivity.kt` wholesale.
2. Do not remove or rename existing native bridge methods, JS callback names, permission declarations, or service classes without a compatibility layer and tests.
3. Keep existing UI and voice behavior intact while adding new modules behind feature boundaries.
4. Add tests and inspect the complete diff after every meaningful change.
5. Keep API keys and signing credentials out of source control.
6. Keep the existing release workflow until a replacement is tested.

## Planned module boundaries

- Agent: expand `AgentCore` incrementally into task planning, tool contracts, approval, persistence, and audit/recovery.
- Video: add a backend API client and a dedicated Video Studio screen; the Android client must not run FFmpeg/OpenMontage workloads locally.
- Backend: deploy a separate authenticated API and isolated workers for OpenMontage.
- Bangla voice/subtitles: integrate server-side transcription/TTS/subtitle jobs while retaining native on-device TTS as a fallback.
- Android release: preserve applicationId and existing signing/release setup until a separately reviewed Play Store signing migration.

## First implementation gates

- Baseline build and workflow status recorded.
- JS-to-native bridge contract inventoried before editing.
- Existing chat, text voice, live voice, permissions, accessibility, and navigation regression checklist prepared.
- Backend API contract and AGPL compliance reviewed before adding production execution code.
- A test APK from the feature branch is installed and checked on the target Android device before merging.

## Current status

The feature branch was created successfully. The source inventory and OpenMontage integration design are being documented. No application source code has been changed by this plan, and no OpenMontage runtime has been integrated yet.
