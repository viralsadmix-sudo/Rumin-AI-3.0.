package com.ruminai.app

import org.json.JSONObject
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Validates native tool requests and tracks verified task completion. */
internal data class AgentPlan(
    val taskId: String,
    val category: String,
    val action: String,
    val firstArgument: String,
    val secondArgument: String,
    val createdAt: Long
)

internal data class AgentLogEvent(
    val time: Long,
    val taskId: String,
    val action: String,
    val state: String,
    val detail: String
) {
    fun toJson(): JSONObject = JSONObject()
        .put("time", time).put("taskId", taskId).put("action", action)
        .put("state", state).put("detail", detail)
}

internal class AgentToolRegistry {
    private val commandTools = setOf(
        "home", "back", "recents", "notifications", "quicksettings", "power", "open_app",
        "writesettings", "volumeup", "volumedown", "volumemute", "volumeunmute",
        "alarm", "timer", "showalarms", "showtimers", "call", "sms", "dialer",
        "accessibility", "notificationaccess", "apppermissions", "ttssettings",
        "settings", "wifi", "bluetooth", "display", "sound", "camera", "contacts",
        "browser", "youtube", "facebook", "whatsapp", "instagram", "gmail",
        "playstore", "maps"
    )
    private val accessibilityTools = setOf(
        "inspect_screen", "click_text", "type_text", "scroll_down", "scroll_up",
        "back", "home", "recents", "notifications", "quicksettings", "power"
    )

    fun contains(category: String, action: String): Boolean = when (category) {
        "command" -> action in commandTools
        "accessibility" -> action in accessibilityTools
        else -> false
    }
}

internal class AgentPlanner(private val registry: AgentToolRegistry) {
    fun createPlan(taskId: String, category: String, action: String, first: String, second: String): AgentPlan? {
        val normalizedCategory = category.trim().lowercase()
        val normalizedAction = action.trim().lowercase()
        if (taskId.isBlank() || !registry.contains(normalizedCategory, normalizedAction)) return null
        return AgentPlan(taskId, normalizedCategory, normalizedAction, first.take(2000), second.take(2000), System.currentTimeMillis())
    }
}

internal object AgentVerifier {
    fun verifyCallback(ok: Boolean, message: String): Boolean = ok && message.isNotBlank()
    fun verifyNativeJson(raw: String): Boolean = try {
        val result = JSONObject(raw)
        verifyCallback(result.optBoolean("ok", false), result.optString("message"))
    } catch (_: Exception) { false }
}

internal class AgentLogger(private val capacity: Int = 120) {
    private val events = ArrayDeque<AgentLogEvent>()
    @Synchronized fun record(event: AgentLogEvent) {
        events.addLast(event)
        while (events.size > capacity) events.removeFirst()
    }
    @Synchronized fun snapshot(): List<AgentLogEvent> = events.toList()
}

internal class AgentExecutor(private val logger: AgentLogger) {
    private val running = ConcurrentHashMap<String, AgentPlan>()

    fun begin(plan: AgentPlan): Boolean {
        if (running.putIfAbsent(plan.taskId, plan) != null) return false
        logger.record(AgentLogEvent(System.currentTimeMillis(), plan.taskId, plan.action, "running", "Native tool execution started"))
        return true
    }

    fun hasRunningTask(): Boolean = running.isNotEmpty()

    fun complete(taskId: String, ok: Boolean, message: String): Boolean {
        val plan = running.remove(taskId) ?: return false
        val verified = AgentVerifier.verifyCallback(ok, message)
        logger.record(
            AgentLogEvent(
                System.currentTimeMillis(), taskId, plan.action,
                if (verified) "succeeded" else "failed",
                if (verified) "Native callback verified" else "Native callback reported failure"
            )
        )
        return verified
    }

    fun recentEvents(): List<AgentLogEvent> = logger.snapshot()
}

/**
 * Foundation v1: typed tool registry, one-action planning, execution lifecycle,
 * callback verification, and bounded in-memory audit events. Persistent logs,
 * multi-step model planning, and recovery are separate later modules.
 */
class AgentCore {
    private val logger = AgentLogger()
    private val registry = AgentToolRegistry()
    private val planner = AgentPlanner(registry)
    private val executor = AgentExecutor(logger)
    private val generatedIds = AtomicLong(0)

    @Synchronized
    fun begin(action: String, first: String, second: String, category: String, requestedTaskId: String): Boolean {
        val taskId = requestedTaskId.ifBlank { "agent-${generatedIds.incrementAndGet()}" }
        val plan = planner.createPlan(taskId, category, action, first, second)
        if (plan == null) {
            logger.record(AgentLogEvent(System.currentTimeMillis(), taskId, action.take(80), "rejected", "Tool is not registered"))
            return false
        }
        if (executor.hasRunningTask()) {
            logger.record(AgentLogEvent(System.currentTimeMillis(), taskId, action.take(80), "rejected", "Another native action is still running"))
            return false
        }
        return executor.begin(plan)
    }

    fun complete(taskId: String, ok: Boolean, message: String): Boolean =
        executor.complete(taskId, ok, message)

    internal fun recentEvents(): List<AgentLogEvent> = executor.recentEvents()
}
