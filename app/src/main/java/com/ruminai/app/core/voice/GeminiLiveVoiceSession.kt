package com.ruminai.app.core.voice

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.AudioManager
import android.util.Base64
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Experimental native-audio Gemini Live session.
 * Uses a user-supplied Gemini Developer API key; do not ship this direct-client
 * authentication approach for production without a trusted backend/token broker.
 */
class GeminiLiveVoiceSession(
    private val apiKey: String,
    private val scope: CoroutineScope,
    private val onStatus: (String) -> Unit,
    private val onTranscript: (String) -> Unit,
    private val onError: (String) -> Unit
) {
    companion object {
        private const val MODEL = "gemini-3.8-live"
        private const val INPUT_RATE = 16_000
        private const val OUTPUT_RATE = 24_000
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val stopped = AtomicBoolean(false)
    private var socket: WebSocket? = null
    private var recorder: AudioRecord? = null
    private var player: AudioTrack? = null
    private var captureJob: Job? = null
    private var setupReady = false
    @Volatile private var micPaused = false
    private var pendingTranscript = ""

    fun start() {
        if (apiKey.isBlank()) {
            onError("Gemini API Key সেট করা নেই।")
            return
        }
        onStatus("Gemini Live-এর সঙ্গে সংযোগ হচ্ছে…")
        val liveUrl = HttpUrl.Builder()
            .scheme("wss")
            .host("generativelanguage.googleapis.com")
            .addPathSegments("ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
            .addQueryParameter("key", apiKey)
            .build()
        val request = Request.Builder()
            .url(liveUrl)
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                val voiceConfig = JSONObject()
                    .put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Aoede"))
                val speechConfig = JSONObject()
                    .put("voiceConfig", voiceConfig)
                    .put("languageCode", "bn-BD")
                val instructionPart = JSONObject().put(
                    "text",
                    "তুমি Rumin, ব্যবহারকারীর ব্যক্তিগত বাংলা AI সহকারী। স্বাভাবিক, সংক্ষিপ্ত ও বন্ধুত্বপূর্ণভাবে বাংলায় কথা বলবে; ব্যবহারকারী ইংরেজিতে বললে ইংরেজিতে উত্তর দেবে। ব্যবহারকারী কোনো ফোনের কাজ চাইলে নিজে কাজ সম্পন্ন হয়েছে বলবে না; অ্যাপের Agent-কে কাজটি পরিকল্পনা ও অনুমতির জন্য পাঠানো হবে।"
                )
                val systemInstruction = JSONObject()
                    .put("parts", JSONArray().put(instructionPart))
                val generationConfig = JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", speechConfig)
                val setup = JSONObject()
                    .put("model", "models/$MODEL")
                    .put("generationConfig", generationConfig)
                    .put("inputAudioTranscription", JSONObject())
                    .put("outputAudioTranscription", JSONObject())
                    .put("systemInstruction", systemInstruction)
                webSocket.send(JSONObject().put("setup", setup).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    if (root.has("setupComplete")) {
                        setupReady = true
                        startAudio()
                        onStatus("লাইভ কথা চালু — বলুন, বস")
                    }
                    if (root.has("error")) {
                        onError(root.getJSONObject("error").toString())
                    }
                    val serverContent = root.optJSONObject("serverContent") ?: return
                    val inputText = serverContent.optJSONObject("inputTranscription")?.optString("text").orEmpty().trim()
                    if (inputText.isNotBlank()) pendingTranscript = inputText
                    if (serverContent.optBoolean("interrupted", false)) {
                        onStatus("আপনার কথা শুনছি…")
                    }
                    val turn = serverContent.optJSONObject("modelTurn")
                    val parts = turn?.optJSONArray("parts")
                    if (parts != null) {
                        for (i in 0 until parts.length()) {
                            val part = parts.optJSONObject(i) ?: continue
                            val inline = part.optJSONObject("inlineData") ?: continue
                            val mime = inline.optString("mimeType")
                            if (mime.startsWith("audio/pcm")) {
                                val bytes = Base64.decode(inline.optString("data"), Base64.DEFAULT)
                                ensurePlayer()
                                player?.write(bytes, 0, bytes.size)
                            }
                        }
                    }
                    if (serverContent.optBoolean("turnComplete", false)) {
                        val spokenRequest = pendingTranscript.trim()
                        pendingTranscript = ""
                        if (spokenRequest.isNotBlank()) {
                            micPaused = true
                            onTranscript(spokenRequest)
                        } else {
                            resumeListening()
                            onStatus("লাইভ চালু — আপনি কথা বলতে পারেন")
                        }
                    }
                } catch (t: Throwable) {
                    onError("Live response পড়া যায়নি: ${t.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!stopped.get()) onError("Live সংযোগ ব্যর্থ: ${t.message ?: response?.code}")
                stop()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!stopped.get()) onStatus("লাইভ সংযোগ বন্ধ হয়েছে: $reason")
                stop()
            }
        })
    }

    private fun startAudio() {
        if (stopped.get() || !setupReady || captureJob != null) return
        val minInput = AudioRecord.getMinBufferSize(
            INPUT_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minInput <= 0) {
            onError("এই ফোনে 16kHz মাইক্রোফোন অডিও শুরু করা যায়নি।")
            return
        }
        val input = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            INPUT_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minInput * 2, 4096)
        )
        if (input.state != AudioRecord.STATE_INITIALIZED) {
            input.release()
            onError("মাইক্রোফোন AudioRecord চালু হয়নি।")
            return
        }
        recorder = input
        input.startRecording()
        captureJob = scope.launch(Dispatchers.IO) {
            val buffer = ByteArray(2048)
            try {
                while (isActive && !stopped.get()) {
                    val count = input.read(buffer, 0, buffer.size)
                    if (count > 0) {
                        val audio = Base64.encodeToString(buffer.copyOf(count), Base64.NO_WRAP)
                        if (micPaused) continue
                        val message = JSONObject().put(
                            "realtimeInput",
                            JSONObject().put("audio", JSONObject()
                                .put("mimeType", "audio/pcm;rate=16000")
                                .put("data", audio))
                        )
                        socket?.send(message.toString())
                    }
                }
            } catch (t: Throwable) {
                if (!stopped.get()) onError("মাইক্রোফোন স্ট্রিম বন্ধ: ${t.message}")
            }
        }
    }

    private fun ensurePlayer() {
        if (player != null) return
        val min = AudioTrack.getMinBufferSize(
            OUTPUT_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (min <= 0) throw IllegalStateException("অডিও প্লেয়ার buffer তৈরি হয়নি")
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(OUTPUT_RATE)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build())
            .setBufferSizeInBytes(maxOf(min * 2, 8192))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        track.play()
        player = track
    }

    fun sendAgentReply(message: String) {
        if (message.isBlank() || stopped.get() || socket == null) return
        micPaused = true
        val part = JSONObject().put("text", "আমাদের ফোনের AI Agent-এর কাজের ফলাফল: $message। ব্যবহারকারীকে সংক্ষেপে বাংলায় জানাও।")
        val turn = JSONObject().put("role", "user").put("parts", JSONArray().put(part))
        val content = JSONObject().put("turns", JSONArray().put(turn)).put("turnComplete", true)
        socket?.send(JSONObject().put("clientContent", content).toString())
    }

    fun resumeListening() {
        micPaused = false
    }

    fun stop() {
        if (!stopped.compareAndSet(false, true)) return
        captureJob?.cancel()
        captureJob = null
        runCatching { recorder?.stop() }
        recorder?.release()
        recorder = null
        runCatching { player?.stop() }
        player?.release()
        player = null
        socket?.close(1000, "user stopped")
        socket = null
        client.dispatcher.executorService.shutdown()
        onStatus("লাইভ ভয়েস বন্ধ")
    }
}
