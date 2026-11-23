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

/** Checks the saved key without ever displaying or logging the credential. */
class GeminiConnectionDiagnostics(private val apiKey: String) {
    private val client = OkHttpClient.Builder()
        .callTimeout(15, TimeUnit.SECONDS)
        .connectTimeout(7, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    suspend fun run(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(apiKey.isNotBlank()) { "Gemini API Key ফাঁকা।" }
            val modelsRequest = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models")
                .header("x-goog-api-key", apiKey)
                .get()
                .build()
            val modelNames = client.newCall(modelsRequest).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching { JSONObject(raw).getJSONObject("error").optString("message") }
                        .getOrDefault("Google API থেকে ত্রুটি এসেছে।")
                    when (response.code) {
                        400, 401, 403 -> error("API Key যাচাই ব্যর্থ (${response.code}): $detail\nGoogle AI Studio-তে Key active এবং API restriction পরীক্ষা করুন।")
                        429 -> error("API Key গ্রহণ হয়েছে, কিন্তু quota/rate limit অতিক্রম হয়েছে (429): $detail")
                        else -> error("মডেল তালিকা পরীক্ষা ব্যর্থ (${response.code}): $detail")
                    }
                }
                val models = JSONObject(raw).optJSONArray("models") ?: JSONArray()
                (0 until models.length()).mapNotNull { i ->
                    models.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }
                }
            }
            val modelName = "models/gemini-3.8-flash"
            if (modelName !in modelNames) error("API Key গ্রহণ হয়েছে, কিন্তু $modelName আপনার Key-তে উপলভ্য নয়।")
            val body = JSONObject()
                .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "Reply with exactly: RUMIN_API_OK")))))
                .put("generationConfig", JSONObject().put("maxOutputTokens", 16))
            val testRequest = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-3.8-flash:generateContent")
                .header("x-goog-api-key", apiKey)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            client.newCall(testRequest).execute().use { response ->
                val raw = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    val detail = runCatching { JSONObject(raw).getJSONObject("error").optString("message") }
                        .getOrDefault("Google API থেকে ত্রুটি এসেছে।")
                    when (response.code) {
                        400, 401, 403 -> error("Key তালিকা পড়তে পেরেছে, কিন্তু Gemini Flash অনুরোধ প্রত্যাখ্যান করেছে (${response.code}): $detail")
                        429 -> error("Key সঠিক এবং মডেল পাওয়া গেছে; কিন্তু quota/rate limit সমস্যা (429): $detail")
                        503 -> error("Key সঠিক; Gemini মডেল এখন সাময়িকভাবে ব্যস্ত (503)। পরে চেষ্টা করুন।")
                        else -> error("Gemini পরীক্ষা ব্যর্থ (${response.code}): $detail")
                    }
                }
                val answer = JSONObject(raw).optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts")?.optJSONObject(0)?.optString("text").orEmpty()
                if (answer.isBlank()) error("API অনুরোধ সফল, কিন্তু মডেল ফাঁকা উত্তর দিয়েছে।")
            }
            "Gemini API পরীক্ষা সফল।\n১) সংরক্ষিত API Key গ্রহণ হয়েছে।\n২) Gemini 3.8 Flash মডেল পাওয়া গেছে।\n৩) GenerateContent পরীক্ষায় উত্তর এসেছে।"
        }
    }
}