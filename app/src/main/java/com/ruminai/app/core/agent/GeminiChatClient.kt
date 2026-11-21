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
        .callTimeout(25, TimeUnit.SECONDS)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
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
            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                .header("x-goog-api-key", key)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(request).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) error("Gemini API error ${response.code}: ${raw.take(350)}")
                val answer = JSONObject(raw).getJSONArray("candidates").getJSONObject(0)
                    .getJSONObject("content").getJSONArray("parts").getJSONObject(0)
                    .getString("text").trim()
                require(answer.isNotBlank()) { "AI ফাঁকা উত্তর দিয়েছে। আবার চেষ্টা করুন।" }
                answer
            }
        }
    }
}
