package com.ruminai.app.core.model

data class AgentStep(
    val id: String,
    val description: String,
    val toolName: String,
    val requiresConfirmation: Boolean
)

data class AgentPlan(
    val taskId: String,
    val userRequest: String,
    val steps: List<AgentStep>
)

enum class ExecutionStatus { DRAFT, AWAITING_APPROVAL, RUNNING, VERIFYING, SUCCEEDED, FAILED, CANCELLED }

data class MemoryEntry(
    val id: String,
    val text: String,
    val createdAtEpochMs: Long
)
