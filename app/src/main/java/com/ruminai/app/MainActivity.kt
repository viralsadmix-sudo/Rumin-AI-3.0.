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
import android.media.MediaPlayer
import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.Locale
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap

class MainActivity : ComponentActivity() {
    private val aiExecutor = Executors.newFixedThreadPool(3)
    private val activeAiConnections = ConcurrentHashMap<String, HttpURLConnection>()
    private val cancelledAiRequests = ConcurrentHashMap.newKeySet<String>()
    private val pendingAiRequests = ConcurrentHashMap.newKeySet<String>()
    private val pcNetworkExecutor = Executors.newSingleThreadExecutor()
    private val ttsExecutor = Executors.newSingleThreadExecutor()
    private val ttsHttp = OkHttpClient.Builder().callTimeout(35, TimeUnit.SECONDS).build()
    @Volatile private var elevenPlayer: MediaPlayer? = null
    private val agentCore = AgentCore()
    private val personalSystems by lazy { RuminPersonalSystems(applicationContext) }
    private lateinit var webView: WebView
    private var textToSpeech: TextToSpeech? = null
    private var ttsReady = false
    private var pendingPermissionRequest: PermissionRequest? = null
    private var pendingNativeSpeech = false
    private var continuousRecognizer: SpeechRecognizer? = null
    private var continuousListening = false
    private var pendingLiveKey: String? = null
    @Volatile private var pendingUpdateInstallFile: File? = null
    @Volatile private var waitingForInstallPermission = false

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
                    override fun onDone(utteranceId: String?) { runOnUiThread { if (::webView.isInitialized) webView.evaluateJavascript("window.onNativeSpeakDone && window.onNativeSpeakDone(); window.onNativeTtsDone && window.onNativeTtsDone()", null) } }
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

