package com.ruminai.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.PermissionRequest
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private lateinit var webView: WebView
    private var pendingPermissionRequest: PermissionRequest? = null
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val request = pendingPermissionRequest
        pendingPermissionRequest = null
        if (granted) request?.grant(request.resources) else request?.deny()
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
                    if (request.resources.contains(PermissionRequest.RESOURCE_AUDIO_CAPTURE) && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                        pendingPermissionRequest = request
                        micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    } else request.grant(request.resources)
                }
            }
        }
        webView.loadUrl("file:///android_asset/index.html")
    }
    inner class NativeBridge {
        @JavascriptInterface fun startSpeechRecognition() {
            runOnUiThread {
                if (!SpeechRecognizer.isRecognitionAvailable(this@MainActivity)) {
                    webView.evaluateJavascript("window.onNativeSpeechError && window.onNativeSpeechError('এই ফোনে Speech Recognition সাপোর্ট নেই')", null)
                    return@runOnUiThread
                }
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                    return@runOnUiThread
                }
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "bn-BD")
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "bn-BD")
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "রুমিন AI-কে বাংলায় বলুন")
                }
                try { speechLauncher.launch(intent) } catch (e: Exception) {
                    webView.evaluateJavascript("window.onNativeSpeechError && window.onNativeSpeechError('ভয়েস চালু করা যায়নি')", null)
                }
            }
        }
    }
    override fun onBackPressed() { if (::webView.isInitialized && webView.canGoBack()) webView.goBack() else super.onBackPressed() }
}
