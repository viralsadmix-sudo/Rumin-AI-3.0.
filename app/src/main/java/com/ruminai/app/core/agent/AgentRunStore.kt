package com.ruminai.app.core.agent

import android.content.Context
import com.ruminai.app.core.model.ExecutionStatus
import org.json.JSONArray
import org.json.JSONObject

/** Local bounded audit trail. Stores task text and tool results only on this device. */
class AgentRunStore(context: Context) {
    private val prefs = context.getSharedPreferences("rumin_agent_runs_v1", Context.MODE_PRIVATE)

    @Synchronized
    fun save(run: AgentRun) {
        val all = readRaw()
        val item = JSONObject()
            .put("id", run.id)
            .put("request", run.plan.userRequest)
            .put("status", run.status.name)
            .put("summary", run.summary.orEmpty())
            .put("updatedAt", System.currentTimeMillis())
        val steps = JSONArray()
        run.steps.forEach { execution ->
            steps.put(JSONObject()
                .put("id", execution.step.id)
                .put("description", execution.step.description)
                .put("tool", execution.step.toolName)
                .put("status", execution.status.name)
                .put("output", execution.output.orEmpty())
                .put("error", execution.error.orEmpty()))
        }
        item.put("steps", steps)
        val retained = (0 until all.length()).mapNotNull { all.optJSONObject(it) }
            .filterNot { it.optString("id") == run.id }
            .takeLast(49)
        val next = JSONArray().apply {
            retained.forEach { put(it) }
            put(item)
        }
        prefs.edit().putString("runs", next.toString()).apply()
    }

    fun latestSummary(): String? {
        val all = readRaw()
        if (all.length() == 0) return null
        val latest = all.optJSONObject(all.length() - 1) ?: return null
        return "${latest.optString("request")} — ${latest.optString("status")}"
    }

    fun clear() { prefs.edit().remove("runs").apply() }

    private fun readRaw(): JSONArray = runCatching {
        JSONArray(prefs.getString("runs", "[]") ?: "[]")
    }.getOrDefault(JSONArray())
}
