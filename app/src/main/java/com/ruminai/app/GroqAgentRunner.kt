package com.ruminai.app

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject

/** Groq fallback adapter. Tool calls are executed by the same allow-listed Native Executor as Gemini. */
internal class GroqAgentRunner(
    private val nativeRunner: GeminiAgentRunner
) {
    fun run(apiKey: String, prompt: String): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", """
            তুমি RUMIN AI 3.0-এর ব্যাকআপ Agent brain। ব্যবহারকারীকে 'বস' বলবে এবং স্বাভাবিক বাংলাদেশি বাংলায় উত্তর দেবে।
            ব্যবহারকারীর বাক্যের intent বুঝবে, শুধু keyword মিলাবে না।
            ব্যবহারকারী স্পষ্টভাবে মনে রাখতে বললে remember_fact tool ব্যবহার করবে; ব্যবহারকারী কী মনে আছে জানতে চাইলে বা সংরক্ষিত পছন্দ ব্যবহার করতে বললে recall_saved_facts tool ব্যবহার করবে। অন্য অনুরোধে অকারণে মেমোরি পড়বে না; অনুমতি ছাড়া স্থায়ী তথ্য সংরক্ষণ করবে না। মেমোরি বন্ধ বা unavailable হলে সেটি জানাবে। উদাহরণ: "ফেসবুক খোলো/ফেসবুকে নিয়ে চলো/ফেসবুক চালু করো" = open_app(Facebook); "আগের পেজে যাও/একটু পেছনে যাও" = phone_navigation(back); "হোমে ফেরত যাও/মূল স্ক্রিনে যাও" = phone_navigation(home); "শব্দ বাড়াও/ভলিউম বাড়াও" = adjust_volume(up); "স্ক্রিনে কী আছে/এখানে কী দেখাচ্ছে/এই পেজটা পড়ে বলো" = inspect_screen।
            অ্যাপের নাম, দৃশ্যমান বাটনের লেখা, সময়, নম্বর বা বার্তার মতো দরকারি তথ্য অনুপস্থিত বা দ্ব্যর্থক হলে একটি নির্দিষ্ট প্রশ্ন করবে; কিছু বানিয়ে নেবে না।
            ঘোষিত Function Tools ছাড়া ফোনে কোনো কাজ করার দাবি করবে না। Native layer প্রতিটি Tool যাচাই করে চালায়।
            Tool result-এ যতটুকু প্রমাণ আছে শুধু ততটুকুই বলবে। অ্যাপ খোলার অনুরোধ গ্রহণ মানে অ্যাপের ভেতরের কাজ সম্পন্ন নয়।
            Alarm/Timer UI খোলা মানে সংরক্ষণ/চালু হওয়া নয়; Dialer খোলা মানে কল নয়; SMS draft মানে পাঠানো নয়।
            Settings page খোলা মানে permission granted নয়। outcome_unknown পেলে একই কাজ আবার চালাবে না।
            generic বা অনির্দিষ্ট tap/type করবে না। screen_interact কেবল বস স্পষ্টভাবে নির্দিষ্ট দৃশ্যমান লেখা চাপতে, ফোকাস করা ইনপুটে নির্দিষ্ট লেখা বসাতে, scroll/swipe করতে বললে ব্যবহার করবে। Groq-কে কোনো screen image দেওয়া না থাকলে tap_at coordinate অনুমান করবে না; আগে inspect_screen-এর দৃশ্যমান লেখা দেখে exact click_text ব্যবহার করবে। প্রতিটি action-এর ফল দেখে পরের ধাপ নেবে। SMS করতে বললে প্রাপক ও বার্তার লেখা না থাকলে আগে জিজ্ঞেস করবে; দুটো জানা থাকলে SMS draft খুলবে, কিন্তু Send চাপবে না। payment, purchase, delete, send বা permission-grant control কখনো চাপবে না। arbitrary shell/code/URL, payment, deletion, permission grant, call বা message send করার চেষ্টা করবে না।
            অস্পষ্ট নির্দেশ হলে প্রশ্ন করবে। কাজ না হলে সীমাবদ্ধতা পরিষ্কার বলবে; অনুমানকে তথ্য হিসেবে বলবে না।
        """.trimIndent()))
        messages.put(JSONObject().put("role", "user").put("content", prompt))

        val tools = nativeRunner.openAiToolDefinitions()
        var response = post(apiKey, requestBody(messages, tools))
        repeat(MAX_TOOL_ROUNDS) {
            val choice = response.optJSONArray("choices")?.optJSONObject(0)
                ?: throw IllegalStateException("Groq response-এ choices পাওয়া যায়নি")
            val message = choice.optJSONObject("message")
                ?: throw IllegalStateException("Groq response-এ message পাওয়া যায়নি")
            val calls = message.optJSONArray("tool_calls")
            if (calls == null || calls.length() == 0) {
                return message.optString("content").takeIf { it.isNotBlank() }
                    ?: throw IllegalStateException("Groq থেকে ফাঁকা উত্তর এসেছে")
            }

            // GPT-OSS 20B is configured for sequential local tool use; never execute parallel actions.
            messages.put(JSONObject()
                .put("role", "assistant")
                .put("content", message.opt("content") ?: JSONObject.NULL)
                .put("tool_calls", calls))

            for (i in 0 until calls.length()) {
                val call = calls.optJSONObject(i) ?: continue
                val callId = call.optString("id")
                val function = call.optJSONObject("function") ?: JSONObject()
                val name = function.optString("name")
                val args = parseArguments(function.optString("arguments", "{}"))
                val result = when {
                    callId.isBlank() -> JSONObject().put("ok", false).put("message", "Tool call ID নেই; চালানো হয়নি।").put("verification", "invalid_call")
                    i > 0 -> JSONObject().put("ok", false).put("message", "একবারে একটির বেশি Native action চালানো হয় না; অতিরিক্ত Tool প্রত্যাখ্যান করা হয়েছে।").put("verification", "tool_limit")
                    else -> nativeRunner.executeTool(name, args)
                }
                if (callId.isNotBlank()) {
                    messages.put(JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", callId)
                        .put("content", result.toString()))
                }
            }
            response = try {
                post(apiKey, requestBody(messages, tools))
            } catch (e: Exception) {
                if (nativeRunner.hasNativeActionAttempted()) {
                    return "বস, Native action চালানোর চেষ্টা হয়েছে, কিন্তু Groq-এর চূড়ান্ত উত্তর পাওয়া যায়নি। একই কাজ আবার চালাইনি—ফোনে কাজটির ফলাফল পরীক্ষা করুন।"
                }
                throw e
            }
        }
        return "বস, নিরাপত্তার জন্য Agent Tool ধাপের সীমায় থেমেছি। শেষ ফলাফল সম্পূর্ণ যাচাই করা যায়নি।"
    }

    private fun requestBody(messages: JSONArray, tools: JSONArray) = JSONObject()
        .put("model", MODEL)
        .put("messages", messages)
        .put("tools", tools)
        .put("tool_choice", "auto")
        .put("parallel_tool_calls", false)
        .put("temperature", 0.4)

    private fun post(apiKey: String, body: JSONObject): JSONObject {
        val connection = URL(API_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = 20000
            connection.readTimeout = 45000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val parsed = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
            if (status !in 200..299) {
                val detail = parsed.optJSONObject("error")?.optString("message")
                    ?.takeIf { it.isNotBlank() } ?: "HTTP $status"
                throw IllegalStateException("Groq API HTTP $status: $detail")
            }
            return parsed
        } finally {
            connection.disconnect()
        }
    }

    private fun parseArguments(raw: String): JSONObject =
        try { JSONObject(raw) } catch (_: Exception) { JSONObject() }

    companion object {
        private const val MODEL = "openai/gpt-oss-20b"
        private const val API_URL = "https://api.groq.com/openai/v1/chat/completions"
        private const val MAX_TOOL_ROUNDS = 4
    }
}
