package com.ruminai.app.core.voice

enum class VoiceSessionState {
    IDLE, CONNECTING, LISTENING, THINKING, SPEAKING, INTERRUPTED, ERROR
}

/** UI-facing state holder. Audio transport is intentionally implemented separately. */
class VoiceSessionStateMachine {
    var state: VoiceSessionState = VoiceSessionState.IDLE
        private set

    fun transition(next: VoiceSessionState) {
        val allowed = when (state) {
            VoiceSessionState.IDLE -> setOf(VoiceSessionState.CONNECTING)
            VoiceSessionState.CONNECTING -> setOf(VoiceSessionState.LISTENING, VoiceSessionState.ERROR, VoiceSessionState.IDLE)
            VoiceSessionState.LISTENING -> setOf(VoiceSessionState.THINKING, VoiceSessionState.INTERRUPTED, VoiceSessionState.ERROR, VoiceSessionState.IDLE)
            VoiceSessionState.THINKING -> setOf(VoiceSessionState.SPEAKING, VoiceSessionState.LISTENING, VoiceSessionState.ERROR, VoiceSessionState.IDLE)
            VoiceSessionState.SPEAKING -> setOf(VoiceSessionState.LISTENING, VoiceSessionState.INTERRUPTED, VoiceSessionState.ERROR, VoiceSessionState.IDLE)
            VoiceSessionState.INTERRUPTED -> setOf(VoiceSessionState.LISTENING, VoiceSessionState.THINKING, VoiceSessionState.IDLE, VoiceSessionState.ERROR)
            VoiceSessionState.ERROR -> setOf(VoiceSessionState.CONNECTING, VoiceSessionState.IDLE)
        }
        require(next in allowed) { "Invalid voice state transition: $state -> $next" }
        state = next
    }
}
