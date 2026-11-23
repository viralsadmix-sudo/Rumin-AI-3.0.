# Rumin AI 3.0 — Video Reference Analysis & UI Direction

Reference: user-uploaded 34m 34s screen-recorded tutorial showing a personal assistant app branded "Maya". This document records the visible product patterns to adapt, not copy.

## What is visible in the reference
- A full-screen, dark navy assistant home with a large friendly animated female character as the visual focus.
- Greeting and conversational status near the character (examples visible: "Good evening, dost", "Bol rahi hai", "Sun rahi hai").
- A prominent central voice control, visually indicating listening / speaking / idle state.
- A compact bottom navigation with Home, Scan, central voice action, Yaadein, and Chat.
- Quick action tiles for music, study, and journal; small information cards for weather, date, and mood.
- A dedicated voice-engine settings page with selectable voice modes and an LLM/provider selector.
- A separate agentic-plan/provider configuration view. The video shows provider choices and configuration fields, but does not establish that every provider or every displayed function works reliably.
- The presenter demonstrates app screens and setup/configuration flows; visual frames also show Google AI Studio API-key setup and several settings screens.
- The screen recording includes comparison/marketing claims. Those claims are not treated as verified technical benchmarks.

## Rumin adaptation (must remain Rumin)
- Keep RUMIN AI 3.0 name, Bengali-first greeting, and Rumin identity. Do not use the Maya name, logo, character art, or exact copy.
- Use a premium deep-navy / electric-blue / cyan visual system with restrained pink accents, glass-like cards, subtle glow, and animated depth.
- Home screen: animated Rumin character/agent presence in the upper-middle; greeting; live status text; one large microphone/Live Voice action; task composer; compact quick actions; bottom navigation.
- Voice statuses must be truthful and state-driven: "শুনছি", "ভাবছি", "উত্তর দিচ্ছি", "Agent-এর কাছে পাঠাচ্ছি", "সংযোগ বিচ্ছিন্ন". Never display "শুনছি" unless microphone capture is actually active.
- Bengali speech input and spoken Bengali output are required. A spoken command must be passed into the same Agent task pipeline used by typed input; it must not create a separate, weaker command system.
- The user should be able to interrupt/stop voice, see recognized text, review sensitive actions, and hear the Agent's actual result.
- Navigation targets: Home, Scan/Screen, Voice, Memory, Chat/Agent. Quick actions should only be shown when they have working implementations; do not add decorative non-functional buttons.
- Agent execution must remain allow-listed and approval-gated for sensitive actions. A spoken request must never bypass approval.
- Keep API/model diagnostics in Settings, not on the primary home screen. Do not expose raw API error JSON in the home layout; show a concise Bengali error with a diagnostic detail view.
- Maintain fast feedback: show immediate listening/processing state, avoid indefinite spinners, and surface a clear timeout/retry message.

## Implementation order
1. Implement the new Rumin home layout and visual hierarchy.
2. Integrate the animated character as a local app asset with lightweight motion (float/breathing); keep a graceful fallback on low-memory devices.
3. Make Live Voice and typed Chat feed the exact same Agent request pipeline.
4. Stream truthful voice/task states to the home UI; ensure Agent result is spoken in Bengali and listening resumes only after playback completes.
5. Wire each quick action to a real feature or hide it until implemented.
6. Verify on the user's Redmi Note 8 / Android 16+ with real microphone, network, and Accessibility tests; run release build before sharing an APK.

## Verification caveat
The video was reviewed visually across its full 34m 34s timeline using periodic frame sampling and representative full-resolution frames. Audio narration was not transcribed in this review. Therefore this document records visible UI and screen flows only; it does not claim to verify every spoken explanation or feature behavior shown in the video.
