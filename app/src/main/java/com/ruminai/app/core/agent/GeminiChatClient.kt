package com.ruminai.app.core.agent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One-turn Bengali/English text chat. Phone actions must go through AgentRuntime separately. */
class GeminiChatClient(
    private val apiKeyProvider: () -> String?,
    private val model: String = "gemini-3.8-flash"
) {
    private val client = OkHttpClient.Builder()
        .callTimeout(9, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun reply(message: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(message.isNotBlank()) { "আগে একটি মেসেজ লিখুন।" }
            val key = apiKeyProvider()?.takeIf { it.isNotBlank() }
                ?: error("আগে Gemini API Key সেট করুন।")
            val prompt = """
                You are Rumin, a helpful personal assistant. Reply naturally and concisely in Bengali
                unless the user writes in another language. Answer questions directly. Do not claim
                to control the phone or perform actions. If the user asks for a phone action, explain
                that they should use the Agent action button. Respect the user's request and context.
                User: $message
            """.trimIndent()
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject()
                    .put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                .put("generationConfig", JSONObject().put("temperature", 0.6))
            val candidateModels = listOf(model, "gemini-3.5-flash-lite").distinct()
            var lastError = ""
            for (selectedModel in candidateModels) {
                val request = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/$selectedModel:generateContent")
                    .header("x-goog-api-key", key)
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                val result = client.newCall(request).execute().use { response ->
                    val raw = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        lastError = "Gemini API error ${response.code}: ${raw.take(250)}"
                        if (response.code == 503 || response.code == 429 || response.code == 500) null
                        else error(lastError)
                    } else {
                        val root = JSONObject(raw)
                        val answer = root.optJSONArray("candidates")
                            ?.optJSONObject(0)?.optJSONObject("content")
                            ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text")
                            .orEmpty().trim()
                        require(answer.isNotBlank()) { "Gemini ফাঁকা উত্তর দিয়েছে।" }
                        answer
                    }
                }
                if (result != null) return@runCatching result
            }
            error("Gemini-এর মূল ও বিকল্প মডেল সাড়া দেয়নি। $lastError")
        }
    }
}