    private fun recentMemoryContext(): String {
        return try {
            val rows = JSONArray(personalSystems.memories())
            val start = maxOf(0, rows.length() - 8)
            (start until rows.length()).mapNotNull { index ->
                rows.optJSONObject(index)?.let { row ->
                    row.optString("text").takeIf { it.isNotBlank() }
                }
            }.joinToString(" | ").take(1800)
        } catch (_: Exception) { "" }
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
        /** Read-only LAN health probe. No pairing token or PC action is sent over HTTP. */
        @JavascriptInterface fun checkPcConnection(hostInput: String) {
            val host = hostInput.trim()
            val validIpv4 = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$").matches(host) &&
                host.split(".").all { (it.toIntOrNull() ?: -1) in 0..255 }
            if (!validIpv4) {
                runOnUiThread { webView.evaluateJavascript("window.onPcConnectionResult && window.onPcConnectionResult(false, 'সঠিক PC IPv4 address দিন (যেমন 192.168.1.10)', '')", null) }
                return
            }
            pcNetworkExecutor.execute {
                var message = "PC Companion পাওয়া যায়নি"
                var payload = ""
                var ok = false
                try {
                    java.net.Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress(host, 47831), 1800)
                        socket.soTimeout = 1800
                        val request = "GET /health HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
                        val response = socket.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText().take(8192) }
                        val split = response.indexOf("\r\n\r\n")
                        if (!response.startsWith("HTTP/1.1 200") || split < 0) throw IllegalStateException("PC Companion health check ব্যর্থ")
                        payload = response.substring(split + 4)
                        val health = JSONObject(payload)
                        if (health.optString("status") != "ready" || health.optString("service") != "Rumin Windows Companion")
                            throw IllegalStateException("এই ঠিকানায় Rumin Windows Companion পাওয়া যায়নি")
                        ok = true
                        message = "PC পাওয়া গেছে: " + health.optString("host", host) + " · Version " + health.optString("version", "unknown")
                    }
                } catch (e: Exception) {
                    message = when (e) {
                        is java.net.SocketTimeoutException -> "PC উত্তর দিচ্ছে না। Companion চালু আছে কি না ও Wi-Fi পরীক্ষা করুন।"
                        is java.net.ConnectException -> "PC-তে সংযোগ হয়নি। IP, একই Wi-Fi এবং Windows Firewall পরীক্ষা করুন।"
                        else -> e.message ?: "PC সংযোগ পরীক্ষা ব্যর্থ"
                    }
                }
                val safeMessage = JSONObject.quote(message)
                val safePayload = JSONObject.quote(payload)
                runOnUiThread { webView.evaluateJavascript("window.onPcConnectionResult && window.onPcConnectionResult($ok, $safeMessage, $safePayload)", null) }
            }
        }


        /** One-time LAN pairing. The returned bearer is encrypted at rest with Android Keystore. */
        @JavascriptInterface fun pairPcCompanion(hostInput: String, codeInput: String) {
            val host = hostInput.trim()
            val code = codeInput.trim()
            val validIpv4 = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$").matches(host) &&
                host.split(".").all { (it.toIntOrNull() ?: -1) in 0..255 }
            if (!validIpv4 || !Regex("^[0-9]{6}$").matches(code)) {
                runOnUiThread {
                    webView.evaluateJavascript("window.onPcPairResult && window.onPcPairResult(false, 'PC IPv4 address ও ৬ সংখ্যার Pairing Code দিন')", null)
                }
                return
            }
            pcNetworkExecutor.execute {
                var ok = false
                var message = "PC Pairing ব্যর্থ"
                try {
                    val body = JSONObject().put("code", code).toString().toByteArray(Charsets.UTF_8)
                    val responseText = java.net.Socket().use { socket ->
                        socket.connect(java.net.InetSocketAddress(host, 47831), 3500)
                        socket.soTimeout = 5000
                        val headers = "POST /v1/pair/join HTTP/1.1\r\n" +
                            "Host: $host:47831\r\nContent-Type: application/json\r\n" +
                            "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().apply {
                            write(headers.toByteArray(Charsets.US_ASCII))
                            write(body)
                            flush()
                        }
                        socket.getInputStream().bufferedReader(Charsets.UTF_8).use { it.readText().take(16384) }
                    }
                    val split = responseText.indexOf("\r\n\r\n")
                    if (split < 0) throw IllegalStateException("PC Companion-এর উত্তর বোঝা যায়নি")
                    val statusLine = responseText.substringBefore("\r\n")
                    val responseBody = responseText.substring(split + 4)
                    if (!statusLine.contains(" 200 ")) {
                        val status = statusLine.substringAfter(" ").substringBefore(" ").toIntOrNull()
                        message = when (status) {
                            410 -> "Pairing Code ভুল, মেয়াদ শেষ, অথবা আগে ব্যবহার হয়েছে। PC-তে নতুন Code তৈরি করুন।"
                            429 -> "অনেকবার চেষ্টা হয়েছে। ৫ মিনিট পরে আবার চেষ্টা করুন।"
                            else -> "PC Pairing প্রত্যাখ্যান করেছে (HTTP ${status ?: "?"})"
                        }
                    } else {
                        val result = JSONObject(responseBody)
                        val accessToken = result.optString("accessToken")
                        if (!result.optBoolean("paired") || accessToken.length < 32)
                            throw IllegalStateException("PC Companion বৈধ Pairing Token দেয়নি")
                        CompanionTokenStore(applicationContext).save(host, accessToken)
                        ok = true
                        message = "Rumin অ্যাপ PC Companion-এর সঙ্গে Pair হয়েছে। Token Android Keystore দিয়ে এনক্রিপ্ট করে রাখা হয়েছে। নিরাপত্তার জন্য PC-র Remote Control এখনো বন্ধ; HTTPS secure relay যুক্ত হলে চালু হবে।"
                    }
                } catch (e: Exception) {
                    message = when (e) {
                        is java.net.SocketTimeoutException -> "PC উত্তর দিচ্ছে না। Companion চালু আছে কি না দেখুন।"
                        is java.net.ConnectException -> "PC-তে সংযোগ হয়নি। IP ও একই Wi-Fi পরীক্ষা করুন।"
                        else -> e.message ?: "PC Pairing ব্যর্থ"
                    }
                }
                val safe = JSONObject.quote(message)
                runOnUiThread { webView.evaluateJavascript("window.onPcPairResult && window.onPcPairResult($ok, $safe)", null) }
            }
        }

        @JavascriptInterface fun checkForAppUpdate() {
            pcNetworkExecutor.execute {
                var ok = false
                var available = false
                var version = ""
                var notes = ""
                var message = "আপডেট পরীক্ষা করা যায়নি"
                try {
                    val connection = (URL("https://api.github.com/repos/viralsadmix-sudo/Rumin-AI-3.0./releases/latest").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10000; readTimeout = 15000
                        setRequestProperty("Accept", "application/vnd.github+json")
                        setRequestProperty("User-Agent", "Rumin-AI-Android")
                    }
                    val release = try {
                        if (connection.responseCode !in 200..299) throw IllegalStateException("GitHub Release API HTTP ${connection.responseCode}")
                        JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    } finally { connection.disconnect() }
                    version = release.optString("tag_name")
                    notes = release.optString("body").take(700)
                    val asset = release.optJSONArray("assets")?.let { assets ->
                        (0 until assets.length()).map { assets.getJSONObject(it) }
                            .firstOrNull { it.optString("name").endsWith(".apk", true) }
                    } ?: throw IllegalStateException("সর্বশেষ Release-এ APK পাওয়া যায়নি")
                    val current = BuildConfig.VERSION_NAME
                    val remoteDigits = version.removePrefix("v").substringAfterLast(".").toIntOrNull() ?: 0
                    val currentDigits = current.substringAfterLast(".").toIntOrNull() ?: 0
                    available = version != current && remoteDigits > currentDigits
                    ok = true
                    message = if (available) "নতুন সংস্করণ পাওয়া গেছে: $version" else "আপনার Rumin AI সর্বশেষ সংস্করণে আছে ($current)"
                } catch (e: Exception) { message = e.message ?: "আপডেট পরীক্ষা ব্যর্থ" }
                val safe = JSONObject.quote(message)
                val safeVersion = JSONObject.quote(version)
                val safeNotes = JSONObject.quote(notes)
                runOnUiThread { webView.evaluateJavascript("window.onRuminUpdateCheck && window.onRuminUpdateCheck($ok, $available, $safeVersion, $safeNotes, $safe)", null) }
            }
        }

        @JavascriptInterface fun downloadAndInstallUpdate() {
            pcNetworkExecutor.execute {
                var ok = false
                var message = "APK ডাউনলোড করা যায়নি"
                try {
                    val api = (URL("https://api.github.com/repos/viralsadmix-sudo/Rumin-AI-3.0./releases/latest").openConnection() as HttpURLConnection).apply {
                        connectTimeout = 10000; readTimeout = 15000
                        setRequestProperty("Accept", "application/vnd.github+json")
                        setRequestProperty("User-Agent", "Rumin-AI-Android")
                    }
                    val release = try {
                        if (api.responseCode !in 200..299) throw IllegalStateException("GitHub API HTTP ${api.responseCode}")
                        JSONObject(api.inputStream.bufferedReader().use { it.readText() })
                    } finally { api.disconnect() }
                    val asset = release.optJSONArray("assets")?.let { assets ->
                        (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name").endsWith(".apk", true) }
                    } ?: throw IllegalStateException("Release APK পাওয়া যায়নি")
                    val downloadUrl = asset.optString("browser_download_url")
                    if (!downloadUrl.startsWith("https://github.com/")) throw IllegalStateException("নিরাপদ GitHub APK URL পাওয়া যায়নি")
                    val connection = (URL(downloadUrl).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 15000; readTimeout = 60000; instanceFollowRedirects = true
                        setRequestProperty("User-Agent", "Rumin-AI-Android")
                    }
                    val apk = File(cacheDir, "rumin-update.apk")
                    try {
                        if (connection.responseCode !in 200..299) throw IllegalStateException("APK ডাউনলোড HTTP ${connection.responseCode}")
                        if (connection.contentLengthLong > 120L * 1024L * 1024L) throw IllegalStateException("APK ফাইল অস্বাভাবিক বড়")
                        connection.inputStream.use { input -> apk.outputStream().use { output ->
                            val buffer = ByteArray(8192); var total = 0L
                            while (true) { val count = input.read(buffer); if (count < 0) break; total += count
                                if (total > 120L * 1024L * 1024L) throw IllegalStateException("APK ফাইল সীমার চেয়ে বড়")
                                output.write(buffer, 0, count)
                            }
                        } }
                    } finally { connection.disconnect() }
                    if (!apk.exists() || apk.length() < 500000L) throw IllegalStateException("ডাউনলোড করা APK অসম্পূর্ণ")
                    val archive = packageManager.getPackageArchiveInfo(apk.absolutePath, 0)
                        ?: throw IllegalStateException("ডাউনলোড করা ফাইলটি বৈধ APK নয়")
                    if (archive.packageName != packageName) throw IllegalStateException("APK-টি Rumin AI-এর নয়")
                    if (archive.versionCode <= BuildConfig.VERSION_CODE) throw IllegalStateException("ইনস্টল করা সংস্করণ নতুন বা সমান")
                    pendingUpdateInstallFile = apk
                    ok = true
                    message = "APK ডাউনলোড ও পরীক্ষা সম্পন্ন। এখন Android Installer খুলছি।"
                    runOnUiThread { installDownloadedUpdate() }
                } catch (e: Exception) { message = e.message ?: "APK ডাউনলোড ব্যর্থ" }
                val safe = JSONObject.quote(message)
                runOnUiThread { webView.evaluateJavascript("window.onRuminUpdateDownload && window.onRuminUpdateDownload($ok, $safe)", null) }
            }
        }

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
                val intent = Intent(this@MainActivity, RuminLiveVoiceService::class.java).apply { action = RuminLiveVoiceService.ACTION_STOP }
                startService(intent)
                reportLiveVoice("লাইভ ভয়েস বন্ধ করা হয়েছে", "stopped")
            }
        }

        @JavascriptInterface fun sendLiveToolResponse(callId: String, functionName: String, responseJson: String) {
            try {
                val response = JSONObject(responseJson)
                val sent = RuminLiveVoiceService.current?.sendToolResponse(callId, functionName, response) == true
                if (!sent) reportLiveVoice("ফোনের কাজের ফল Gemini Live-এ ফেরত পাঠানো যায়নি।", "listening")
            } catch (e: Exception) {
                reportLiveVoice("ফোনের কাজের ফল পাঠাতে সমস্যা হয়েছে: ${e.message ?: "অজানা ত্রুটি"}", "listening")
            }
        }

        @JavascriptInterface fun speakText(text: String, proxyUrl: String, voiceId: String, modelId: String, accessToken: String) {
            val endpoint = proxyUrl.trim().trimEnd('/')
            if (endpoint.startsWith("https://") && voiceId.isNotBlank()) {
                ttsExecutor.execute {
                    try {
                        val payload = JSONObject().put("text", text).put("voice_id", voiceId)
                            .put("model_id", modelId.ifBlank { "eleven_flash_v2_5" }).toString()
                        val request = Request.Builder().url("$endpoint/v1/tts")
                            .header("Authorization", "Bearer $accessToken")
                            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                        ttsHttp.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) throw IllegalStateException("ElevenLabs Backend HTTP ${response.code}")
                            val bytes = response.body?.bytes() ?: throw IllegalStateException("Backend audio খালি")
                            if (bytes.isEmpty()) throw IllegalStateException("Backend audio খালি")
                            val audioFile = File(cacheDir, "rumin-elevenlabs-${System.currentTimeMillis()}.mp3")
                            audioFile.writeBytes(bytes)
                            runOnUiThread {
                                try {
                                    elevenPlayer?.release()
                                    val player = MediaPlayer()
                                    elevenPlayer = player
                                    player.setDataSource(audioFile.absolutePath)
                                    player.setOnPreparedListener { it.start() }
                                    player.setOnCompletionListener {
                                        it.release()
                                        if (elevenPlayer === it) elevenPlayer = null
                                        audioFile.delete()
                                        webView.evaluateJavascript("window.onNativeTtsDone && window.onNativeTtsDone()", null)
                                    }
                                    player.setOnErrorListener { mp, _, _ ->
                                        mp.release()
                                        if (elevenPlayer === mp) elevenPlayer = null
                                        audioFile.delete()
                                        speakWithDeviceTts(text)
                                        true
                                    }
                                    player.prepareAsync()
                                } catch (_: Exception) {
                                    audioFile.delete()
                                    speakWithDeviceTts(text)
                                }
                            }
                        }
                    } catch (_: Exception) {
                        runOnUiThread { speakWithDeviceTts(text) }
                    }
                }
            } else {
                runOnUiThread { speakWithDeviceTts(text) }
            }
        }

        private fun speakWithDeviceTts(text: String) {
            val tts = textToSpeech
            if (tts == null || !ttsReady) {
                webView.evaluateJavascript("window.onNativeTtsError && window.onNativeTtsError('ফোনের বাংলা Text-to-Speech প্রস্তুত নয়। Settings থেকে Bengali voice data ইনস্টল করুন।')", null)
                return
            }
            val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "rumin_answer")
            if (result == TextToSpeech.ERROR) webView.evaluateJavascript("window.onNativeTtsError && window.onNativeTtsError('ভয়েস চালু করা যায়নি। ফোনের Text-to-Speech সেটিংস পরীক্ষা করুন।')", null)
        }

        @JavascriptInterface fun speak(text: String) { runOnUiThread { speakWithDeviceTts(text) } }

        @JavascriptInterface fun stopSpeaking() { runOnUiThread { textToSpeech?.stop(); elevenPlayer?.let { it.stop(); it.release() }; elevenPlayer = null } }

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

        /** Store an explicit user-approved memory locally on this phone. */
        @JavascriptInterface fun rememberPersonalFact(text: String, tag: String = "general"): Boolean {
            return personalSystems.remember(text, tag)
        }

        @JavascriptInterface fun getPersonalMemories(): String = personalSystems.memories()

        @JavascriptInterface fun clearPersonalMemories() {
            personalSystems.clearMemories()
        }

        /** Local phone reminder. Cloud mirroring is enabled only after a backend URL is configured. */
        @JavascriptInterface fun schedulePhoneReminder(title: String, message: String, atMillis: Long): String {
            return try { personalSystems.scheduleReminder(title, message, atMillis) }
            catch (e: Exception) { "রিমাইন্ডার সেট করা যায়নি: ${e.message ?: "অজানা সমস্যা"}" }
        }

        @JavascriptInterface fun recordPerformanceMetric(name: String, durationMs: Long, success: Boolean) {
            personalSystems.metric(name, durationMs, success)
        }

        @JavascriptInterface fun getPerformanceMetrics(): String = personalSystems.metrics()

        @JavascriptInterface fun cancelAiRequest(requestId: String) {
            val id = requestId.trim()
            if (id.isNotBlank() && pendingAiRequests.contains(id)) {
                cancelledAiRequests.add(id)
                activeAiConnections.remove(id)?.disconnect()
            }
        }

        @JavascriptInterface fun requestAi(provider: String, key: String, historyJson: String, currentText: String, requestId: String) {
            if (key.isBlank()) {
                deliverAiResult(requestId, false, "API Key ফাঁকা")
                return
            }
            pendingAiRequests.add(requestId)
            aiExecutor.execute {
                if (cancelledAiRequests.remove(requestId)) { pendingAiRequests.remove(requestId); return@execute }
                var connection: HttpURLConnection? = null
                try {
                    val p = provider.trim().lowercase()
                    val isGemini = p == "gemini"
                    val baseUrl = when (p) {
                        "grok" -> "https://api.x.ai/v1/chat/completions"
                        "groq" -> "https://api.groq.com/openai/v1/chat/completions"
                        "openai" -> "https://api.openai.com/v1/chat/completions"
                        "muse" -> "https://api.meta.ai/v1/chat/completions"
                        "gemini" -> "https://generativelanguage.googleapis.com/v1beta/interactions"
                        else -> throw IllegalArgumentException("অসমর্থিত AI Provider: $provider")
                    }
                    val history = JSONArray(historyJson)
                    val body: JSONObject
                    if (isGemini) {
                        val lines = mutableListOf<String>()
                        lines.add("নির্দেশনা: তুমি রুমিন এআই, বসের ব্যক্তিগত বাংলা সহকারী। স্বাভাবিক বাংলাদেশি বাংলায় উত্তর দাও। ফোনের কাজ নিজে সম্পন্ন হয়েছে বলবে না; ফোনের কাজ Native Agent Engine করে এবং ফলাফল যাচাই করে.")
                        val memoryContext = recentMemoryContext()
                        if (memoryContext.isNotBlank()) lines.add("ব্যবহারকারীর সংরক্ষিত স্মৃতি (প্রাসঙ্গিক হলে ব্যবহার করো): " + memoryContext)
                        for (i in 0 until history.length()) {
                            val item = history.optJSONObject(i) ?: continue
                            val label = if (item.optString("role") == "assistant") "রুমিন: " else "ব্যবহারকারী: "
                            lines.add(label + item.optString("content"))
                        }
                        lines.add("ব্যবহারকারী: " + currentText)
                        body = JSONObject().put("model", "gemini-3.5-flash-lite").put("input", lines.joinToString("\n"))
                    } else {
                        val messages = JSONArray()
                        val memoryContext = recentMemoryContext()
                        val plannerPrompt = "তুমি Rumin AI-এর Task Agent Planner। ব্যবহারকারীর লক্ষ্য, স্ক্রিন ও আগের ধাপ দেখে পরবর্তী একটি নিরাপদ Android tool action বেছে নাও। শুধু বৈধ JSON দাও; ফোনে কাজ Native Android bridge করবে, তুমি নিজে ফোন নিয়ন্ত্রণ করো না। কাজ শেষ না হলে done:true দেবে না." +
                            if (memoryContext.isBlank()) "" else "\nপ্রাসঙ্গিক সংরক্ষিত স্মৃতি: " + memoryContext
                        messages.put(JSONObject().put("role", "system").put("content", plannerPrompt))
                        // Keep planner context compact so free-tier TPM limits are not exhausted by old chat history.
                        val historyLimit = if (p == "groq") 3 else 4
                        val startIndex = maxOf(0, history.length() - historyLimit)
                        for (i in startIndex until history.length()) {
                            val item = history.optJSONObject(i) ?: continue
                            val role = if (item.optString("role") == "assistant") "assistant" else "user"
                            val content = item.optString("content").takeLast(900)
                            messages.put(JSONObject().put("role", role).put("content", content))
                        }
                        messages.put(JSONObject().put("role", "user").put("content", currentText.take(1800)))
                        val model = when (p) {
                            "grok" -> "grok-4.7"
                            "groq" -> "openai/gpt-oss-120b"
                            "openai" -> "gpt-6.2-sol"
                            "muse" -> "muse-spark-1.3"
                            else -> ""
                        }
                        body = JSONObject().put("model", model).put("messages", messages)
                        if (p == "groq") {
                            body.put("max_completion_tokens", 512)
                            body.put("reasoning_effort", "low")
                            body.put("include_reasoning", false)
                        }
                    }
                    val activeConnection = (URL(baseUrl).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 20000
                        readTimeout = 60000
                        doOutput = true
                        setRequestProperty("Content-Type", "application/json; charset=utf-8")
                        if (isGemini) setRequestProperty("x-goog-api-key", key)
                        else setRequestProperty("Authorization", "Bearer $key")
                    }
                    connection = activeConnection
                    activeAiConnections[requestId] = activeConnection
                    if (cancelledAiRequests.contains(requestId)) {
                        activeConnection.disconnect()
                        throw InterruptedException("AI request cancelled")
                    }
                    activeConnection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val status = activeConnection.responseCode
                    val stream = if (status in 200..299) activeConnection.inputStream else activeConnection.errorStream
                    val responseText = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    val response = try { JSONObject(responseText) } catch (_: Exception) { JSONObject() }
                    if (status !in 200..299) {
                        val errorObj = response.optJSONObject("error")
                        val message = errorObj?.optString("message")?.takeIf { it.isNotBlank() }
                            ?: response.optString("message").takeIf { it.isNotBlank() }
                            ?: responseText.take(500).takeIf { it.isNotBlank() }
                            ?: "HTTP $status"
                        throw IllegalStateException("HTTP $status — $message")
                    }
                    val reply = if (isGemini) extractGeminiText(response)
                        else response.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                    if (reply.isBlank()) throw IllegalStateException("AI থেকে ফাঁকা উত্তর এসেছে")
                    deliverAiResult(requestId, true, reply)
                } catch (e: Exception) {
                    val detail = when (e) {
                        is java.net.UnknownHostException -> "ইন্টারনেট/DNS সংযোগ নেই: ${e.message ?: "হোস্ট খুঁজে পাওয়া যায়নি"}"
                        is java.net.SocketTimeoutException -> "API উত্তর দিতে সময় নিয়েছে: ${e.message ?: "সময় শেষ"}"
                        is javax.net.ssl.SSLException -> "নিরাপদ HTTPS সংযোগ ব্যর্থ: ${e.message ?: "SSL ত্রুটি"}"
                        else -> "${e.javaClass.simpleName}: ${e.message ?: "অজানা সমস্যা"}"
                    }
                    deliverAiResult(requestId, false, detail)
                } finally {
                    activeAiConnections.remove(requestId)
                    cancelledAiRequests.remove(requestId)
                    pendingAiRequests.remove(requestId)
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
                    val startedAt = System.nanoTime()
                    val result = RuminAccessibilityService.nativeAction(actualAction, first)
                    val raw = result.toString()
                    val verified = AgentVerifier.verifyNativeJson(raw)
                    personalSystems.metric("native:$actualAction", (System.nanoTime() - startedAt) / 1_000_000L, verified)
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
                    if (action == "list_apps") {
                        val filter = first.trim()
                        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        val apps = packageManager.queryIntentActivities(launcherIntent, 0)
                            .mapNotNull { resolved ->
                                val info = resolved.activityInfo ?: return@mapNotNull null
                                val pkg = info.packageName
                                val label = resolved.loadLabel(packageManager).toString()
                                if (pkg == packageName) null else Pair(label, pkg)
                            }
                            .filter { filter.isBlank() || it.first.contains(filter, ignoreCase = true) || it.second.contains(filter, ignoreCase = true) }
                            .distinctBy { it.second }
                            .sortedBy { it.first.lowercase() }
                            .take(40)
                        val rows = JSONArray()
                        apps.forEach { (label, pkg) ->
                            rows.put(JSONObject().put("name", label).put("packageName", pkg))
                        }
                        val result = JSONObject().put("count", rows.length()).put("apps", rows).toString()
                        deliverCommandResult(true, result, taskId)
                        return@runOnUiThread
                    }
                    if (action == "open_app") {
                        val requested = first.trim()
                        if (requested.isBlank()) {
                            deliverCommandResult(false, "কোন অ্যাপ খুলতে হবে তা বুঝতে পারিনি।", taskId)
                            return@runOnUiThread
                        }
                        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                        val candidates = packageManager.queryIntentActivities(launcherIntent, 0)
                            .mapNotNull { resolved ->
                                val info = resolved.activityInfo ?: return@mapNotNull null
                                if (info.packageName == packageName) null
                                else Pair(resolved.loadLabel(packageManager).toString(), info.packageName)
                            }
                            .distinctBy { it.second }
                        val normalized = requested.lowercase().replace(Regex("[\\s_\\-]+"), "")
                        val exact = candidates.filter { it.first.lowercase().replace(Regex("[\\s_\\-]+"), "") == normalized }
                        val matches = if (exact.isNotEmpty()) exact else candidates.filter {
                            val label = it.first.lowercase().replace(Regex("[\\s_\\-]+"), "")
                            label.contains(normalized) || normalized.contains(label)
                        }
                        if (matches.size != 1) {
                            val message = if (matches.isEmpty()) "‘$requested’ নামে কোনো ইনস্টল করা অ্যাপ খুঁজে পাইনি।"
                                else "‘$requested’ নামে একাধিক অ্যাপ পাওয়া গেছে: " + matches.take(4).joinToString(", ") { it.first } + "। নির্দিষ্ট নাম বলুন।"
                            deliverCommandResult(false, message, taskId)
                            return@runOnUiThread
                        }
                        val (label, targetPackage) = matches.first()
                        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
                        if (launchIntent == null) {
                            deliverCommandResult(false, "$label অ্যাপটি চালু করার মতো Launcher পাওয়া যায়নি।", taskId)
                            return@runOnUiThread
                        }
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                        deliverCommandResult(true, "$label খুলেছি।", taskId)
                        return@runOnUiThread
                    }

                    if (action == "launch_app") {
                        val targetPackage = first.trim()
                        if (!Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$").matches(targetPackage)) {
                            deliverCommandResult(false, "অ্যাপের বৈধ package name পাওয়া যায়নি।", taskId)
                            return@runOnUiThread
                        }
                        val launchIntent = packageManager.getLaunchIntentForPackage(targetPackage)
                        if (launchIntent == null) {
                            deliverCommandResult(false, "এই package-এর কোনো চালু করার মতো অ্যাপ পাওয়া যায়নি: $targetPackage", taskId)
                            return@runOnUiThread
                        }
                        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        startActivity(launchIntent)
                        val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(targetPackage, 0)).toString() } catch (_: Exception) { targetPackage }
                        deliverCommandResult(true, JSONObject().put("name", label).put("packageName", targetPackage).toString(), taskId)
                        return@runOnUiThread
                    }
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
                        deliverCommandResult(ok, message, taskId)
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
                            deliverCommandResult(false, message, taskId)
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
                        "chatgpt" -> packageManager.getLaunchIntentForPackage("com.openai.chatgpt") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com"))
                        else -> null
                    }
                    if (intent == null) {
                        deliverCommandResult(false, "এই কমান্ডটি সমর্থিত নয়", taskId)
                        return@runOnUiThread
                    }
                    try { startActivity(intent) } catch (first: Exception) { when (action) { "youtube" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))); "whatsapp" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/"))); "instagram" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com"))); "gmail" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com"))); "playstore" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store"))); "maps" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps"))); "chatgpt" -> startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://chatgpt.com"))); else -> throw first } }
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
        try {
            val intent = Intent(this, RuminLiveVoiceService::class.java).apply {
                action = RuminLiveVoiceService.ACTION_START
                putExtra(RuminLiveVoiceService.EXTRA_API_KEY, key)
            }
            androidx.core.content.ContextCompat.startForegroundService(this, intent)
            reportLiveVoice("ব্যাকগ্রাউন্ড Live Voice Service চালু হচ্ছে…", "connecting")
        } catch (e: Exception) {
            reportLiveVoice("Background Voice Service চালু করা যায়নি: ${e.message ?: "Android অনুমতি/সার্ভিস পরীক্ষা করুন"}", "stopped")
        }
    }

    private val liveUiListener = object : RuminLiveVoiceService.Listener {
        override fun onStatus(message: String, state: String) { reportLiveVoice(message, state) }
        override fun onTurn(userText: String, assistantText: String, audioReceived: Boolean) {
            runOnUiThread {
                if (::webView.isInitialized) webView.evaluateJavascript("window.onLiveVoiceTurn && window.onLiveVoiceTurn(" + JSONObject.quote(userText) + ", " + JSONObject.quote(assistantText) + ")", null)
                if (assistantText.isNotBlank() && !audioReceived) {
                    val tts = textToSpeech
                    if (tts != null && ttsReady) {
                        val languageResult = tts.setLanguage(Locale("bn", "BD"))
                        if (languageResult == TextToSpeech.LANG_MISSING_DATA || languageResult == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale("bn", "IN"))
                        tts.speak(assistantText, TextToSpeech.QUEUE_FLUSH, null, "rumin_live_fallback")
                    } else reportLiveVoice("Gemini উত্তর দিয়েছে, কিন্তু অডিও আসেনি এবং ফোনের বাংলা Text-to-Speech প্রস্তুত নয়। TTS সেটিংসে বাংলা কণ্ঠ ইনস্টল করুন।", "stopped")
                }
            }
        }
        override fun onToolCall(callId: String, functionName: String, args: JSONObject) {
            runOnUiThread {
                if (::webView.isInitialized) {
                    val argsJson = args.toString()
                    webView.evaluateJavascript("window.onLiveToolCall && window.onLiveToolCall(${JSONObject.quote(callId)}, ${JSONObject.quote(functionName)}, ${JSONObject.quote(argsJson)})", null)
                }
            }
        }
    }

    private fun installDownloadedUpdate() {
        val apk = pendingUpdateInstallFile ?: return
        if (!apk.exists()) { pendingUpdateInstallFile = null; return }
        if (android.os.Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            waitingForInstallPermission = true
            startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        waitingForInstallPermission = false
        pendingUpdateInstallFile = null
        try {
            val contentUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            if (::webView.isInitialized) webView.evaluateJavascript("window.onRuminUpdateDownload && window.onRuminUpdateDownload(false, ${JSONObject.quote(e.message ?: "Installer খুলতে সমস্যা")})", null)
        }
    }

    override fun onResume() {
        super.onResume()
        RuminLiveVoiceService.listener = liveUiListener
        if (waitingForInstallPermission && (android.os.Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())) installDownloadedUpdate()
        RuminLiveVoiceService.current?.let { reportLiveVoice("Live Voice ব্যাকগ্রাউন্ডে চালু আছে।", "listening") }
    }

    override fun onDestroy() {
        if (RuminLiveVoiceService.listener === liveUiListener) RuminLiveVoiceService.listener = null
        textToSpeech?.stop()
        continuousListening = false
        continuousRecognizer?.destroy()
        continuousRecognizer = null
        textToSpeech?.shutdown()
        elevenPlayer?.release()
        elevenPlayer = null
        ttsExecutor.shutdownNow()
        aiExecutor.shutdown()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
