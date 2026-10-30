package com.ruminai.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.RecognitionListener
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
    private val agentCore = AgentCore()
    private lateinit var webView: WebView
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingNativeSpeech = false
    private var continuousRecognizer: SpeechRecognizer? = null
    private var continuousListening = false
    private var pendingLiveKey: String? = null
    @Volatile private var liveVoiceSession: GeminiLiveVoice? = null

    private val phonePermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        val granted = results.filterValues { it }.keys
        val denied = results.filterValues { !it }.keys
        val message = buildString {
            append("অনুমতি পরীক্ষা সম্পন্ন। অনুমোদিত: ")
            append(if (granted.isEmpty()) "কোনোটিই নয়" else granted.joinToString(", "))
            if (denied.isNotEmpty()) {
                append(". অনুমতি মেলেনি: ")
                append(denied.joinToString(", "))
            }
            append(". Accessibility ও Notification Access আলাদা Settings থেকে চালু করতে হবে।")
        }
        if (::webView.isInitialized) webView.evaluateJavascript("window.onNativeCommandResult && window.onNativeCommandResult(${denied.isEmpty()}, ${JSONObject.quote(message)})", null)
    }

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pendingPermissionRequest
        pendingPermissionRequest = null
        if (request != null) {
            if (granted) request.grant(request.resources) else request.deny()
        }
        if (pendingNativeSpeech) {
            pendingNativeSpeech = false
            if (granted) startContinuousListening()
            else webView.evaluateJavascript("window.onNativeSpeechError && window.onNativeSpeechError('Microphone permission দিন')", null)
        }
        val liveKey = pendingLiveKey
        pendingLiveKey = null
        if (liveKey != null) {
            if (granted) startLiveVoiceInternal(liveKey)
            else reportLiveVoice("মাইক্রোফোনের অনুমতি দিন", "stopped")
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
                tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { runOnUiThread { if (::webView.isInitialized) webView.evaluateJavascript("window.onNativeTtsDone && window.onNativeTtsDone()", null) } }
                    @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { runOnUiThread { if (::webView.isInitialized) webView.evaluateJavascript("window.onNativeTtsError && window.onNativeTtsError(\u0027ফোনের বাংলা কণ্ঠে উত্তর দিতে সমস্যা হয়েছে\u0027)", null) } }
                })
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

    private fun startContinuousListening() {
        if (!continuousListening) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            webView.evaluateJavascript("window.onNativeSpeechError && window.onNativeSpeechError('এই ফোনে Speech Recognition সাপোর্ট নেই')", null)
            return
        }
        continuousRecognizer?.destroy()
        val recognizer = SpeechRecognizer.createSpeechRecognizer(this)
        continuousRecognizer = recognizer
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                if (continuousListening) runOnUiThread {
                    webView.evaluateJavascript("window.onNativeSpeechError && window.onNativeSpeechError('শোনা বন্ধ হয়েছে, আবার শুনছি…')", null)
                }
            }
            override fun onResults(results: Bundle?) {
                val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                runOnUiThread {
                    webView.evaluateJavascript("window.onNativeSpeechResult && window.onNativeSpeechResult(${JSONObject.quote(text)})", null)
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        try { recognizer.startListening(intent) } catch (_: Exception) {
            webView.evaluateJavascript("window.onNativeSpeechError && window.onNativeSpeechError('ভয়েস চালু করা যায়নি')", null)
        }
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
        @JavascriptInterface fun startLiveVoice(key: String) {
            runOnUiThread {
                if (key.isBlank()) {
                    reportLiveVoice("Settings থেকে Gemini API Key সেভ করুন", "stopped")
                    return@runOnUiThread
                }
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingLiveKey = key
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                } else startLiveVoiceInternal(key)
            }
        }

        @JavascriptInterface fun stopLiveVoice() {
            runOnUiThread {
                liveVoiceSession?.stop()
                liveVoiceSession = null
                reportLiveVoice("লাইভ ভয়েস বন্ধ করা হয়েছে", "stopped")
            }
        }

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

        @JavascriptInterface fun stopSpeaking() { runOnUiThread { textToSpeech?.stop() } }

        @JavascriptInterface fun stopSpeechRecognition() { runOnUiThread { continuousListening = false; continuousRecognizer?.cancel(); continuousRecognizer?.destroy(); continuousRecognizer = null; textToSpeech?.stop() } }

        @JavascriptInterface fun startSpeechRecognition() {
            runOnUiThread {
                continuousListening = true
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    pendingNativeSpeech = true
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                } else startContinuousListening()
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
                        messages.put(JSONObject().put("role", "system").put("content", "তুমি RUMIN AI (রুমিন), তোমার নির্মাতা সাব্বির আহমেদ রুবেল। কেউ ‘তোমাকে কে বানিয়েছে?’ জিজ্ঞেস করলে সত্যভাবে বলবে: ‘আমাকে তৈরি করেছেন সাব্বির আহমেদ রুবেল। আমি রুমিন AI; আমার AI উত্তর দেওয়ার জন্য Google Gemini ব্যবহার করা হতে পারে।’ Google-কে তোমার নির্মাতা বলবে না। তুমি বসের ব্যক্তিগত বাংলা AI সহকারী। তোমার মূল উদ্দেশ্য শুধু চ্যাট করা নয়: তুমি RUMIN AI 3.0 অ্যাপের ভেতরে থাকা ব্যক্তিগত ফোন-সহকারী; বসের কথা শুনবে, উদ্দেশ্য বুঝবে, এবং এই অ্যাপে বাস্তবে সংযুক্ত ও অনুমোদিত ফোন-কমান্ড থাকলে সেটি চালানোর চেষ্টা করবে। AI মস্তিষ্ক অনুরোধ বুঝতে সাহায্য করে, কিন্তু নিজে থেকে Android ফোনে হাত দিতে পারে না—কাজ চালায় অ্যাপের Native Android command bridge। তাই শুধু কথা বলে কাজ হয়েছে এমন ভান করবে না। আগে সহজ, স্পষ্ট নির্দেশ বুঝবে; পরিচিত কমান্ড হলে তা কমান্ড-ব্যবস্থায় পাঠাতে হবে, আর কমান্ড সংযুক্ত না থাকলে স্পষ্ট বলবে যে ওই কাজের Native action এখনো যোগ করা নেই। কমান্ডের ফলাফল না পাওয়া পর্যন্ত সফল বলবে না। ব্যবহারকারীকে স্বাভাবিকভাবে ‘বস’ বলবে, তবে তার অনুভূতি, হতাশা, আনন্দ, উদ্বেগ বা তাড়াহুড়ো বুঝে সহানুভূতিশীলভাবে উত্তর দেবে; অনুভূতি সম্পর্কে নিশ্চিত না হলে অনুমান না করে নরমভাবে জিজ্ঞেস করবে। কথার ভাষা স্বাভাবিক বাংলাদেশি বাংলা, কণ্ঠে বলার উপযোগী ছোট বাক্য, উষ্ণ ও সম্মানজনক ভঙ্গি। ব্যবহারকারী কাজ চাইলে প্রথমে সংক্ষেপে স্বীকার করবে—যেমন ‘ঠিক আছে বস, আমি চেষ্টা করছি’—তারপর বাস্তবসম্মত পরবর্তী পদক্ষেপ নেবে। এই অ্যাপে বাস্তবে উপলভ্য Native command ব্যবহার করে সমর্থিত ফোনের কাজ করো; ফলাফল callback থেকে না আসা পর্যন্ত কাজ সফল হয়েছে বলবে না। অনুমতি না থাকলে কোন অনুমতি দরকার তা নির্দিষ্ট করে বলো এবং অ্যাপের Settings-এর বোতাম/পথ দেখাও; ব্যবহারকারী নিজে Android-এর অনুমতি দেবে। কোনো কাজ এই অ্যাপে সম্ভব না হলে শুধু ‘পারব না’ বলে থেমো না: কেন আটকে আছে, কোন সংযোগ/টুল দরকার, কীভাবে চালু করবে এবং এখন বিকল্প কী—স্পষ্ট করে বলো। GitHub সংযোগ/ফাইল-সম্পাদনার টুল এই অ্যাপে না থাকলে GitHub-এ কাজ করেছি বলে ভান করবে না; নিরাপদ সংযোগের প্রয়োজন ব্যাখ্যা করবে। কাজ সম্পন্ন না হলে সম্পন্ন বলবে না। ব্যবহারকারী স্পষ্টভাবে না বললে নিজে থেকে কোনো ফোন/অ্যাপের কাজ শুরু করবে না, সেটিংস বদলাবে না, বার্তা পাঠাবে না বা সিদ্ধান্ত চাপিয়ে দেবে না। ব্যবহারকারীর নির্দেশের অর্থ অস্পষ্ট হলে আগে ছোট করে প্রশ্ন করবে; নিরাপদ ও স্পষ্ট নির্দেশ পেলে শুধু সেই কাজটাই করবে। ঝুঁকিপূর্ণ, ব্যক্তিগত ডেটা প্রকাশ, মুছে ফেলা, টাকা বা বাহ্যিক বার্তা পাঠানোর আগে নিশ্চিত অনুমতি নাও। ব্যবহারকারীর নাম বানিয়ে বলবে না।"))
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
                        lines.add("নির্দেশনা: তুমি রুমিন এআই, বসের ব্যক্তিগত বাংলা AI সহকারী। ব্যবহারকারীর অনুভূতি, উদ্দেশ্য ও কথার প্রেক্ষাপট মনোযোগ দিয়ে বোঝো; তিনি কষ্টে/হতাশ হলে সহানুভূতি দেখাও, কিন্তু তার অনুভূতি সম্পর্কে নিশ্চিত না হলে অনুমান করো না। স্বাভাবিক বাংলাদেশি বাংলায় উষ্ণ, সম্মানজনক, কণ্ঠে বলার উপযোগী বাক্যে উত্তর দাও। ব্যবহারকারী কোনো কাজ বললে প্রথমে ‘ঠিক আছে বস, আমি চেষ্টা করছি’ ধরনের সংক্ষিপ্ত আশ্বাস দিয়ে বাস্তব কাজের চেষ্টা করো—শুধু সাধারণ পরামর্শ দিয়ে থেমো না। এই অ্যাপে উপলভ্য Native command ও অনুমোদিত ক্ষমতাই ব্যবহার করতে পারো; ফলাফল না পাওয়া পর্যন্ত সফলতার দাবি করবে না। কোনো অনুমতি দরকার হলে নির্দিষ্ট করে বোঝাও এবং Settings-এর বোতাম/পথ দেখাও; অনুমতি ব্যবহারকারী নিজে দেবেন। কোনো সংযোগ/টুল অনুপস্থিত হলে স্পষ্টভাবে বলো কী অনুপস্থিত, কীভাবে সংযোগ করতে হবে এবং আপাতত বিকল্প কী। GitHub ফাইল পড়া/সম্পাদনা বা বিল্ড চালানোর টুল না থাকলে এমন কাজ করেছি বলে ভান করবে না। ব্যবহারকারী স্পষ্টভাবে না বললে নিজে থেকে কোনো ফোন/অ্যাপের কাজ শুরু করবে না, সেটিংস বদলাবে না, বার্তা পাঠাবে না বা সিদ্ধান্ত চাপিয়ে দেবে না। নির্দেশ অস্পষ্ট হলে আগে প্রশ্ন করবে; স্পষ্ট ও নিরাপদ নির্দেশ পেলে শুধু সেই কাজটাই করবে। ঝুঁকিপূর্ণ, ব্যক্তিগত ডেটা প্রকাশ, মুছে ফেলা, টাকা বা বাইরের বার্তা পাঠানোর আগে স্পষ্ট অনুমতি নাও। কাজ শেষ না হলে শেষ হয়েছে বলবে না। আগের কথোপকথনের প্রেক্ষাপট বজায় রাখো, সংক্ষিপ্ত ও পরিষ্কার বাংলায় উত্তর দাও।")
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
                        throw IllegalStateException("HTTP $status — $message")
                    }
                    val reply = if (isGroq) {
                        response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                    } else {
                        extractGeminiText(response)
                    }
                    if (reply.isBlank()) throw IllegalStateException("AI থেকে ফাঁকা উত্তর এসেছে")
                    deliverAiResult(requestId, true, reply)
                } catch (e: Exception) {
                    val detail = when (e) {
                        is java.net.UnknownHostException -> "ইন্টারনেট/DNS সংযোগ নেই (UnknownHostException): ${e.message ?: "হোস্ট খুঁজে পাওয়া যায়নি"}"
                        is java.net.SocketTimeoutException -> "API উত্তর দিতে সময় নিয়েছে; নেটওয়ার্ক বা সার্ভার ধীর (Timeout): ${e.message ?: "সময় শেষ"}"
                        is javax.net.ssl.SSLException -> "নিরাপদ HTTPS সংযোগ ব্যর্থ (SSL): ${e.message ?: "SSL ত্রুটি"}"
                        is java.net.SocketException -> "নেটওয়ার্ক সংযোগ মাঝপথে বন্ধ হয়েছে (SocketException): ${e.message ?: "সংযোগ বন্ধ"}"
                        else -> "${e.javaClass.simpleName}: ${e.message ?: "অজানা সমস্যা"}"
                    }
                    deliverAiResult(requestId, false, detail)
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

        @JavascriptInterface fun executeAgentTool(action: String, first: String, second: String, taskId: String) {
            val accessibility = action.startsWith("accessibility:")
            val actualAction = if (accessibility) action.removePrefix("accessibility:") else action
            val category = if (accessibility) "accessibility" else "command"
            if (!accessibility && actualAction == "requestpermissions") {
                performCommand(actualAction, first, second, "")
                return
            }
            if (!agentCore.begin(actualAction, first, second, category, taskId)) {
                runOnUiThread { deliverCommandResult(false, "এই Tool Registry-তে কমান্ডটি নেই: $actualAction", taskId) }
                return
            }
            runOnUiThread {
                if (accessibility) {
                    val result = RuminAccessibilityService.nativeAction(actualAction, first)
                    val raw = result.toString()
                    val verified = AgentVerifier.verifyNativeJson(raw)
                    agentCore.complete(taskId, verified, result.optString("message"))
                    webView.evaluateJavascript(
                        "window.onNativeActionResult && window.onNativeActionResult(${JSONObject.quote(raw)}, ${JSONObject.quote(taskId)})",
                        null
                    )
                } else {
                    performCommand(actualAction, first, second, taskId)
                }
            }
        }

        private fun deliverCommandResult(ok: Boolean, message: String, taskId: String) {
            if (taskId.isNotBlank()) agentCore.complete(taskId, ok, message)
            webView.evaluateJavascript(
                "window.onNativeCommandResult && window.onNativeCommandResult($ok, ${JSONObject.quote(message)}, ${JSONObject.quote(taskId)})",
                null
            )
        }

        @JavascriptInterface fun performNativeAction(action: String, value: String) {
            runOnUiThread {
                val result = RuminAccessibilityService.nativeAction(action, value)
                val safe = JSONObject.quote(result.toString())
                webView.evaluateJavascript("window.onNativeActionResult && window.onNativeActionResult($safe)", null)
            }
        }

        @JavascriptInterface fun performCommand(action: String, first: String, second: String, taskId: String = "") {
            runOnUiThread {
                try {
                    if (action == "requestpermissions") {
                        // Request only runtime permissions supported by this Android version.
                        // Microphone was missing from the original bulk permission flow.
                        val needed = mutableListOf(
                            Manifest.permission.RECORD_AUDIO,
                            Manifest.permission.READ_CONTACTS,
                            Manifest.permission.WRITE_CONTACTS,
                            Manifest.permission.CALL_PHONE,
                            Manifest.permission.READ_SMS,
                            Manifest.permission.SEND_SMS,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        )
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                            needed.add(Manifest.permission.BLUETOOTH_CONNECT)
                            needed.add(Manifest.permission.BLUETOOTH_SCAN)
                        }
                        if (android.os.Build.VERSION.SDK_INT >= 33) {
                            needed.add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        phonePermissions.launch(needed.distinct().toTypedArray())
                        return@runOnUiThread
                    }
                    if (action == "home" || action == "back" || action == "recents" || action == "notifications" || action == "quicksettings" || action == "power") {
                        val ok = RuminAccessibilityService.perform(action)
                        val message = if (ok) "ফোনের কমান্ড চালানো হয়েছে।" else "আগে Accessibility সেটিংসে RUMIN AI ফোন নিয়ন্ত্রণ চালু করুন।"
                        deliverCommandResult($ok, message, taskId)
                        return@runOnUiThread
                    }
                    if (action == "writesettings") {
                        val settingsIntent = Intent(
                            android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS,
                            Uri.parse("package:$packageName")
                        )
                        try {
                            if (settingsIntent.resolveActivity(packageManager) != null) {
                                startActivity(settingsIntent)
                            } else {
                                startActivity(Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:$packageName")
                                ))
                            }
                            val granted = android.provider.Settings.System.canWrite(this@MainActivity)
                            val message = if (granted) {
                                "RUMIN AI-কে System Settings পরিবর্তনের অনুমতি আগে থেকেই দেওয়া আছে।"
                            } else {
                                "System Settings অনুমতি পেজ খোলার চেষ্টা করেছি। Pixel Experience-এ পেজ না এলে App Info-তে Special app access/Modify system settings খুঁজুন। অপশন না থাকলে ROM সেটিংস থেকে অনুমতি দিতে হতে পারে।"
                            }
                            deliverCommandResult(true, message, taskId)
                        } catch (e: Exception) {
                            val message = "System Settings অনুমতি পেজ খোলা যায়নি। App Info খুলে Permissions বা Special app access পরীক্ষা করুন।"
                            try {
                                startActivity(Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:$packageName")
                                ))
                            } catch (_: Exception) { }
                            webView.evaluateJavascript("window.onNativeCommandResult && window.onNativeCommandResult(false, ${org.json.JSONObject.quote(message)})", null)
                        }
                        return@runOnUiThread
                    }
                    if (action == "volumeup" || action == "volumedown" || action == "volumemute" || action == "volumeunmute") {
                        val audio = getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                        when (action) {
                            "volumeup" -> audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
                            "volumedown" -> audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
                            "volumemute" -> audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_MUTE, android.media.AudioManager.FLAG_SHOW_UI)
                            "volumeunmute" -> audio.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_UNMUTE, android.media.AudioManager.FLAG_SHOW_UI)
                        }
                        val message = when (action) { "volumeup" -> "মিডিয়া ভলিউম বাড়ানো হয়েছে।"; "volumedown" -> "মিডিয়া ভলিউম কমানো হয়েছে।"; "volumemute" -> "মিডিয়া ভলিউম মিউট করা হয়েছে।"; else -> "মিডিয়া ভলিউম চালু করা হয়েছে।" }
                        deliverCommandResult(true, message, taskId)
                        return@runOnUiThread
                    }
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
                        "requestpermissions" -> null
                        "accessibility" -> Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        "writesettings" -> Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName"))
                        "notificationaccess" -> Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                        "apppermissions" -> Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
                        "ttssettings" -> Intent("com.android.settings.TTS_SETTINGS")
                        "settings" -> Intent(android.provider.Settings.ACTION_SETTINGS)
                        "wifi" -> Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                        "bluetooth" -> Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS)
                        "display" -> Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)
                        "sound" -> Intent(android.provider.Settings.ACTION_SOUND_SETTINGS)
                        "camera" -> Intent("android.media.action.IMAGE_CAPTURE")
                        "contacts" -> Intent(Intent.ACTION_VIEW, Uri.parse("content://contacts/people/"))
                        "browser" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
                        "youtube" -> packageManager.getLaunchIntentForPackage("com.google.android.youtube") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
                        "facebook" -> packageManager.getLaunchIntentForPackage("com.facebook.katana") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com"))
                        "whatsapp" -> packageManager.getLaunchIntentForPackage("com.whatsapp") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/"))
                        "instagram" -> packageManager.getLaunchIntentForPackage("com.instagram.android") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com"))
                        "gmail" -> packageManager.getLaunchIntentForPackage("com.google.android.gm") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com"))
                        "playstore" -> packageManager.getLaunchIntentForPackage("com.android.vending") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store"))
                        "maps" -> packageManager.getLaunchIntentForPackage("com.google.android.apps.maps") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps"))
                        else -> null
                    }
                    if (intent == null) {
                        deliverCommandResult(false, "এই কমান্ডটি সমর্থিত নয়", taskId)
                        return@runOnUiThread
                    }
                    try { startActivity(intent) } catch (first: Exception) { when (action) { "youtube" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))); "whatsapp" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/"))); "instagram" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com"))); "gmail" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com"))); "playstore" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store"))); "maps" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps"))); else -> throw first } }
                    val message = when (action) {
                        "alarm" -> "অ্যালার্ম অ্যাপে সময়টি সেট করার জন্য পাঠানো হয়েছে।"
                        "timer" -> "টাইমার অ্যাপে টাইমারটি সেট করার জন্য পাঠানো হয়েছে।"
                        "call" -> "ফোন ডায়ালার খোলা হয়েছে; কল করার আগে নম্বরটি যাচাই করুন।"
                        "sms" -> "মেসেজ অ্যাপ খোলা হয়েছে; পাঠানোর আগে মেসেজটি যাচাই করুন।"
                        "showalarms" -> "অ্যালার্ম অ্যাপ খোলা হয়েছে।"
                        "showtimers" -> "টাইমার অ্যাপ খোলা হয়েছে।"
                        "accessibility" -> "Accessibility সেটিংস খুলেছি। সেখানে RUMIN AI ফোন নিয়ন্ত্রণ চালু করুন।"
                        "writesettings" -> "System settings পরিবর্তনের অনুমতি পেজ খুলেছি। দরকার হলে অনুমতি দিন।"
                        "notificationaccess" -> "Notification Access সেটিংস খুলেছি। অনুমতি প্রয়োজন হলে RUMIN AI নির্বাচন করুন।"
                        "apppermissions" -> "RUMIN AI-এর App Info খুলেছি। Permissions থেকে প্রয়োজনীয় অনুমতি দিন।"
                        "ttssettings" -> "Text-to-Speech সেটিংস খুলেছি। পছন্দের ভয়েস ও ভাষা পরীক্ষা করুন।"
                        "settings" -> "ফোনের সেটিংস খুলেছি।"
                        "wifi" -> "Wi-Fi সেটিংস খুলেছি।"
                        "bluetooth" -> "Bluetooth সেটিংস খুলেছি।"
                        "display" -> "ডিসপ্লে সেটিংস খুলেছি।"
                        "sound" -> "সাউন্ড সেটিংস খুলেছি।"
                        "camera" -> "ক্যামেরা খুলেছি।"
                        "contacts" -> "কন্টাক্টস খুলেছি।"
                        "browser" -> "ব্রাউজার খুলেছি।"
                        "youtube" -> "YouTube খুলেছি।"
                        "facebook" -> "Facebook খুলেছি।"
                        "whatsapp" -> "WhatsApp খুলেছি।"
                        "instagram" -> "Instagram খুলেছি।"
                        "gmail" -> "Gmail খুলেছি।"
                        "playstore" -> "Play Store খুলেছি।"
                        "maps" -> "Google Maps খুলেছি।"
                        else -> "ফোন ডায়ালার খোলা হয়েছে।"
                    }
                    deliverCommandResult(true, message, taskId)
                } catch (e: Exception) {
                    val message = org.json.JSONObject.quote("কমান্ড চালানো যায়নি: " + (e.message ?: "অজানা সমস্যা"))
                    deliverCommandResult(false, "কমান্ড চালানো যায়নি: " + (e.message ?: "অজানা সমস্যা"), taskId)
                }
            }
        }
    }


    private fun reportLiveVoice(message: String, state: String) {
        if (!::webView.isInitialized) return
        runOnUiThread {
            webView.evaluateJavascript("window.onLiveVoiceStatus && window.onLiveVoiceStatus(" + JSONObject.quote(message) + ", " + JSONObject.quote(state) + ")", null)
        }
    }

    private fun startLiveVoiceInternal(key: String) {
        liveVoiceSession?.stop()
        val session = GeminiLiveVoice(this,
            { message, state -> reportLiveVoice(message, state) },
            { userText, assistantText, audioReceived -> runOnUiThread {
                webView.evaluateJavascript("window.onLiveVoiceTurn && window.onLiveVoiceTurn(" + JSONObject.quote(userText) + ", " + JSONObject.quote(assistantText) + ")", null)
                // If Gemini returned text but no audio chunk, speak the answer locally instead of staying silent.
                if (assistantText.isNotBlank() && !audioReceived) {
                    val tts = textToSpeech
                    if (tts != null && ttsReady) {
                        val languageResult = tts.setLanguage(Locale("bn", "BD"))
                        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) {
                            tts.setLanguage(Locale("bn", "IN"))
                        }
                        tts.speak(assistantText, TextToSpeech.QUEUE_FLUSH, null, "rumin_live_fallback")
                    } else {
                        reportLiveVoice("Gemini উত্তর দিয়েছে, কিন্তু অডিও আসেনি এবং ফোনের বাংলা Text-to-Speech প্রস্তুত নয়। TTS সেটিংসে বাংলা কণ্ঠ ইনস্টল করুন।", "stopped")
                    }
                }
            } }
        )
        liveVoiceSession = session
        reportLiveVoice("Gemini Live-এ সংযোগ করছি…", "connecting")
        session.start(key)
    }

    override fun onDestroy() {
        liveVoiceSession?.stop()
        liveVoiceSession = null
        textToSpeech?.stop()
        continuousListening = false
        continuousRecognizer?.destroy()
        continuousRecognizer = null
        textToSpeech?.shutdown()
        aiExecutor.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
