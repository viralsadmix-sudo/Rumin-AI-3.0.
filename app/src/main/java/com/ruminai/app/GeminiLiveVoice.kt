package com.ruminai.app

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.net.URLEncoder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GeminiLiveVoice(
    context: Context,
    private val onStatus: (String, String) -> Unit,
    private val onTurn: (String, String) -> Unit
) {
    private val appContext = context.applicationContext
    private val active = AtomicBoolean(true)
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).pingInterval(20, TimeUnit.SECONDS).build()
    private var socket: WebSocket? = null
    @Volatile private var recorder: AudioRecord? = null
    @Volatile private var echoCanceler: AcousticEchoCanceler? = null
    @Volatile private var noiseSuppressor: NoiseSuppressor? = null
    @Volatile private var player: AudioTrack? = null
    private val playbackQueue = LinkedBlockingQueue<ByteArray>()
    private val stopMarker = ByteArray(0)
    private val inputTranscript = StringBuilder()
    private val outputTranscript = StringBuilder()
    @Volatile private var setupDone = false
    @Volatile private var lastServerEvent = "সার্ভার থেকে কোনো বার্তা আসেনি"

    fun start(apiKey: String) {
        try {
            val encodedKey = URLEncoder.encode(apiKey, "UTF-8")
            val request = Request.Builder()
                .url("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent?key=" + encodedKey)
                .build()
            socket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    onStatus("Gemini Live সার্ভারে সংযোগ হয়েছে; সেশন প্রস্তুত হচ্ছে…", "connecting")
                    Thread {
                        try { Thread.sleep(12000) } catch (_: InterruptedException) { return@Thread }
                        if (active.get() && !setupDone) {
                            val serverName = response.header("server") ?: "অজানা"
                            val requestId = response.header("x-goog-request-id")
                                ?: response.header("x-request-id")
                                ?: "পাওয়া যায়নি"
                            onStatus(
                                "Live setup-এর উত্তর ১২ সেকেন্ডেও আসেনি। WebSocket HTTP ${response.code}; setup পাঠানো হয়েছে; server=$serverName; requestId=$requestId; শেষ বার্তা: $lastServerEvent। সংযোগটি ডায়াগনস্টিকের জন্য খোলা রাখা হয়েছে।",
                                "connecting"
                            )
                        }
                    }.start()
                    val setup = JSONObject()
                        .put("model", "models/gemini-3.8-live")
                        .put("generationConfig", JSONObject()
                            .put("responseModalities", JSONArray().put("AUDIO"))
                            .put("speechConfig", JSONObject().put("voiceConfig",
                                JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Kore")))))
                        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                            "তুমি রুমিন, বসের ব্যক্তিগত AI সহকারী। স্বাভাবিক বাংলাদেশি বাংলায় কথা বলবে, বন্ধুসুলভ ও সংক্ষিপ্ত উত্তর দেবে। ব্যবহারকারী কথা বললে সরাসরি কণ্ঠে উত্তর দাও। ব্যবহারকারী মাঝখানে কথা বললে থেমে তার কথা শোনো। ব্যবহারকারীর কথা স্পষ্টভাবে শনাক্ত হলে অবশ্যই প্রাসঙ্গিক কণ্ঠের উত্তর দেবে; শুধু নীরবতা থাকলে উত্তর তৈরি করবে না। নিজেকে ChatGPT বলে পরিচয় দেবে না।"))))
                        .put("inputAudioTranscription", JSONObject())
                        .put("outputAudioTranscription", JSONObject())
                        .put("realtimeInputConfig", JSONObject()
                            .put("automaticActivityDetection", JSONObject()
                                .put("startOfSpeechSensitivity", "START_SENSITIVITY_HIGH")
                                .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                                .put("silenceDurationMs", 900))
                            .put("activityHandling", "START_OF_ACTIVITY_INTERRUPTS"))
                    val sent = webSocket.send(JSONObject().put("setup", setup).toString())
                    if (!sent) onStatus("Gemini Live setup পাঠানো যায়নি: WebSocket বার্তাটি গ্রহণ করেনি।", "stopped")
                }

                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    // Decode binary frames too; server payload is JSON.
                    onMessage(webSocket, bytes.utf8())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    try {
                        val root = JSONObject(text)
                        lastServerEvent = root.keys().asSequence().toList().joinToString(",").ifBlank { "খালি server message" }
                        if (root.has("error")) {
                            val error = root.optJSONObject("error")
                            val message = error?.optString("message")?.takeIf { it.isNotBlank() }
                                ?: root.optString("message").takeIf { it.isNotBlank() }
                                ?: text.take(500)
                            onStatus("Gemini Live API ত্রুটি: $message", "stopped")
                            stop()
                            return
                        }
                        if (root.has("setupComplete")) {
                            setupDone = true
                            startAudio()
                            onStatus("লাইভ ভয়েস চালু—বস, কথা বলুন", "listening")
                            return
                        }
                        val content = root.optJSONObject("serverContent") ?: return
                        if (content.optJSONObject("inputTranscription")?.optString("text").orEmpty().isNotBlank()) {
                            onStatus("আপনার কথা শুনেছি, উত্তর তৈরি হচ্ছে…", "thinking")
                        }
                        if (content.optBoolean("interrupted", false)) {
                            playbackQueue.clear()
                            player?.let { try { it.pause(); it.flush(); it.play() } catch (_: Exception) {} }
                            onStatus("বস, বলুন—আমি শুনছি", "listening")
                        }
                        content.optJSONObject("inputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { inputTranscript.append(it) }
                        content.optJSONObject("outputTranscription")?.optString("text")?.takeIf { it.isNotBlank() }?.let { outputTranscript.append(it) }
                        val parts = content.optJSONObject("modelTurn")?.optJSONArray("parts")
                        if (parts != null) for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue
                            val inline = part.optJSONObject("inlineData") ?: part.optJSONObject("inline_data") ?: continue
                            val encoded = inline.optString("data")
                            if (encoded.isNotBlank()) {
                                try {
                                    playbackQueue.put(Base64.decode(encoded, Base64.DEFAULT))
                                    onStatus("রুমিন উত্তর দিচ্ছে… কথা বললে থেমে যাবে", "speaking")
                                } catch (_: Exception) {}
                            }
                        }
                        if (content.optBoolean("turnComplete", false)) {
                            playbackQueue.offer(stopMarker)
                            val userText = inputTranscript.toString().trim()
                            val assistantText = outputTranscript.toString().trim()
                            inputTranscript.setLength(0)
                            outputTranscript.setLength(0)
                            if (userText.isNotBlank() || assistantText.isNotBlank()) onTurn(userText, assistantText)
                        }
                    } catch (_: Exception) {
                        onStatus("লাইভ অডিও বার্তা পড়তে সমস্যা হয়েছে", "listening")
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (active.get()) {
                        val http = response?.code?.let { " (HTTP $it)" }.orEmpty()
                        onStatus("লাইভ WebSocket সংযোগ ব্যর্থ$http: " + (t.message ?: "নেটওয়ার্ক/API সংযোগ ত্রুটি"), "stopped")
                        stop()
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    if (active.get()) onStatus("Gemini Live সংযোগ বন্ধ করার সংকেত দিয়েছে (code $code): " + reason.ifBlank { "কারণ দেওয়া হয়নি" }, "stopped")
                }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (active.get()) onStatus("লাইভ সংযোগ বন্ধ হয়েছে (code $code): " + reason.ifBlank { "সার্ভার কারণ দেয়নি" }, "stopped")
                }
            })
        } catch (e: Exception) {
            onStatus("লাইভ সংযোগ শুরু হয়নি: " + (e.message ?: "অজানা সমস্যা"), "stopped")
            stop()
        }
    }

    private fun startAudio() {
        if (!active.get() || !setupDone) return
        try {
            val outMin = AudioTrack.getMinBufferSize(24000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT).coerceAtLeast(4096)
            player = AudioTrack(AudioManager.STREAM_MUSIC, 24000, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT, outMin * 2, AudioTrack.MODE_STREAM)
            player?.play()
            Thread {
                while (active.get()) {
                    try {
                        val chunk = playbackQueue.take()
                        if (chunk.isEmpty()) {
                            onStatus("বস, আমি শুনছি—আবার বলুন", "listening")
                            continue
                        }
                        player?.write(chunk, 0, chunk.size)
                    } catch (_: InterruptedException) { break }
                    catch (_: Exception) { if (active.get()) onStatus("অডিও চালাতে সমস্যা হয়েছে", "listening") }
                }
            }.start()

            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) throw IllegalStateException("ফোনের মাইক্রোফোন চালু করা যায়নি")
            val audioRecorder = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, 16000,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min * 2, 6400))
            recorder = audioRecorder
            // Reduce speaker-to-microphone feedback so Rumin does not hear its own voice as user speech.
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(audioRecorder.audioSessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(audioRecorder.audioSessionId)?.apply { enabled = true }
            }
            audioRecorder.startRecording()
            Thread {
                val buffer = ByteArray(640)
                while (active.get()) {
                    val count = try { audioRecorder.read(buffer, 0, buffer.size) } catch (_: Exception) { -1 }
                    if (count > 0 && active.get() && setupDone) {
                        val bytes = if (count == buffer.size) buffer.copyOf() else buffer.copyOf(count)
                        val blob = JSONObject().put("mimeType", "audio/pcm;rate=16000")
                            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                        socket?.send(JSONObject().put("realtimeInput", JSONObject().put("audio", blob)).toString())
                    } else if (count < 0) break
                }
            }.start()
        } catch (e: Exception) {
            onStatus("মাইক্রোফোন চালু হয়নি: " + (e.message ?: "অনুমতি/ডিভাইস পরীক্ষা করুন"), "stopped")
            stop()
        }
    }

    fun stop() {
        if (!active.compareAndSet(true, false)) return
        try { recorder?.stop() } catch (_: Exception) {}
        try { recorder?.release() } catch (_: Exception) {}
        recorder = null
        try { echoCanceler?.release() } catch (_: Exception) {}
        echoCanceler = null
        try { noiseSuppressor?.release() } catch (_: Exception) {}
        noiseSuppressor = null
        playbackQueue.clear()
        playbackQueue.offer(stopMarker)
        try { player?.pause(); player?.flush(); player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
        socket?.close(1000, "User stopped live voice")
        socket = null
        client.dispatcher.executorService.shutdown()
    }
}
