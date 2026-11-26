package com.ruminai.app

import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * OpenPhoneUse-inspired execution core, hosted by Rumin.
 *
 * This module deliberately does not register an Android AccessibilityService or
 * own voice capture. The host supplies the existing Rumin accessibility bridge.
 * Model planning and UI selection are integrated in a later adapter step.
 */
internal data class PhoneUseAction(
    val tool: String,
    val value: String = "",
    val argument: String = ""
)

internal data class PhoneUseResult(
    val ok: Boolean,
    val message: String,
    val raw: JSONObject? = null
)

internal fun interface PhoneUseDevice {
    fun execute(action: PhoneUseAction): PhoneUseResult
}

internal enum class PhoneUseTaskState {
    RUNNING, SUCCEEDED, FAILED, CANCELLED
}

internal data class PhoneUseTaskSnapshot(
    val id: String,
    val state: PhoneUseTaskState,
    val completedActions: Int,
    val lastMessage: String
)

/**
 * Bounded, auditable action runner. It accepts only the same narrow native
 * action vocabulary exposed by RuminAccessibilityService and verifies every
 * result before advancing. It never treats an attempted tap as success.
 */
internal class OpenPhoneUseEngine(
    private val device: PhoneUseDevice,
    private val maxActionsPerTask: Int = 24
) {
    private val ids = AtomicLong(0)
    private val tasks = ConcurrentHashMap<String, PhoneUseTaskSnapshot>()

    private val allowedTools = setOf(
        "inspect_screen", "click_text", "type_text", "scroll_down", "scroll_up",
        "home", "back", "recents", "notifications", "quicksettings"
    )

    fun newTaskId(): String = "phoneuse-${ids.incrementAndGet()}"

    fun runAction(taskId: String, action: PhoneUseAction): PhoneUseTaskSnapshot {
        require(taskId.isNotBlank()) { "Task ID is required" }
        val previous = tasks[taskId]
        if (previous != null && previous.state != PhoneUseTaskState.RUNNING) {
            return previous
        }
        val currentCount = previous?.completedActions ?: 0
        if (currentCount >= maxActionsPerTask) {
            return snapshot(taskId, PhoneUseTaskState.FAILED, currentCount, "Action limit reached")
        }
        if (action.tool !in allowedTools) {
            return snapshot(taskId, PhoneUseTaskState.FAILED, currentCount, "Tool is not allowed")
        }

        tasks[taskId] = PhoneUseTaskSnapshot(taskId, PhoneUseTaskState.RUNNING, currentCount, "Executing ${action.tool}")
        val result = try {
            device.execute(action)
        } catch (error: Exception) {
            PhoneUseResult(false, error.message ?: "Device action failed")
        }
        val verified = result.ok && result.message.isNotBlank()
        return snapshot(
            taskId,
            if (verified) PhoneUseTaskState.RUNNING else PhoneUseTaskState.FAILED,
            if (verified) currentCount + 1 else currentCount,
            result.message.ifBlank { "Device did not verify the action" }
        )
    }

    fun complete(taskId: String, message: String): PhoneUseTaskSnapshot {
        val previous = tasks[taskId]
            ?: return snapshot(taskId, PhoneUseTaskState.FAILED, 0, "Unknown task")
        if (previous.state != PhoneUseTaskState.RUNNING) return previous
        return snapshot(
            taskId,
            if (message.isNotBlank()) PhoneUseTaskState.SUCCEEDED else PhoneUseTaskState.FAILED,
            previous.completedActions,
            message.ifBlank { "Completion was not verified" }
        )
    }

    fun cancel(taskId: String): PhoneUseTaskSnapshot {
        val previous = tasks[taskId]
            ?: return snapshot(taskId, PhoneUseTaskState.CANCELLED, 0, "Cancelled")
        return snapshot(taskId, PhoneUseTaskState.CANCELLED, previous.completedActions, "Cancelled by user")
    }

    fun getTask(taskId: String): PhoneUseTaskSnapshot? = tasks[taskId]

    private fun snapshot(
        id: String,
        state: PhoneUseTaskState,
        count: Int,
        message: String
    ): PhoneUseTaskSnapshot {
        return PhoneUseTaskSnapshot(id, state, count, message).also { tasks[id] = it }
    }
}
