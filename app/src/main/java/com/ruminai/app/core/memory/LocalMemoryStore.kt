package com.ruminai.app.core.memory

import android.content.Context
import com.ruminai.app.core.model.MemoryEntry
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Stores only facts explicitly approved by the user on this device. */
class LocalMemoryStore(context: Context) {
    private val prefs = context.getSharedPreferences("rumin_local_memory_v1", Context.MODE_PRIVATE)

    fun addApprovedFact(text: String): MemoryEntry {
        require(text.isNotBlank()) { "Memory text cannot be empty" }
        val entry = MemoryEntry(UUID.randomUUID().toString(), text.trim(), System.currentTimeMillis())
        val all = readAll().toMutableList()
        all.add(entry)
        val array = JSONArray()
        all.takeLast(200).forEach {
            array.put(JSONObject().put("id", it.id).put("text", it.text).put("createdAt", it.createdAtEpochMs))
        }
        prefs.edit().putString("approved_facts", array.toString()).apply()
        return entry
    }

    fun readAll(): List<MemoryEntry> {
        val raw = prefs.getString("approved_facts", "[]") ?: "[]"
        val array = JSONArray(raw)
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            MemoryEntry(item.optString("id"), item.optString("text"), item.optLong("createdAt"))
        }
    }

    fun clearAll() { prefs.edit().remove("approved_facts").apply() }
}
