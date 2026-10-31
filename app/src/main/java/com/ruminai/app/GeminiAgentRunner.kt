package com.ruminai.app

import android.content.Intent
import android.net.Uri
import android.os.CountDownLatch
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Gemini Interactions function-calling loop.
 *
 * Gemini proposes a registered tool call; Android validates and executes it;
 * the native result is returned to Gemini before the final user-facing answer.
 */
internal class GeminiAgentRunner(
    private val activity: MainActivity,
    private val agentCore: AgentCore
) {
    private val taskIds = AtomicLong(0)

    private val tools = JSONArray().apply {
        put(functionTool(
            "open_app",
            "Open one allow-listed Android app or its official web fallback when the user explicitly asks.",
            JSONObject()
                .put("type", "object")
                .put("properties", JSONObject().put(
                    "app",
                    JSONObject().put("type", "string")
                        .put("enum", JSONArray(listOf("youtube", "facebook", "whatsapp", "instagram", "gmail", "maps", "camera", "settings", "browser")))
                        .put("description", "The allow-listed app identifier")
                ))
                .put("required", JSONArray(listOf("app")))
        ))
        put(functionTool(
            "inspect_screen",
            "Read visible text from the currently active screen. Do not click, type, send, or change anything.",
            JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray())
        ))
    }

    fun run(apiKey: String, prompt: String): String {
        val input = JSONArray().put(
            JSONObject()
                .put("type", "user_input")
                .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", prompt)))
        )
        var response = post(apiKey, JSONObject()
            .put("model", "gemini-3.8-flash")
            .put("store", false)
            .put("input", input)
            .put("tools", tools))

        // Bound tool loops so a model response cannot keep the phone busy indefinitely.
        repeat(MAX_TOOL_ROUNDS) {
            val steps = response.optJSONArray("steps") ?: JSONArray()
            val calls = (0 until steps.length()).mapNotNull { steps.optJSONObject(it) }
            val call = calls.firstOrNull { it.optString("type") == "function_call" }
            if (call == null) return extractText(response)

            // Preserve model steps exactly as returned by the API for stateless continuation.
            for (step in calls) input.put(step)
            val name = call.optString("name")
            val callId = call.optString("id")
            val args = call.optJSONObject("arguments") ?: JSONObject()
            val result = executeRegisteredTool(name, args)

            input.put(
                JSONObject()
                    .put("type", "function_result")
                    .put("name", name)
                    .put("call_id", callId)
                    .put("result", JSONArray().put(JSONObject()
                        .put("type", "text")
                        .put("text", result.toString())))
            )
            response = post(apiKey, JSONObject()
                .put("model", "gemini-3.8-flash")
                .put("store", false)
                .put("input", input)
                .put("tools", tools))
        }
        val last = extractText(response)
        return last.ifBlank { "বস, কাজের জন্য একাধিক Tool ধাপ দরকার হয়েছে। নিরাপত্তার জন্য এই পর্যায়ে থেমেছি।" }
    }

    private fun executeRegisteredTool(name: String, args: JSONObject): JSONObject {
        val taskId = "gemini-tool-${taskIds.incrementAndGet()}"
        val action: String
        val category: String
        val first: String
        when (name) {
            "open_app" -> {
                action = "open_app"
                category = "command"
                first = args.optString("app").lowercase()
                if (first !in ALLOWED_APPS) {
                    return JSONObject().put("ok", false).put("message", "এই অ্যাপটি অনুমোদিত তালিকায় নেই।")
                }
            }
            "inspect_screen" -> {
                action = "inspect_screen"
                category = "accessibility"
                first = ""
            }
            else -> return JSONObject().put("ok", false).put("message", "অজানা Tool প্রত্যাখ্যান করা হয়েছে।")
        }

        if (!agentCore.begin(action, first, "", category, taskId)) {
            return JSONObject().put("ok", false).put("message", "Agent Tool Registry এই কাজটি অনুমোদন করেনি।")
        }

        val latch = CountDownLatch(1)
        var result = JSONObject().put("ok", false).put("message", "Native action সম্পন্ন হয়নি।")
        activity.runOnUiThread {
            try {
                result = when (action) {
                    "inspect_screen" -> RuminAccessibilityService.nativeAction("inspect_screen", "")
                    "open_app" -> openAllowedApp(first)
                    else -> JSONObject().put("ok", false).put("message", "Tool সমর্থিত নয়।")
                }
            } catch (e: Exception) {
                result = JSONObject().put("ok", false).put("message", e.message ?: "Native action ব্যর্থ হয়েছে।")
            } finally {
                agentCore.complete(taskId, result.optBoolean("ok"), result.optString("message"))
                latch.countDown()
            }
        }
        if (!latch.await(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            agentCore.complete(taskId, false, "Native tool timeout")
            return JSONObject().put("ok", false).put("message", "ফোনের Tool নির্দিষ্ট সময়ে ফলাফল দেয়নি।")
        }
        return result
    }

    private fun openAllowedApp(app: String): JSONObject {
        val intent = when (app) {
            "youtube" -> activity.packageManager.getLaunchIntentForPackage("com.google.android.youtube")
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
            "facebook" -> activity.packageManager.getLaunchIntentForPackage("com.facebook.katana")
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com"))
            "whatsapp" -> activity.packageManager.getLaunchIntentForPackage("com.whatsapp")
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/"))
            "instagram" -> activity.packageManager.getLaunchIntentForPackage("com.instagram.android")
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com"))
            "gmail" -> activity.packageManager.getLaunchIntentForPackage("com.google.android.gm")
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com"))
            "maps" -> activity.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps")
                ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps"))
            "camera" -> Intent("android.media.action.IMAGE_CAPTURE")
            "settings" -> Intent(android.provider.Settings.ACTION_SETTINGS)
            "browser" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
            else -> return JSONObject().put("ok", false).put("message", "অ্যাপটি অনুমোদিত নয়।")
        }
        activity.startActivity(intent)
        return JSONObject().put("ok", true).put("message", "$app খোলার অনুরোধ Android গ্রহণ করেছে।")
            .put("verification", "activity_launch_accepted")
    }

    private fun functionTool(name: String, description: String, parameters: JSONObject): JSONObject =
        JSONObject().put("type", "function").put("name", name)
            .put("description", description).put("parameters", parameters)

    private fun post(apiKey: String, body: JSONObject): JSONObject {
        val connection = (URL(INTERACTIONS_URL).openConnection() as HttpURLConnection)
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 45000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val parsed = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
            if (status !in 200..299) {
                val detail = parsed.optJSONObject("error")?.optString("message")
                    ?.takeIf { it.isNotBlank() } ?: "HTTP $status"
                throw IllegalStateException("Gemini API HTTP $status: $detail")
            }
            return parsed
        } finally {
            connection.disconnect()
        }
    }

    private fun extractText(response: JSONObject): String {
        response.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val parts = mutableListOf<String>()
        fun collect(array: JSONArray?) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.optString("type") == "text") item.optString("text").takeIf { it.isNotBlank() }?.let(parts::add)
                collect(item.optJSONArray("content"))
            }
        }
        collect(response.optJSONArray("output"))
        if (parts.isEmpty()) collect(response.optJSONArray("steps"))
        return parts.joinToString("\n")
    }

    companion object {
        private const val INTERACTIONS_URL = "https://generativelanguage.googleapis.com/v1beta/interactions"
        private const val MAX_TOOL_ROUNDS = 2
        private const val TOOL_TIMEOUT_SECONDS = 15L
        private val ALLOWED_APPS = setOf("youtube", "facebook", "whatsapp", "instagram", "gmail", "maps", "camera", "settings", "browser")
    }
}
