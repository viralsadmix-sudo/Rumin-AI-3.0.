package com.ruminai.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
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
                    if (intent.resolveActivity(packageManager) == null) {
                        webView.evaluateJavascript("window.onNativeCommandResult && window.onNativeCommandResult(false, 'এই কাজের জন্য ফোনে উপযুক্ত অ্যাপ পাওয়া যায়নি')", null)
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

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }
}
