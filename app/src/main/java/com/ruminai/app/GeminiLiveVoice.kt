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
    private val onTurn: (String, String, Boolean) -> Unit,
    private val onToolCall: (String, String, JSONObject) -> Unit
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
    @Volatile private var turnHadAudio = false
    @Volatile private var assistantSpeaking = false

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
                        .put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().put(
                            JSONObject()
                                .put("name", "start_agent_task")
                                .put("description", "বসের ফোন-সংক্রান্ত লক্ষ্য বুঝে Rumin AI Agent Planner চালাও। Planner স্ক্রিন পড়ে, ধাপে ধাপে native action চালিয়ে ফল যাচাই করবে।")
                                .put("parameters", JSONObject()
                                    .put("type", "OBJECT")
                                    .put("properties", JSONObject()
                                        .put("goal", JSONObject().put("type", "STRING").put("description", "বসের সম্পূর্ণ স্বাভাবিক ভাষার নির্দেশ"))
                                    )
                                    .put("required", JSONArray().put("goal"))
                                )
                        )
                    )))
                        .put("generationConfig", JSONObject()
                            .put("responseModalities", JSONArray().put("AUDIO"))
                            .put("speechConfig", JSONObject().put("voiceConfig",
                                JSONObject().put("prebuiltVoiceConfig", JSONObject().put("voiceName", "Kore")))))
                        .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text",
                            "তুমি Rumin AI 3.0 — বসের ব্যক্তিগত বাংলা AI সহকারী। তোমাকে তৈরি ও ডেভেলপ করেছেন সাব্বির আহমেদ রুবেল (Sabbir Ahmed Rubel); কেউ তোমার নির্মাতা/ডেভেলপার/কে বানিয়েছে জিজ্ঞেস করলে স্পষ্টভাবে এই উত্তর দেবে।\nতুমি শুধু নির্দিষ্ট ফোন-কমান্ডের যন্ত্র নও। বসের যেকোনো স্বাভাবিক প্রশ্ন, সাধারণ জ্ঞান, ব্যাখ্যা, মতামত, আলাপ, পরিকল্পনা, লেখা বা কথোপকথনের অনুরোধে স্বাভাবিকভাবে উত্তর দেবে। ফোনের কাজ না হলে কখনো বলবে না যে তুমি শুধু তালিকাভুক্ত কমান্ড করতে পারো। কোনো command তালিকার বাইরের সাধারণ প্রশ্নও এড়িয়ে যাবে না।\nবস ফোনে কোনো কাজ করতে বললে নিজে পরিকল্পনাকারীর মতো ধাপে ধাপে কাজ করবে। ফোনে কাজ করতে হলে start_agent_task function call করে বিদ্যমান Rumin AI Agent Planner-কে লক্ষ্য দেবে; Planner প্রথমে inspect_screen, তারপর দৃশ্যমান label অনুযায়ী native action চালিয়ে প্রতিটি ফল যাচাই করবে। একটি tool call-এর ফল না পাওয়া পর্যন্ত পরের ধাপ অনুমান করবে না। সাধারণ আলাপ/প্রশ্নে tool ডাকবে না। কলের জন্য শুধু ডায়ালার খুলবে, SMS-এর জন্য শুধু compose screen খুলবে; ব্যবহারকারী নিজে শেষবার যাচাই করে কল/Send করবেন। কোনো কাজের জন্য প্রয়োজনীয় Native capability না থাকলে কোন অংশটি করা যাচ্ছে না তা সৎভাবে বলবে এবং নিরাপদ বিকল্প জানাবে—কিন্তু সাধারণ কথোপকথন চালিয়ে যাবে। কল করা, SMS/মেসেজ পাঠানো, পেমেন্ট, পোস্ট/শেয়ার, ডিলিট বা নিরাপত্তা/গোপনীয়তা বদলানোর আগে স্পষ্ট অনুমতি নেবে।\nস্বাভাবিক, আন্তরিক বাংলাদেশি বাংলায় কথা বলবে; বসকে “বস” বলে সম্বোধন করবে। বসের কথা কথ্য, অসম্পূর্ণ, আঞ্চলিক বা ভুল উচ্চারণে হলেও পুরো কথোপকথনের প্রসঙ্গ থেকে উদ্দেশ্য ধরবে; কোনো নির্দিষ্ট command phrase বা exact keyword চাইবে না। আগের turn-এর বিষয়, ব্যক্তি/বস্তু নির্দেশ, পছন্দ এবং অসমাপ্ত কাজ মনে রেখে উত্তর দেবে। বসের কণ্ঠের শব্দমাত্র নয়—বলা কথার ভাষা ও প্রসঙ্গ থেকে সম্ভাব্য আবেগ (খুশি, বিরক্তি, দুঃখ, উদ্বেগ, উত্তেজনা, ক্লান্তি) বুঝে সহানুভূতিশীলভাবে সাড়া দেবে; অনুমানকে নিশ্চিত সত্য বলবে না, প্রয়োজনে জিজ্ঞেস করবে। প্রাসঙ্গিক হলে নিজে থেকে পরের প্রশ্ন করবে বা সাহায্যের প্রস্তাব দেবে, কথোপকথনকে স্বাভাবিকভাবে এগিয়ে নেবে; অকারণে বারবার কথা কেটে দেবে না বা নীরবতাকে অনুমতি ধরে কোনো ফোনের কাজ করবে না। ফোনে যেকোনো কাজের অনুরোধ—এক ধাপের হলেও—start_agent_task function call করে Agent Planner-এ পাঠাবে; সাধারণ আলাপ, আবেগ প্রকাশ বা প্রশ্নকে ভুল করে ফোনের কাজ ভাববে না। সরাসরি Gemini Live অডিওতেই উত্তর দেবে, আলাদা Speech-to-Text/Text-to-Speech পদ্ধতিতে স্যুইচ করবে না। বস মাঝখানে কথা বললে থেমে শুনবে। শুধু নীরবতায় উত্তর তৈরি করবে না। নিজেকে ChatGPT বলে পরিচয় দেবে না।"))))
                        .put("inputAudioTranscription", JSONObject())
                        .put("outputAudioTranscription", JSONObject())
                        .put("realtimeInputConfig", JSONObject()
                            .put("automaticActivityDetection", JSONObject()
                                .put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                                .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                                .put("silenceDurationMs", 700))
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
                        val toolCall = root.optJSONObject("toolCall")
                        val functionCalls = toolCall?.optJSONArray("functionCalls")
                        if (functionCalls != null) {
                            for (i in 0 until functionCalls.length()) {
                                val call = functionCalls.optJSONObject(i) ?: continue
                                val callId = call.optString("id")
                                val functionName = call.optString("name")
                                val args = call.optJSONObject("args") ?: JSONObject()
                                if (callId.isNotBlank() && functionName == "start_agent_task") {
                                    onStatus("রুমিন ফোনের কাজ পরিকল্পনা করছে…", "thinking")
                                    onToolCall(callId, functionName, args)
                                } else if (callId.isNotBlank()) {
                                    sendToolResponse(callId, functionName, JSONObject().put("ok", false).put("message", "এই tool সমর্থিত নয়।"))
                                }
                            }
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
                                    assistantSpeaking = true
                                    playbackQueue.put(Base64.decode(encoded, Base64.DEFAULT))
                                    turnHadAudio = true
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
                            val audioReceived = turnHadAudio
                            turnHadAudio = false
                            if (userText.isNotBlank() || assistantText.isNotBlank()) onTurn(userText, assistantText, audioReceived)
                            if (assistantText.isBlank() && !audioReceived && userText.isNotBlank()) onStatus("আপনার কথা শনাক্ত হয়েছে, কিন্তু Gemini উত্তর পাঠায়নি। আবার একটু স্পষ্ট করে বলুন বা API Key/মডেল অ্যাক্সেস পরীক্ষা করুন।", "listening")
                            else if (assistantText.isNotBlank() && !audioReceived) onStatus("উত্তরের লেখা এসেছে; কণ্ঠের অডিও আসেনি—ফোনের কণ্ঠে বলছি…", "speaking")
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

    fun sendToolResponse(callId: String, functionName: String, response: JSONObject): Boolean {
        if (!active.get() || !setupDone || callId.isBlank()) return false
        val functionResponse = JSONObject()
            .put("id", callId)
            .put("name", functionName)
            .put("response", response)
        val payload = JSONObject().put("toolResponse",
            JSONObject().put("functionResponses", JSONArray().put(functionResponse)))
        return socket?.send(payload.toString()) == true
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
                            assistantSpeaking = false
                            onStatus("বস, আমি শুনছি—আবার বলুন", "listening")
                            continue
                        }
                        val track = player
                        if (track != null) {
                            var offset = 0
                            while (offset < chunk.size && active.get()) {
                                val written = track.write(chunk, offset, chunk.size - offset)
                                if (written <= 0) break
                                offset += written
                            }
                        }
                    } catch (_: InterruptedException) { break }
                    catch (_: Exception) { if (active.get()) onStatus("অডিও চালাতে সমস্যা হয়েছে", "listening") }
                }
            }.start()

            val min = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) throw IllegalStateException("ফোনের মাইক্রোফোন চালুর উপযোগী buffer পাওয়া যায়নি (minBuffer=$min)")
            val bufferSize = maxOf(min * 2, 12800)
            // Restore the known-working Build 99 microphone route: standard MIC source.
            // Build 100's VOICE_RECOGNITION-first selection could initialize successfully
            // while returning silent PCM on this device, so do not prefer it automatically.
            val sourceUsed = MediaRecorder.AudioSource.MIC
            val activeRecorder = AudioRecord(sourceUsed, 16000, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize)
            if (activeRecorder.state != AudioRecord.STATE_INITIALIZED) {
                activeRecorder.release()
                throw IllegalStateException("ফোনের MIC অডিও সোর্স চালু হয়নি।")
            }
            activeRecorder.startRecording()
            if (activeRecorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                activeRecorder.release()
                throw IllegalStateException("ফোনের MIC রেকর্ডিং শুরু হয়নি।")
            }
            recorder = activeRecorder
            onStatus("ফোনের মাইক্রোফোন চালু হয়েছে (source=$sourceUsed); অডিও পরীক্ষা করছি…", "connecting")
            // Reduce speaker-to-microphone feedback so Rumin does not hear its own voice as user speech.
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(activeRecorder.audioSessionId)?.apply { enabled = true }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(activeRecorder.audioSessionId)?.apply { enabled = true }
            }
            Thread {
                val buffer = ByteArray(640) // 20 ms of mono PCM16 at 16 kHz
                var lastDiagnosticAt = System.currentTimeMillis()
                var totalBytes = 0L
                var latestRms = 0
                var quietReads = 0
                var zeroReads = 0
                while (active.get()) {
                    val count = try { activeRecorder.read(buffer, 0, buffer.size) } catch (e: Exception) {
                        if (active.get()) onStatus("মাইক্রোফোন পড়তে সমস্যা: ${e.message ?: "অজানা ত্রুটি"}", "stopped")
                        -1
                    }
                    if (count > 0 && active.get() && setupDone) {
                        // Measure real microphone signal so a silent AudioRecord is not mistaken for a working voice session.
                        var sumSquares = 0.0
                        var samples = 0
                        var i = 0
                        while (i + 1 < count) {
                            val sample = ((buffer[i + 1].toInt() shl 8) or (buffer[i].toInt() and 0xff)).toShort().toInt()
                            sumSquares += sample.toDouble() * sample.toDouble()
                            samples++
                            i += 2
                        }
                        latestRms = if (samples > 0) kotlin.math.sqrt(sumSquares / samples).toInt() else 0
                        if (latestRms < 80) quietReads++ else quietReads = 0
                        zeroReads = 0
                        totalBytes += count.toLong()

                        // Do not feed the assistant speaker output back into the Live session as user speech.
                        // Send same-duration silence while a response is playing, preventing echo-triggered
                        // interruptions and repeated partial responses with apparent voice changes.
                        // Prevent speaker echo from being re-submitted as a new user turn while Rumin is speaking.
                        // Keep sending same-duration silence so the Live input stream stays continuous.
                        val sourceBytes = if (assistantSpeaking) ByteArray(count) else buffer.copyOf(count)
                        val blob = JSONObject().put("mimeType", "audio/pcm;rate=16000")
                            .put("data", Base64.encodeToString(sourceBytes, Base64.NO_WRAP))
                        val ws = socket
                        val sent = try {
                            ws != null && ws.send(JSONObject().put("realtimeInput", JSONObject().put("audio", blob)).toString())
                        } catch (_: Exception) { false }
                        if (!sent) {
                            onStatus("মাইক্রোফোন অডিও Gemini-তে পাঠানো যাচ্ছে না—WebSocket সংযোগ পরীক্ষা করুন।", "stopped")
                            break
                        }

                        val now = System.currentTimeMillis()
                        if (now - lastDiagnosticAt >= 2500) {
                            val signal = when {
                                latestRms < 80 -> "খুব কম/নীরব"
                                latestRms < 500 -> "কম"
                                else -> "পাওয়া যাচ্ছে"
                            }
                            val seconds = totalBytes / 32000.0
                            val serverHint = if (lastServerEvent.contains("voiceActivity", true) ||
                                lastServerEvent.contains("serverContent", true)) "সার্ভারের ইভেন্ট এসেছে" else "সার্ভারের কথা-শনাক্তকরণ এখনও আসেনি"
                            val quietHint = if (quietReads > 80) " ফোনের মাইক/অডিও সোর্স পরীক্ষা করুন।" else ""
                            onStatus("মাইক পরীক্ষা: শব্দ $signal (RMS $latestRms); পাঠানো অডিও ${"%.1f".format(java.util.Locale.US, seconds)} সেকেন্ড; $serverHint.$quietHint", "listening")
                            lastDiagnosticAt = now
                        }
                    } else if (count == 0) {
                        zeroReads++
                        val now = System.currentTimeMillis()
                        if (now - lastDiagnosticAt >= 2500) {
                            val hint = if (zeroReads > 100) {
                                val am = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                                if (am.isMicrophoneMute) "Android-এ microphone mute চালু আছে। Quick Settings থেকে Mic access চালু করুন।"
                                else "অডিও সোর্স চালু দেখালেও ডেটা আসছে না। Android Quick Settings-এর Mic access, অন্য অ্যাপের microphone use, এবং ফোন restart পরীক্ষা করুন।"
                            } else "মাইক্রোফোনের ডেটা আসার অপেক্ষায়…"
                            onStatus("মাইক পরীক্ষা: নতুন অডিও আসছে না। মোট পাঠানো অডিও " + String.format(java.util.Locale.US, "%.1f", totalBytes / 32000.0) + " সেকেন্ড। " + hint, "listening")
                            lastDiagnosticAt = now
                        }
                        try { Thread.sleep(10) } catch (_: InterruptedException) { break }
                    } else if (count < 0) {
                        if (active.get()) onStatus("মাইক্রোফোন থেকে অডিও পড়া বন্ধ হয়েছে (read=$count)।", "stopped")
                        break
                    }
                }
            }.start()
        } catch (e: Exception) {
            onStatus("মাইক্রোফোন চালু হয়নি: " + (e.message ?: "অনুমতি/ডিভাইস পরীক্ষা করুন"), "stopped")
            stop()
        }
    }

    fun stop() {
        if (!active.compareAndSet(true, false)) return

        // Stop the WebSocket promptly, but never release audio devices or shut down
        // OkHttp on the Android/WebView UI thread. AudioRecord/AudioTrack cleanup
        // can block on some devices and otherwise freezes every app button.
        val ws = socket
        socket = null
        try { ws?.cancel() } catch (_: Exception) {}

        playbackQueue.clear()
        playbackQueue.offer(stopMarker)

        Thread({
            try { recorder?.stop() } catch (_: Exception) {}
            try { recorder?.release() } catch (_: Exception) {}
            recorder = null
            try { echoCanceler?.release() } catch (_: Exception) {}
            echoCanceler = null
            try { noiseSuppressor?.release() } catch (_: Exception) {}
            noiseSuppressor = null
            try { player?.pause(); player?.flush(); player?.stop() } catch (_: Exception) {}
            try { player?.release() } catch (_: Exception) {}
            player = null
            try { client.dispatcher.cancelAll() } catch (_: Exception) {}
            try { client.dispatcher.executorService.shutdown() } catch (_: Exception) {}
            try { client.connectionPool.evictAll() } catch (_: Exception) {}
        }, "Rumin-LiveVoice-Stop").start()
    }
}
