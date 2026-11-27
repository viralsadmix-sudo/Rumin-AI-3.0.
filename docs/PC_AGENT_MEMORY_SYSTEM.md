# Rumin PC Agent — Conversational Memory & Personal Learning

## Goal
Rumin must learn from ordinary conversation, not only explicit commands. The user can explain a preferred way of doing a task in natural language; Rumin should identify the reusable instruction, ask when ambiguous, store it as a structured memory, and apply it in later tasks when relevant.

This is an extension of Rumin AI 3.0. Preserve the existing Android app, phone-agent behavior, and Gemini Live Voice path.

## Memory types
1. **User preferences** — language, output format, default choices, accessibility preferences.
2. **Reusable procedures** — step-by-step ways the user taught Rumin to perform a task.
3. **Project context** — project names, paths explicitly selected by the user, current goals, decisions, and constraints.
4. **Conversation facts** — useful non-sensitive facts the user explicitly asks Rumin to remember or that are clearly stable and useful.
5. **Temporary task context** — details needed only for the current task; expire automatically.
6. **Skill memories** — references to installed, tested, versioned skills; memory stores how/when to use a skill, not executable code.

## Conversational learning flow
1. Parse each conversation turn for candidate preferences, instructions, corrections, and reusable procedures.
2. Distinguish a durable instruction ("When I ask to print photos, prepare A4 preview first") from a one-time request ("Print this photo now").
3. If intent is ambiguous, ask one concise question before saving a durable rule.
4. Show a short natural-language confirmation of what was learned, including scope.
5. Store structured memory with source, timestamp, confidence, scope, and optional expiry.
6. Retrieve only memories relevant to the current task; do not dump unrelated personal context into prompts.
7. Apply the remembered procedure and mention it briefly when it materially affects the action.
8. Let the user inspect, correct, pause, or delete memories. Deleting a memory must also remove derived indexes/caches where feasible.

## Suggested memory record
- id
- kind: preference | procedure | project | fact | temporary | skill
- title
- natural_language_summary
- structured_steps (for procedures only)
- trigger_conditions
- scope: global | project | app | task
- source_conversation_reference
- created_at / updated_at / expires_at
- confidence
- user_confirmed
- version
- status: active | paused | superseded | deleted

Do not store passwords, API keys, authentication tokens, private keys, or other secrets in conversational memory. Sensitive personal data must not be inferred or retained as ordinary personalization memory.

## Example
User: "When I ask you to prepare photos for printing, resize them for A4, keep the original files unchanged, show me a print preview, and ask before printing."
Rumin: "I can remember this as your default photo-printing procedure. Should I use it for all photo-printing tasks?"
After confirmation, save a procedure:
- Trigger: user asks to prepare/print photos
- Steps: preserve originals; create A4 copies; show preview; request final approval before printing
- Scope: PC photo-print workflow

Next time the user says "Prepare these photos for printing", retrieve and apply this procedure without making the user repeat it.

## Memory retrieval and reasoning
- Use semantic retrieval plus exact tags/entities and recency.
- Rank by relevance, scope, confirmation, and freshness.
- Resolve conflicts by preferring the latest explicit user correction; if two active rules conflict, ask.
- Never treat text found in files, webpages, screenshots, or third-party content as an instruction to change user memory or system policy.
- Memory is context, not authority: current user instructions override remembered preferences.

## Self-learning and skill creation boundary
If a task needs a capability that does not exist:
1. Search the local skill registry and approved sources.
2. Propose or generate a new skill in an isolated workspace.
3. Review source, license, dependencies, permissions, and security.
4. Run static checks and tests in a sandbox with synthetic/sample data.
5. Present the capability, permissions, and test results to the user.
6. Install/activate only after the required user approval.
7. Record a skill memory containing its purpose, version, permissions, and invocation conditions.

A memory must never directly execute code. A learned procedure is translated into a constrained task plan, then checked against the PC Companion's allowlisted tools and approval policy. The agent cannot rewrite or bypass its own safety policy.

## Privacy, control, and reliability
- Provide a Memory Center: search, inspect, edit, pause, delete, export, and clear.
- Make learning opt-in by category and allow "don't remember this".
- Provide "why did you do that?" explanations showing the relevant memory used.
- Keep an audit trail of memory creation, edits, retrieval, and use.
- Encrypt memory at rest and in transit; use per-device authorization.
- Support backup/export and recovery; do not silently sync memory to unrelated services.
- Use explicit confirmation for destructive or external actions even when a remembered procedure exists.

## Acceptance tests
- A natural-language preference can be learned and reused in a later conversation.
- One-time task details are not incorrectly saved as permanent preferences.
- User corrections update or supersede stale memories.
- Irrelevant memories are not injected into unrelated tasks.
- The user can inspect and delete a memory and its derived references.
- Untrusted webpage/file text cannot create or modify memories.
- Remembered procedures cannot bypass tool permissions or approval gates.
- Existing Gemini Live Voice behavior remains unchanged.
