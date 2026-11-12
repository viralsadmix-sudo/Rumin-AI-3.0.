package com.ruminai.app

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Rumin AI 3.5 Video Studio client.
 *
 * The Android client uploads media to an authenticated Rumin backend.
 * OpenMontage itself runs on the server; it is intentionally not bundled in the APK.
 */
class VideoStudioActivity : ComponentActivity() {
    private lateinit var studioWeb: WebView
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var selectedVideo: Uri? = null

    private val videoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            selectedVideo = uri
            try { contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) { }
            val name = displayName(uri)
            val mime = contentResolver.getType(uri) ?: "video/*"
            val size = displaySize(uri)
            studioWeb.evaluateJavascript(
                "window.onRuminVideoPicked && window.onRuminVideoPicked(${JSONObject.quote(name)}, ${JSONObject.quote(mime)}, $size)",
                null
            )
        } else {
            studioWeb.evaluateJavascript("window.onRuminStudioMessage && window.onRuminStudioMessage('ভিডিও নির্বাচন বাতিল হয়েছে')", null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        studioWeb = WebView(this)
        setContentView(studioWeb)
        studioWeb.settings.javaScriptEnabled = true
        studioWeb.settings.domStorageEnabled = true
        studioWeb.addJavascriptInterface(StudioBridge(), "RuminStudio")
        studioWeb.webViewClient = WebViewClient()
        studioWeb.webChromeClient = WebChromeClient()
        studioWeb.loadUrl("file:///android_asset/video_studio.html")
    }

    private fun displayName(uri: Uri): String {
        var name = "video.mp4"
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        return name.replace(Regex("[\\r\\n\\\\/]"), "_").take(120)
    }

    private fun displaySize(uri: Uri): Long {
        contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0)
        }
        return -1L
    }

    private fun safeBaseUrl(raw: String): String {
        val value = raw.trim().trimEnd('/')
        val parsed = Uri.parse(value)
        require(parsed.scheme == "https" && !parsed.host.isNullOrBlank()) {
            "নিরাপত্তার জন্য HTTPS backend URL দিতে হবে"
        }
        return value
    }

    inner class StudioBridge {
        @JavascriptInterface fun pickVideo() {
            runOnUiThread { videoPicker.launch(arrayOf("video/*")) }
        }

        @JavascriptInterface fun submitVideoJob(baseUrl: String, token: String, pipeline: String, prompt: String) {
            if (token.isBlank()) {
                studioResult(false, "Backend access token দিন")
                return
            }
            val uri = selectedVideo
            if (uri == null) {
                studioResult(false, "আগে একটি ভিডিও নির্বাচন করুন")
                return
            }
            val allowed = setOf("clip-factory", "documentary-montage", "localization-dub", "animated-explainer", "podcast-repurpose", "screen-demo", "talking-head")
            if (pipeline !in allowed) {
                studioResult(false, "এই ভিডিও pipeline অনুমোদিত নয়")
                return
            }
            if (prompt.isBlank()) {
                studioResult(false, "কাজের নির্দেশনা লিখুন")
                return
            }
            io.execute {
                var connection: HttpURLConnection? = null
                try {
                    val endpoint = safeBaseUrl(baseUrl) + "/api/v1/video/jobs"
                    val boundary = "RuminBoundary-" + UUID.randomUUID().toString().replace("-", "")
                    connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                        requestMethod = "POST"
                        connectTimeout = 20000
                        readTimeout = 120000
                        doOutput = true
                        setChunkedStreamingMode(64 * 1024)
                        setRequestProperty("Authorization", "Bearer $token")
                        setRequestProperty("Accept", "application/json")
                        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
                    }
                    DataOutputStream(connection.outputStream).use { out ->
                        fun field(name: String, value: String) {
                            out.writeBytes("--$boundary\r\n")
                            out.writeBytes("Content-Disposition: form-data; name=\"$name\"\r\n\r\n")
                            out.write(value.toByteArray(Charsets.UTF_8))
                            out.writeBytes("\r\n")
                        }
                        field("pipeline", pipeline)
                        field("prompt", prompt.take(8000))
                        field("output_format", "mp4")
                        field("subtitle_language", "bn")
                        val mime = contentResolver.getType(uri) ?: "video/mp4"
                        val filename = displayName(uri).replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "video.mp4" }
                        out.writeBytes("--$boundary\r\n")
                        out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n")
                        out.writeBytes("Content-Type: $mime\r\n\r\n")
                        contentResolver.openInputStream(uri)?.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val count = input.read(buffer)
                                if (count < 0) break
                                total += count
                                if (total > 500L * 1024L * 1024L) throw IllegalArgumentException("ভিডিও ৫০০ MB-এর বেশি; ছোট ফাইল ব্যবহার করুন")
                                out.write(buffer, 0, count)
                            }
                        } ?: throw IllegalStateException("ভিডিও ফাইল পড়া যায়নি")
                        out.writeBytes("\r\n--$boundary--\r\n")
                        out.flush()
                    }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (status !in 200..299) throw IllegalStateException("Backend HTTP $status: " + body.take(500))
                    val json = JSONObject(body)
                    val jobId = json.optString("job_id", json.optString("id"))
                    if (jobId.isBlank()) throw IllegalStateException("Backend উত্তর পেয়েছি, কিন্তু job_id নেই")
                    studioResult(true, body)
                } catch (e: Exception) {
                    studioResult(false, e.message ?: "ভিডিও job তৈরি করা যায়নি")
                } finally {
                    connection?.disconnect()
                }
            }
        }

        @JavascriptInterface fun getVideoJobStatus(baseUrl: String, token: String, jobId: String) {
            if (token.isBlank() || jobId.isBlank()) {
                studioResult(false, "Backend token এবং Job ID দিন")
                return
            }
            io.execute {
                var connection: HttpURLConnection? = null
                try {
                    val safeId = Uri.encode(jobId)
                    connection = (URL(safeBaseUrl(baseUrl) + "/api/v1/video/jobs/$safeId").openConnection() as HttpURLConnection).apply {
                        requestMethod = "GET"
                        connectTimeout = 15000
                        readTimeout = 30000
                        setRequestProperty("Authorization", "Bearer $token")
                        setRequestProperty("Accept", "application/json")
                    }
                    val status = connection.responseCode
                    val stream = if (status in 200..299) connection.inputStream else connection.errorStream
                    val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                    if (status !in 200..299) throw IllegalStateException("Backend HTTP $status: " + body.take(500))
                    studioResult(true, body)
                } catch (e: Exception) {
                    studioResult(false, e.message ?: "Job status পাওয়া যায়নি")
                } finally {
                    connection?.disconnect()
                }
            }
        }

        @JavascriptInterface fun closeStudio() {
            runOnUiThread { finish() }
        }
    }

    private fun studioResult(ok: Boolean, message: String) {
        runOnUiThread {
            if (::studioWeb.isInitialized) {
                studioWeb.evaluateJavascript(
                    "window.onRuminStudioResult && window.onRuminStudioResult($ok, ${JSONObject.quote(message)})",
                    null
                )
            }
        }
    }

    override fun onDestroy() {
        io.shutdown()
        if (::studioWeb.isInitialized) {
            studioWeb.removeJavascriptInterface("RuminStudio")
            studioWeb.destroy()
        }
        super.onDestroy()
    }
}
