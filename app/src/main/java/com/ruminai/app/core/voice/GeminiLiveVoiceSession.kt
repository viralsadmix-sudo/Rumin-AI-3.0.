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
import kotlinx.coroutines.channels.Channel
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
data class LiveToolCall(val id: String, val name: String, val arguments: JSONObject)

class GeminiLiveVoiceSession(
    private val apiKey: String,
    private val scope: CoroutineScope,
    private val onStatus: (String) -> Unit,
    private val onTranscript: (String) -> Unit,
    private val onToolCall: (List<LiveToolCall>) -> Unit,
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
    private var playbackJob: Job? = null
    private val audioQueue = Channel<ByteArray>(Channel.UNLIMITED)
    private var setupReady = false
    @Volatile private var micPaused = false
    private var pendingTranscript = ""
    @Volatile private var awaitingToolResponse = false

    fun start() {
        if (apiKey.isBlank()) {
            onError("Gemini API Key সেট করা নেই।")
            return
        }
        onStatus("Gemini Live-এর সঙ্গে সংযোগ হচ্ছে…")
        val liveUrl = HttpUrl.Builder()
            .scheme("https")
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
                    "তুমি Rumin, ব্যবহারকারীর ব্যক্তিগত বাংলা AI voice agent। স্বাভাবিক, সংক্ষিপ্ত ও বন্ধুত্বপূর্ণভাবে বাংলায় কথা বলবে; ইংরেজিতে বললে ইংরেজিতে উত্তর দেবে। সাধারণ আলাপ, প্রশ্ন ও তথ্যের অনুরোধে স্বাভাবিকভাবে উত্তর দেবে। ব্যবহারকারী ফোনে কোনো কাজ করতে বললে অবশ্যই উপযুক্ত ঘোষিত function call করবে; শুধু মুখে কাজ করার প্রতিশ্রুতি দেবে না। প্রতিটি function call ব্যবহারকারীর অনুমতির জন্য প্রস্তাব মাত্র—অ্যাপ অনুমতি না পাওয়া পর্যন্ত কোনো কাজ চালাবে না। কখনো নিজে থেকে অন্য অ্যাপের মেসেজ পাঠানো, কল করা, পেমেন্ট, মুছে ফেলা, OTP/PIN/পাসওয়ার্ড ব্যবহার বা নিরাপত্তা এড়িয়ে যাওয়ার কাজ করবে না। function result না পাওয়া পর্যন্ত কাজ সফল হয়েছে বলবে না।"
                )
                val systemInstruction = JSONObject()
                    .put("parts", JSONArray().put(instructionPart))
                val generationConfig = JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put("speechConfig", speechConfig)
                val functionDeclarations = JSONArray()
                    .put(functionDeclaration("launch_app", "Open an installed Android app by its exact package name.", JSONObject()
                        .put("package_name", stringProperty("Installed Android package name, for example com.google.android.youtube.")), arrayOf("package_name")))
                    .put(functionDeclaration("read_visible_screen", "Read the text currently visible on the active phone screen.", JSONObject(), emptyArray()))
                    .put(functionDeclaration("click_visible_text", "Tap one unique visible button or label by its exact text.", JSONObject()
                        .put("label", stringProperty("Exact visible label to tap.")), arrayOf("label")))
                    .put(functionDeclaration("scroll_screen_forward", "Scroll the currently active phone screen forward once.", JSONObject(), emptyArray()))
                    .put(functionDeclaration("type_focused_text", "Type the supplied text into the currently focused editable field.", JSONObject()
                        .put("text", stringProperty("Exact text to enter into the focused field.")), arrayOf("text")))
                val setup = JSONObject()
                    .put("model", "models/$MODEL")
                    .put("generationConfig", generationConfig)
                    .put("inputAudioTranscription", JSONObject().put("languageCodes", JSONArray().put("bn-BD")))
                    .put("outputAudioTranscription", JSONObject().put("languageCodes", JSONArray().put("bn-BD")))
                    .put("systemInstruction", systemInstruction)
                    .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", functionDeclarations)))
                webSocket.send(JSONObject().put("setup", setup).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val root = JSONObject(text)
                    if (root.has("setupComplete")) {
                        setupReady = true
                        startPlayback()
                        startAudio()
                        onStatus("লাইভ কথা চালু — বলুন, বস")
                    }
                    if (root.has("error")) {
                        onError(root.getJSONObject("error").toString())
                    }
                    val toolCall = root.optJSONObject("toolCall")
                    val functionCalls = toolCall?.optJSONArray("functionCalls")
                    if (functionCalls != null && functionCalls.length() > 0) {
                        micPaused = true
                        awaitingToolResponse = true
                        val calls = (0 until functionCalls.length()).mapNotNull { callIndex ->
                            val item = functionCalls.optJSONObject(callIndex) ?: return@mapNotNull null
                            val args = item.optJSONObject("args") ?: JSONObject()
                            val id = item.optString("id")
                            val name = item.optString("name")
                            if (id.isBlank() || name.isBlank()) null else LiveToolCall(id, name, args)
                        }
                        if (calls.isNotEmpty()) onToolCall(calls)
                    }
                    val serverContent = root.optJSONObject("serverContent") ?: return
                    val inputText = serverContent.optJSONObject("inputTranscription")?.optString("text").orEmpty().trim()
                    if (inputText.isNotBlank()) pendingTranscript = inputText
                    if (serverContent.optBoolean("interrupted", false)) {
                        while (audioQueue.tryReceive().isSuccess) { }
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
                                audioQueue.trySend(bytes)
                            }
                        }
                    }
                    if (serverContent.optBoolean("turnComplete", false)) {
                        val spokenRequest = pendingTranscript.trim()
                        pendingTranscript = ""
                        if (spokenRequest.isNotBlank()) {
                            if (!awaitingToolResponse) onTranscript(spokenRequest)
                        } else if (!awaitingToolResponse) {
                            resumeListening()
                            onStatus("লাইভ চালু — আপনি কথা বলতে পারেন")
                        }
                    }
                } catch (t: Throwable) {
                    onError("Live response পড়া যায়নি: ${t.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!stopped.get()) {
                    val http = response?.let {
                        val body = runCatching { it.body?.string().orEmpty() }.getOrDefault("")
                        "HTTP ${it.code} ${it.message}. ${body.take(700)}"
                    }
                    val cause = generateSequence(t as Throwable?) { it.cause }
                        .take(4)
                        .joinToString(" → ") { err -> "${err.javaClass.simpleName}: ${err.message}" }
                    onError("Live সংযোগ ব্যর্থ। ${http ?: cause}")
                }
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
        // AudioRecord must be created and started off the WebSocket callback thread.
        captureJob = scope.launch(Dispatchers.IO) {
            var input: AudioRecord? = null
            try {
                val minInput = AudioRecord.getMinBufferSize(
                    INPUT_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                check(minInput > 0) { "এই ফোনে 16kHz মাইক্রোফোন অডিও শুরু করা যায়নি (buffer=$minInput)।" }
                input = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    INPUT_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    maxOf(minInput * 2, 8192)
                )
                check(input.state == AudioRecord.STATE_INITIALIZED) {
                    "মাইক্রোফোন AudioRecord initialize হয়নি। Android microphone permission পরীক্ষা করুন।"
                }
                recorder = input
                input.startRecording()
                check(input.recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    "Android মাইক্রোফোন recording শুরু করেনি।"
                }
                onStatus("লাইভ চালু — বলুন, বস")
                val buffer = ByteArray(2048)
                while (isActive && !stopped.get()) {
                    val count = input.read(buffer, 0, buffer.size)
                    if (count > 0 && !micPaused) {
                        val audio = Base64.encodeToString(buffer.copyOf(count), Base64.NO_WRAP)
                        val message = JSONObject().put(
                            "realtimeInput",
                            JSONObject().put("audio", JSONObject()
                                .put("mimeType", "audio/pcm;rate=16000")
                                .put("data", audio))
                        )
                        if (socket?.send(message.toString()) != true) {
                            throw IllegalStateException("Gemini Live WebSocket অডিও গ্রহণ করছে না।")
                        }
                    } else if (count < 0 && count != AudioRecord.ERROR_INVALID_OPERATION) {
                        throw IllegalStateException("মাইক্রোফোন read error: $count")
                    }
                }
            } catch (t: Throwable) {
                if (!stopped.get()) {
                    onError("মাইক্রোফোন চালু/পড়তে সমস্যা: ${t.message ?: t.javaClass.simpleName}")
                }
            } finally {
                if (recorder === input) recorder = null
                runCatching { input?.stop() }
                runCatching { input?.release() }
            }
        }
    }

    private fun startPlayback() {
        if (playbackJob != null) return
        playbackJob = scope.launch(Dispatchers.IO) {
            try {
                ensurePlayer()
                for (bytes in audioQueue) {
                    val track = player ?: break
                    var offset = 0
                    while (offset < bytes.size && isActive && !stopped.get()) {
                        val written = track.write(bytes, offset, bytes.size - offset)
                        if (written < 0) throw IllegalStateException("অডিও প্লেব্যাক error: $written")
                        if (written == 0) break
                        offset += written
                    }
                }
            } catch (t: Throwable) {
                if (!stopped.get()) onError("উত্তরের অডিও চালানো যায়নি: ${t.message}")
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

    private fun stringProperty(description: String): JSONObject =
        JSONObject().put("type", "STRING").put("description", description)

    private fun functionDeclaration(
        name: String,
        description: String,
        properties: JSONObject,
        required: Array<String>
    ): JSONObject {
        val parameters = JSONObject().put("type", "OBJECT").put("properties", properties)
        if (required.isNotEmpty()) parameters.put("required", JSONArray().apply { required.forEach { put(it) } })
        return JSONObject().put("name", name).put("description", description).put("parameters", parameters)
    }

    fun sendToolResponses(responses: JSONArray) {
        if (stopped.get() || socket == null || responses.length() == 0) return
        awaitingToolResponse = false
        socket?.send(JSONObject().put("toolResponse",
            JSONObject().put("functionResponses", responses)).toString())
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
        playbackJob?.cancel()
        playbackJob = null
        audioQueue.close()
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
