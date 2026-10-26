package com.ruminai.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val aiExecutor = Executors.newSingleThreadExecutor()
    private lateinit var webView: WebView
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingNativeSpeech = false

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pendingPermissionRequest
        pendingPermissionRequest = null
        if (request != null) {
            if (granted) request.grant(request.resources) else request.deny()
        }
        if (pendingNativeSpeech) {
            pendingNativeSpeech = false
            if (granted) launchSpeech()
            else webView.evaluateJavascript("window.onNativeSpeechError && window.onNativeSpeechError('Microphone permission দিন')", null)
        }
    }

    private val speechLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
        val text = matches?.firstOrNull().orEmpty()
        val safe = org.json.JSONObject.quote(text)
        webView.evaluateJavascript("window.onNativeSpeechResult && window.onNativeSpeechResult($safe)", null)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        textToSpeech = TextToSpeech(this) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                val tts = textToSpeech
                val result = tts?.setLanguage(Locale("bn", "BD")) ?: TextToSpeech.LANG_NOT_SUPPORTED
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) tts?.setLanguage(Locale("bn", "IN"))
                tts?.setSpeechRate(0.96f)
                tts?.setPitch(1.12f)
                val femaleVoice = tts?.voices?.firstOrNull { voice -> voice.locale.language == "bn" && voice.name.contains("female", ignoreCase = true) }
                if (femaleVoice != null) tts?.voice = femaleVoice
            }
        }
        webView = WebView(this)
        setContentView(webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.addJavascriptInterface(NativeBridge(), "RuminNative")
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) &&
                        ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        pendingPermissionRequest = request
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    } else request.grant(request.resources)
                }
            }
        }
        webView.loadUrl("file:///android_asset/index.html")
    }

    private fun launchSpeech() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            webView.evaluateJavascript("window.onNativeSpeechError && window.onNativeSpeechError('এই ফোনে Speech Recognition সাপোর্ট নেই')", null)
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_PROMPT, "রুমিন AI-কে বাংলায় বলুন")
        }
        try {
            speechLauncher.launch(intent)
        } catch (_: Exception) {
            webView.evaluateJavascript("window.onNativeSpeechError && window.onNativeSpeechError('ভয়েস চালু করা যায়নি')", null)
        }
    }

    inner class NativeBridge {
        @JavascriptInterface fun speakText(text: String) {
            runOnUiThread {
                val tts = textToSpeech
                if (tts == null || !ttsReady) {
                    webView.evaluateJavascript("window.onNativeTtsError && window.onNativeTtsError('ফোনের বাংলা Text-to-Speech প্রস্তুত নয়। Settings থেকে Bengali voice data ইনস্টল করুন।')", null)
                    return@runOnUiThread
                }
                val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rumin_answer")
                if (result == TextToSpeech.ERROR) webView.evaluateJavascript("window.onNativeTtsError && window.onNativeTtsError('ভয়েস চালু করা যায়নি। ফোনের Text-to-Speech সেটিংস পরীক্ষা করুন।')", null)
            }
        }

        @JavascriptInterface fun startSpeechRecognition() {
            runOnUiThread {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingNativeSpeech = true
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                } else launchSpeech()
            }
        }
        @JavascriptInterface fun openUrl(url: String) {
            runOnUiThread {
                try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) {}
            }
        }

        @JavascriptInterface fun requestAi(provider: String, key: String, historyJson: String, currentText: String, requestId: String) {
            if (key.isBlank()) {
                deliverAiResult(requestId, false, "API Key ফাঁকা")
                return
            }
            aiExecutor.execute {
                var connection: HttpURLConnection? = null
                try {
                    val isGroq = provider == "groq"
                    val url = if (isGroq) "https://api.groq.com/openai/v1/chat/completions" else "https://generativelanguage.googleapis.com/v1beta/interactions"
                    val body = if (isGroq) {
                        val messages = JSONArray()
                        messages.put(JSONObject().put("role", "system").put("content", "তুমি রুমিন এআই, একজন সহায়ক ব্যক্তিগত AI সহকারী। স্বাভাবিক, পরিষ্কার ও সহানুভূতিশীল বাংলায় উত্তর দাও। আগের কথোপকথনের প্রেক্ষাপট বজায় রাখো।"))
                        val history = JSONArray(historyJson)
                        for (i in 0 until history.length()) {
                            val item = history.optJSONObject(i) ?: continue
                            val role = if (item.optString("role") == "assistant") "assistant" else "user"
                            messages.put(JSONObject().put("role", role).put("content", item.optString("content")))
                        }
                        messages.put(JSONObject().put("role", "user").put("content", currentText))
                        JSONObject().put("model", "openai/gpt-oss-20b").put("messages", messages).put("temperature", 0.7)
                    } else {
                        val history = JSONArray(historyJson)
                        val lines = mutableListOf<String>()
                        lines.add("নির্দেশনা: তুমি রুমিন এআই, একজন সহায়ক ব্যক্তিগত AI সহকারী। স্বাভাবিক ও পরিষ্কার বাংলায় উত্তর দাও। আগের কথোপকথনের প্রেক্ষাপট বজায় রাখো।")
                        for (i in 0 until history.length()) {
                            val item = history.optJSONObject(i) ?: continue
                            val label = if (item.optString("role") == "assistant") "রুমিন: " else "ব্যবহারকারী: "
                            lines.add(label + item.optString("content"))
                        }
                        lines.add("ব্যবহারকারী: " + currentText)
                        // Use the documented minimal Interactions API request shape for Gemini 3.8 Flash.
                        JSONObject()
                            .put("model", "gemini-3.8-flash")
                            .put("input", lines.joinToString("\n"))
                    }
                    connection = (URL(url).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 20000
                        readTimeout = 45000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        if (isGroq) setRequestProperty("Authorization", "Bearer $key")
                        else setRequestProperty("x-goog-api-key", key)
                    }
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    val response = try { JSONObject(responseText) } catch (_: Exception) { JSONObject() }
                    if (status !in 200..299) {
                        val errorObj = response.optJSONObject("error")
                        val message = errorObj?.optString("message")?.takeIf { it.isNotBlank() }
                            ?: response.optString("message").takeIf { it.isNotBlank() }
                            ?: "HTTP $status"
                        throw IllegalStateException(message)
                    }
                    val reply = if (isGroq) {
                        response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                    } else {
                        extractGeminiText(response)
                    }
                    if (reply.isBlank()) throw IllegalStateException("AI থেকে ফাঁকা উত্তর এসেছে")
                    deliverAiResult(requestId, true, reply)
                } catch (e: Exception) {
                    deliverAiResult(requestId, false, e.message ?: "AI সংযোগে অজানা সমস্যা")
                } finally {
                    connection?.disconnect()
                }
            }
        }

        private fun extractGeminiText(response: JSONObject): String {
            response.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
            val pieces = mutableListOf<String>()
            fun collect(array: JSONArray?) {
                if (array == null) return
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    if (item.optString("type") == "text") item.optString("text").takeIf { it.isNotBlank() }?.let { pieces.add(it) }
                    collect(item.optJSONArray("content"))
                }
            }
            collect(response.optJSONArray("output"))
            if (pieces.isEmpty()) collect(response.optJSONArray("steps"))
            return pieces.joinToString("\n")
        }

        private fun deliverAiResult(requestId: String, success: Boolean, message: String) {
            val idJson = JSONObject.quote(requestId)
            val messageJson = JSONObject.quote(message)
            runOnUiThread {
                if (::webView.isInitialized) {
                    webView.evaluateJavascript("window.onNativeAiResponse && window.onNativeAiResponse($idJson, $success, $messageJson)", null)
                }
            }
        }

        @JavascriptInterface fun performCommand(action: String, first: String, second: String) {
            runOnUiThread {
                try {
                    val intent = when (action) {
                        "alarm" -> Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                            putExtra(android.provider.AlarmClock.EXTRA_HOUR, first.toInt())
                            putExtra(android.provider.AlarmClock.EXTRA_MINUTES, second.toInt())
                            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                        }
                        "timer" -> Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                            putExtra(android.provider.AlarmClock.EXTRA_LENGTH, first.toInt())
                            putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, second.ifBlank { "RUMI AI Timer" })
                            putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, false)
                        }
                        "showalarms" -> Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS)
                        "showtimers" -> Intent(android.provider.AlarmClock.ACTION_SHOW_TIMERS)
                        "call" -> Intent(Intent.ACTION_DIAL, Uri.parse("tel:$first"))
                        "sms" -> Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$first")).apply {
                            putExtra("sms_body", second)
                        }
                        "dialer" -> Intent(Intent.ACTION_DIAL)
                        else -> null
                    }
                    if (intent == null) {
                        webView.evaluateJavascript("window.onNativeCommandResult && window.onNativeCommandResult(false, 'এই কমান্ডটি সমর্থিত নয়')", null)
                        return@runOnUiThread
                    }
                    startActivity(intent)
                    val message = when (action) {
                        "alarm" -> "অ্যালার্ম অ্যাপে সময়টি সেট করার জন্য পাঠানো হয়েছে।"
                        "timer" -> "টাইমার অ্যাপে টাইমারটি সেট করার জন্য পাঠানো হয়েছে।"
                        "call" -> "ফোন ডায়ালার খোলা হয়েছে; কল করার আগে নম্বরটি যাচাই করুন।"
                        "sms" -> "মেসেজ অ্যাপ খোলা হয়েছে; পাঠানোর আগে মেসেজটি যাচাই করুন।"
                        "showalarms" -> "অ্যালার্ম অ্যাপ খোলা হয়েছে।"
                        "showtimers" -> "টাইমার অ্যাপ খোলা হয়েছে।"
                        else -> "ফোন ডায়ালার খোলা হয়েছে।"
                    }
                    webView.evaluateJavascript("window.onNativeCommandResult && window.onNativeCommandResult(true, ${org.json.JSONObject.quote(message)})", null)
                } catch (e: Exception) {
                    val message = org.json.JSONObject.quote("কমান্ড চালানো যায়নি: " + (e.message ?: "অজানা সমস্যা"))
                    webView.evaluateJavascript("window.onNativeCommandResult && window.onNativeCommandResult(false, $message)", null)
                }
            }
        }
    }

    override fun onDestroy() {
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        aiExecutor.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
