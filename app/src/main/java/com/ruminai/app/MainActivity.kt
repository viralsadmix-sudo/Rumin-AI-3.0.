package com.ruminai.app

import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import android.app.AlertDialog
import android.os.Bundle
import android.content.Intent
import android.Manifest
import android.content.pm.PackageManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import java.util.Locale
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.ruminai.app.core.ui.AnimatedAgentCoreView
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.ScrollView
import android.text.InputType
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import com.ruminai.app.core.agent.AgentRuntime
import com.ruminai.app.core.agent.AgentObserver
import com.ruminai.app.core.agent.AgentRun
import com.ruminai.app.core.agent.AgentRunStore
import com.ruminai.app.core.agent.GeminiAgentPlanner
import com.ruminai.app.core.agent.GeminiChatClient
import com.ruminai.app.core.agent.ToolRegistry
import com.ruminai.app.core.model.ExecutionStatus
import com.ruminai.app.core.security.AgentCredentialStore
import com.ruminai.app.core.phone.PhoneAccessibilityTools
import com.ruminai.app.core.memory.LocalMemoryStore
import com.ruminai.app.core.phone.PhoneAccessibilityService
import com.ruminai.app.core.voice.GeminiLiveVoiceSession

class MainActivity : ComponentActivity() {
    private lateinit var memoryStore: LocalMemoryStore
    private lateinit var memoryCount: TextView
    private lateinit var phoneControlStatus: TextView
    private lateinit var credentialStore: AgentCredentialStore
    private lateinit var agentRunStore: AgentRunStore
    private lateinit var taskInput: EditText
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var voiceStatus: TextView
    private lateinit var chatReplyView: TextView
    private var voicePending = false
    private var liveVoiceSession: GeminiLiveVoiceSession? = null
    private lateinit var liveVoiceButton: Button
    private var textToSpeech: TextToSpeech? = null
    private val micPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) { voicePending = false; startLiveVoice() } else { voicePending = false; showMessage("লাইভ ভয়েসের জন্য মাইক্রোফোন অনুমতি প্রয়োজন।") }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        memoryStore = LocalMemoryStore(this)
        credentialStore = AgentCredentialStore(this)
        agentRunStore = AgentRunStore(this)
        textToSpeech = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) textToSpeech?.language = Locale("bn", "BD")
        }
        renderHome()
    }

    override fun onResume() {
        super.onResume()
        if (::memoryCount.isInitialized) refreshMemoryCount()
        if (::phoneControlStatus.isInitialized) refreshPhoneControlStatus()
    }

    private fun renderHome() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 48, 28, 28)
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF0B1020.toInt())
        }

        val brand = TextView(this).apply {
            text = "RUMIN AI 3.0"
            textSize = 27f
            setTextColor(0xFFFF4FCB.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(brand, matchWidth())

        val avatar = AnimatedAgentCoreView(this)
        root.addView(avatar, LinearLayout.LayoutParams(260, 230).apply { topMargin = 12 })

        val greeting = TextView(this).apply {
            text = "আসসালামু আলাইকুম, বস!\nআমি রুমিন।"
            textSize = 22f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 12)
        }
        root.addView(greeting, matchWidth())

        val status = TextView(this).apply {
            text = "আপনার ব্যক্তিগত AI সহকারী। Chat, Voice এবং Agent আলাদা ভাবে ব্যবহার করুন।"
            textSize = 15f
            setTextColor(0xFFB9C4D8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        }
        root.addView(status, matchWidth())

        memoryCount = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF8DE1FF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 20)
        }
        root.addView(memoryCount, matchWidth())
        refreshMemoryCount()

        val memoryButton = Button(this).apply {
            text = "অনুমোদিত তথ্য মেমোরিতে রাখুন"
            setOnClickListener { showSaveMemoryDialog() }
        }
        root.addView(memoryButton, matchWidth())

        phoneControlStatus = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFFB9C4D8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 24, 0, 8)
        }
        root.addView(phoneControlStatus, matchWidth())
        refreshPhoneControlStatus()

        val phoneControlButton = Button(this).apply {
            text = "ফোন নিয়ন্ত্রণের অনুমতি সেট করুন"
            setOnClickListener { showAccessibilityDisclosure() }
        }
        root.addView(phoneControlButton, matchWidth())

        val screenReadButton = Button(this).apply {
            text = "বর্তমান স্ক্রিন পড়ে পরীক্ষা করুন"
            setOnClickListener { confirmAndReadScreen() }
        }
        root.addView(screenReadButton, matchWidth())

        val apiButton = Button(this).apply {
            text = "Gemini API Key সেট করুন"
            setOnClickListener { showGeminiKeyDialog() }
        }
        root.addView(apiButton, matchWidth())

        val apiTestButton = Button(this).apply {
            text = "Gemini API সংযোগ পরীক্ষা করুন"
            setOnClickListener { testGeminiConnection() }
        }
        root.addView(apiTestButton, matchWidth())

        taskInput = EditText(this).apply {
            hint = "রুমিনকে বাংলায় কাজ বলুন..."
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFF8995AA.toInt())
            setPadding(18, 14, 18, 14)
        }
        root.addView(taskInput, matchWidth())

        val chatButton = Button(this).apply {
            text = "চ্যাটে উত্তর পান"
            setOnClickListener { sendChatMessage() }
        }
        root.addView(chatButton, matchWidth())

        chatReplyView = TextView(this).apply {
            text = "রুমিনের উত্তর এখানে দেখা যাবে।"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(16, 16, 16, 16)
            setBackgroundColor(0xFF151D32.toInt())
        }
        root.addView(chatReplyView, matchWidth())

        voiceStatus = TextView(this).apply {
            text = "লাইভ ভয়েস: মাইক্রোফোন প্রস্তুত"
            textSize = 14f
            setTextColor(0xFF8DE1FF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 12, 0, 4)
        }
        root.addView(voiceStatus, matchWidth())
        liveVoiceButton = Button(this).apply {
            text = "🔴 Live Voice চালু করুন"
            setOnClickListener { toggleLiveVoice() }
        }
        root.addView(liveVoiceButton, matchWidth())

        val planButton = Button(this).apply {
            text = "Agent দিয়ে কাজের পরিকল্পনা করুন"
            setOnClickListener { createAgentPlan() }
        }
        root.addView(planButton, matchWidth())

        val info = TextView(this).apply {
            text = "পরবর্তী ধাপ: Agent Planner → অনুমতি → Tool Execution → যাচাই।\nতারপর নতুন Live Voice অডিও ইঞ্জিন।"
            textSize = 14f
            setTextColor(0xFFB9C4D8.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 0)
        }
        root.addView(info, matchWidth())
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(scroll)
    }

    private fun testGeminiConnection() {
        val key = credentialStore.readGeminiApiKey().orEmpty()
        if (key.isBlank()) {
            showMessage("Gemini API Key সংরক্ষিত নেই। আগে Key সেট করুন।")
            return
        }
        val progress = AlertDialog.Builder(this)
            .setMessage("Gemini API Key ও মডেল অ্যাক্সেস পরীক্ষা হচ্ছে…")
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch {
            val result = withTimeoutOrNull(20_000L) {
                com.ruminai.app.core.agent.GeminiConnectionDiagnostics(key).run()
            }
            progress.dismiss()
            val message = when {
                result == null -> "পরীক্ষা ২০ সেকেন্ডে শেষ হয়নি। নেটওয়ার্ক বা Google API-তে সাড়া পাওয়া যায়নি।"
                result.isSuccess -> result.getOrNull().orEmpty()
                else -> result.exceptionOrNull()?.message ?: "অজানা API সমস্যা"
            }
            showMessage(message)
        }
    }

    private fun sendChatMessage() {
        val message = taskInput.text.toString().trim()
        if (message.isBlank()) {
            showMessage("আগে মেসেজ লিখুন।")
            return
        }
        val client = GeminiChatClient({ credentialStore.readGeminiApiKey() })
        chatReplyView.text = "রুমিন উত্তর তৈরি করছে…"
        lifecycleScope.launch {
            val result = withTimeoutOrNull(27_000L) { client.reply(message) }
            chatReplyView.text = when {
                result == null -> "উত্তর আসতে দেরি হচ্ছে। ইন্টারনেট বা Gemini API সংযোগ পরীক্ষা করে আবার চেষ্টা করুন।"
                result.isSuccess -> result.getOrNull().orEmpty().also { answer -> textToSpeech?.speak(answer, TextToSpeech.QUEUE_FLUSH, null, "rumin-chat-answer") }
                else -> "উত্তর পাওয়া যায়নি: ${result.exceptionOrNull()?.message ?: "অজানা সমস্যা"}"
            }
        }
    }

    private fun toggleLiveVoice() {
        if (liveVoiceSession != null) {
            liveVoiceSession?.stop()
            liveVoiceSession = null
            liveVoiceButton.text = "🔴 Live Voice চালু করুন"
            voiceStatus.text = "লাইভ ভয়েস বন্ধ"
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            voicePending = true
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startLiveVoice()
    }

    private fun startLiveVoice() {
        voicePending = false
        val key = credentialStore.readGeminiApiKey().orEmpty()
        if (key.isBlank()) {
            showMessage("Live Voice চালু করতে আগে Gemini API Key সেট করুন।")
            return
        }
        liveVoiceButton.isEnabled = false
        liveVoiceButton.text = "সংযোগ হচ্ছে…"
        val session = GeminiLiveVoiceSession(
            apiKey = key,
            scope = lifecycleScope,
            onStatus = { status ->
                runOnUiThread {
                    voiceStatus.text = status
                    liveVoiceButton.text = if (status.contains("চালু") || status.contains("বলুন")) "⏹ Live Voice বন্ধ করুন" else "Live Voice: সংযোগ হচ্ছে…"
                    liveVoiceButton.isEnabled = true
                }
            },
            onTranscript = { transcript ->
                runOnUiThread {
                    taskInput.setText(transcript)
                    voiceStatus.text = "আপনি বলেছেন: $transcript — AI Agent-এ পাঠানো হচ্ছে"
                    createAgentPlan()
                }
            },
            onError = { error ->
                runOnUiThread {
                    voiceStatus.text = error
                    liveVoiceButton.text = "🔴 Live Voice চালু করুন"
                    liveVoiceButton.isEnabled = true
                    liveVoiceSession = null
                    showMessage("Live Voice সমস্যা: $error")
                }
            }
        )
        liveVoiceSession = session
        session.start()
    }

    private fun sendLiveAgentFeedback(message: String) {
        if (message.isNotBlank()) {
            liveVoiceSession?.sendAgentReply(message)
        }
    }

    override fun onDestroy() {
        speechRecognizer?.destroy()
        speechRecognizer = null
        liveVoiceSession?.stop()
        liveVoiceSession = null
        textToSpeech?.stop()
        textToSpeech?.shutdown()
        textToSpeech = null
        super.onDestroy()
    }

    private fun speak(message: String) {
        if (message.isNotBlank()) {
            textToSpeech?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "rumin-agent-voice")
        }
    }

    private fun showSaveMemoryDialog() {
        val field = EditText(this).apply { hint = "যে তথ্যটি মনে রাখতে বলছেন লিখুন" }
        AlertDialog.Builder(this)
            .setTitle("নতুন মেমোরি")
            .setMessage("শুধু আপনার অনুমোদিত তথ্য এই ফোনে সংরক্ষণ হবে।")
            .setView(field)
            .setNegativeButton("বাতিল", null)
            .setPositiveButton("সংরক্ষণ") { _, _ ->
                val value = field.text.toString().trim()
                if (value.isNotEmpty()) {
                    memoryStore.addApprovedFact(value)
                    refreshMemoryCount()
                }
            }
            .show()
    }




    private fun showGeminiKeyDialog() {
        val field = EditText(this).apply {
            hint = "Gemini API Key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(credentialStore.readGeminiApiKey().orEmpty())
        }
        AlertDialog.Builder(this)
            .setTitle("Gemini সংযোগ")
            .setMessage("API Key এই ফোনে Android Keystore-ভিত্তিক encrypted storage-এ রাখা হবে। GitHub-এ পাঠানো হবে না।")
            .setView(field)
            .setNegativeButton("বাতিল", null)
            .setNeutralButton("Key মুছুন") { _, _ ->
                credentialStore.clearGeminiApiKey()
            }
            .setPositiveButton("নিরাপদে সংরক্ষণ") { _, _ ->
                val key = field.text.toString().trim()
                if (key.isNotEmpty()) {
                    runCatching { credentialStore.saveGeminiApiKey(key) }
                        .onFailure { showMessage("Key সংরক্ষণ হয়নি: ${it.message}") }
                        .onSuccess { showMessage("Gemini API Key এই ফোনে সংরক্ষিত হয়েছে।") }
                }
            }
            .show()
    }

    private fun createAgentPlan() {
        val request = taskInput.text.toString().trim()
        if (request.isBlank()) {
            showMessage("আগে রুমিনকে একটি কাজ লিখে দিন।")
            return
        }
        if (credentialStore.readGeminiApiKey().isNullOrBlank()) {
            showMessage("আগে Gemini API Key সেট করুন।")
            return
        }
        val registry = ToolRegistry(PhoneAccessibilityTools().asAgentTools())
        val planner = GeminiAgentPlanner({ credentialStore.readGeminiApiKey() }, registry) { memoryStore.readAll().map { it.text } }
        val runtime = AgentRuntime(planner, registry, observer = object : AgentObserver {
            override fun onRunUpdated(run: AgentRun) { agentRunStore.save(run) }
        })
        val progress = AlertDialog.Builder(this)
            .setMessage("রুমিন কাজটি বুঝে পরিকল্পনা তৈরি করছে... সর্বোচ্চ ২৫ সেকেন্ড অপেক্ষা করুন।")
            .setCancelable(false)
            .create()
        progress.show()

        lifecycleScope.launch {
            val prepared = withTimeoutOrNull(25_000L) { runtime.prepare(request) }
            progress.dismiss()
            if (prepared == null) {
                showMessage("২৫ সেকেন্ডেও Gemini থেকে উত্তর আসেনি। ইন্টারনেট, API Key এবং Google AI Studio-তে মডেল অ্যাক্সেস পরীক্ষা করুন।")
                return@launch
            }
            prepared.onFailure { val msg = "পরিকল্পনা তৈরি হয়নি: ${it.message}"; sendLiveAgentFeedback(msg); showMessage(msg) }
            prepared.onSuccess { run ->
                if (run.plan.steps.isEmpty()) {
                    val msg = "এই অনুরোধের জন্য অনুমোদিত Tool পাওয়া যায়নি। এখনো কোনো কাজ করা হয়নি।"
                    sendLiveAgentFeedback(msg)
                    showMessage(msg)
                    return@onSuccess
                }
                sendLiveAgentFeedback("আপনার কাজের পরিকল্পনা তৈরি করেছি। স্ক্রিনে অনুমতি চাইছি।")
                val planText = run.plan.steps.mapIndexed { index, step ->
                    "${index + 1}. ${step.toolName}\n${step.description}\nঅনুমতি: প্রয়োজন"
                }.joinToString("\n\n")
                AlertDialog.Builder(this@MainActivity)
                    .setTitle("রুমিনের কাজের পরিকল্পনা")
                    .setMessage("অনুরোধ: ${run.plan.userRequest}\n\n$planText\n\nপ্রতিটি ধাপ চালানোর অনুমতি দিচ্ছেন?")
                    .setNegativeButton("এখন নয়", null)
                    .setPositiveButton("সব ধাপ অনুমোদন ও চালান") { _, _ ->
                        sendLiveAgentFeedback("আপনার অনুমোদন পেয়েছি। কাজ শুরু করছি।")
                        executeApprovedPlan(runtime, run)
                    }
                    .show()
            }
        }
    }

    private fun executeApprovedPlan(runtime: AgentRuntime, run: com.ruminai.app.core.agent.AgentRun) {
        val needsPhoneControl = run.steps.any { it.step.toolName.startsWith("phone.") }
        if (needsPhoneControl && !PhoneAccessibilityService.isEnabledInProcess()) {
            showMessage("এই পরিকল্পনায় ফোন নিয়ন্ত্রণ দরকার। আগে Rumin AI Phone Control Accessibility চালু করুন, তারপর আবার চেষ্টা করুন।")
            return
        }
        val progress = AlertDialog.Builder(this)
            .setMessage("রুমিন অনুমোদিত ধাপগুলো চালাচ্ছে...")
            .setCancelable(false)
            .create()
        progress.show()
        lifecycleScope.launch {
            val result = runCatching {
                runtime.execute(run, run.steps.map { it.step.id }.toSet())
            }
            progress.dismiss()
            result.onFailure { val msg = "কাজ চালানো যায়নি: ${it.message}"; sendLiveAgentFeedback(msg); showMessage(msg) }
            result.onSuccess { completed ->
                val detail = completed.steps.joinToString("\n\n") { step ->
                    "${step.step.toolName}: ${step.status}\n${step.output ?: step.error.orEmpty()}"
                }
                if (completed.status == ExecutionStatus.VERIFYING) {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle("বাস্তব ফলাফল যাচাই করুন")
                        .setMessage("$detail\n\nRumin Tool চালিয়েছে, কিন্তু কাজটি বাস্তবে সফল হয়েছে কি না আপনিই যাচাই করুন।")
                        .setNegativeButton("সফল হয়নি") { _, _ ->
                            val verified = runtime.markVerified(completed, false, "ব্যবহারকারী ফলাফল নিশ্চিত করেননি।")
                            showMessage(verified.summary.orEmpty())
                        }
                        .setPositiveButton("আমি যাচাই করেছি—সফল") { _, _ ->
                            val verified = runtime.markVerified(completed, true, "ব্যবহারকারী ফলাফল যাচাই করেছেন।")
                            showMessage(verified.summary.orEmpty())
                        }
                        .show()
                } else {
                    speak(completed.summary.orEmpty().ifBlank { "কাজের ধাপ শেষ হয়েছে।" })
                    showMessage("${completed.summary.orEmpty()}\n$detail")
                }
            }
        }
    }

    private fun showMessage(message: String) {
        AlertDialog.Builder(this)
            .setMessage(message)
            .setPositiveButton("ঠিক আছে", null)
            .show()
    }

    private fun confirmAndReadScreen() {
        AlertDialog.Builder(this)
            .setTitle("স্ক্রিন পড়ার অনুমতি")
            .setMessage("Rumin শুধু এখনকার দৃশ্যমান স্ক্রিনের লেখা এই ফোনেই পড়বে। কোনো তথ্য সার্ভারে পাঠানো বা সংরক্ষণ করা হবে না। চালাবেন?")
            .setNegativeButton("বাতিল", null)
            .setPositiveButton("পড়ুন") { _, _ ->
                val service = PhoneAccessibilityService.current()
                if (service == null) {
                    AlertDialog.Builder(this)
                        .setMessage("Accessibility Service এখনো সংযুক্ত নয়। Android Settings-এ অনুমতি চালু আছে কি না দেখুন।")
                        .setPositiveButton("ঠিক আছে", null)
                        .show()
                } else {
                    val screenText = runCatching { service.readVisibleText() }
                        .getOrElse { "স্ক্রিন পড়তে সমস্যা হয়েছে: ${it.message ?: "অজানা সমস্যা"}" }
                        .ifBlank { "বর্তমান স্ক্রিনে পড়ার মতো লেখা পাওয়া যায়নি।" }
                    AlertDialog.Builder(this)
                        .setTitle("স্ক্রিন পড়ার ফলাফল")
                        .setMessage(screenText)
                        .setPositiveButton("বন্ধ", null)
                        .show()
                }
            }
            .show()
    }

    private fun showAccessibilityDisclosure() {
        AlertDialog.Builder(this)
            .setTitle("Rumin AI ফোন নিয়ন্ত্রণ")
            .setMessage(
                "এই ঐচ্ছিক সুবিধা চালু করলে, আপনার নির্দেশে Rumin দৃশ্যমান স্ক্রিনের লেখা পড়তে, " +
                    "নির্দিষ্ট লেখা ট্যাপ করতে, ফোকাস করা ফিল্ডে টাইপ করতে এবং স্ক্রল করতে পারবে। " +
                    "এখন কোনো স্ক্রিন ডেটা ব্যাকগ্রাউন্ডে সংরক্ষণ বা পাঠানো হয় না। " +
                    "প্রতিটি ফোন-নিয়ন্ত্রণ কাজের আগে অ্যাপের ভেতরে আপনার অনুমতি নিতে হবে। " +
                    "আপনি চাইলে Android Settings থেকে যেকোনো সময় এটি বন্ধ করতে পারবেন।"
            )
            .setNegativeButton("এখন নয়", null)
            .setPositiveButton("Android Settings খুলুন") { _, _ ->
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
            .show()
    }

    private fun refreshPhoneControlStatus() {
        phoneControlStatus.text = if (PhoneAccessibilityService.isEnabledInProcess()) {
            "ফোন নিয়ন্ত্রণ: Accessibility Service সংযুক্ত"
        } else {
            "ফোন নিয়ন্ত্রণ: এখনো চালু/সংযুক্ত নয়"
        }
    }

    private fun refreshMemoryCount() {
        memoryCount.text = "সংরক্ষিত মেমোরি: ${memoryStore.readAll().size}"
    }

    private fun matchWidth() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    )
}
