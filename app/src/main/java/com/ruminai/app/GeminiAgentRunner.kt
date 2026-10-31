package com.ruminai.app

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.Settings
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONObject

/** Gemini tool loop with an allow-listed, policy-checked Android execution layer. */
internal class GeminiAgentRunner(
    private val activity: MainActivity,
    private val agentCore: AgentCore
) {
    private val taskIds = AtomicLong(System.currentTimeMillis())
    @Volatile private var nativeActionAttempted = false

    internal fun hasNativeActionAttempted(): Boolean = nativeActionAttempted

    internal fun openAiToolDefinitions(): JSONArray {
        val result = JSONArray()
        for (i in 0 until tools.length()) {
            val source = tools.optJSONObject(i) ?: continue
            val function = JSONObject()
                .put("name", source.optString("name"))
                .put("description", source.optString("description"))
                .put("parameters", source.optJSONObject("parameters") ?: JSONObject())
            result.put(JSONObject().put("type", "function").put("function", function))
        }
        return result
    }

    private val tools = JSONArray().apply {
        put(tool("open_app", "Open one allow-listed app only when the user asks. This does not perform actions inside that app.",
            schema("app" to enum("App identifier", "youtube", "facebook", "whatsapp", "instagram", "gmail", "maps", "camera", "settings", "browser", "playstore", "github"), required = listOf("app"))))
        put(tool("inspect_screen", "Read visible screen text only. Never click or type. Sensitive-looking values are redacted.", schema()))
        put(tool("phone_navigation", "Perform one basic navigation action when explicitly requested. Requires RUMIN Accessibility service.",
            schema("action" to enum("Navigation action", "back", "home", "recents", "notifications", "quicksettings"), required = listOf("action"))))
        put(tool("adjust_volume", "Adjust media volume one step, mute, or unmute when explicitly requested.",
            schema("action" to enum("Volume action", "up", "down", "mute", "unmute"), required = listOf("action"))))
        put(tool("open_settings", "Open an Android settings page. Opening a page does not grant a permission or change a setting.",
            schema("page" to enum("Settings page", "system", "wifi", "bluetooth", "display", "sound", "accessibility", "notification_access", "app_permissions", "write_settings", "text_to_speech"), required = listOf("page"))))
        put(tool("create_alarm", "Open Clock with an alarm time prefilled. User must confirm/save it; do not claim it is saved.",
            schema("hour" to integer("Hour 0-23"), "minute" to integer("Minute 0-59"), "label" to string("Optional label"), required = listOf("hour", "minute"))))
        put(tool("create_timer", "Open Clock with a timer prefilled. User may need to confirm/start it.",
            schema("seconds" to integer("Duration 1-86400 seconds"), "label" to string("Optional label"), required = listOf("seconds"))))
        put(tool("open_dialer", "Open the phone dialer, optionally with a number filled in. Never place a call.",
            schema("phone_number" to string("Optional phone number"))))
        put(tool("compose_sms_draft", "Open an SMS draft addressed to the supplied number. Never send it; user must review and press Send.",
            schema("phone_number" to string("Recipient phone number"), "message" to string("Draft message, max 1000 characters"), required = listOf("phone_number", "message"))))
    }

    init { recoverInterruptedTask() }

    fun run(apiKey: String, prompt: String): String {
        nativeActionAttempted = false
        val safePrompt = """
            তুমি RUMIN AI 3.0-এর Agent brain; ব্যবহারকারীকে 'বস' বলবে এবং স্বাভাবিক বাংলাদেশি বাংলায় উত্তর দেবে।
            কাজের আগে উদ্দেশ্য বোঝো। নির্দেশ অস্পষ্ট বা গুরুত্বপূর্ণ তথ্য অনুপস্থিত হলে একটি স্পষ্ট প্রশ্ন করো।
            কাজটি স্পষ্ট ও সমর্থিত হলে ঘোষিত Function Tool নির্বাচন করো; Native layer তা যাচাই করে চালায়।
            শুধু Tool result-এর প্রমাণ অনুযায়ী কাজের অবস্থা জানাবে। activity_launch_accepted মানে Android অ্যাপ খোলার অনুরোধ গ্রহণ করেছে, অ্যাপের ভেতরের লক্ষ্য পূরণ নয়।
            alarm_ui_opened বা timer_ui_opened মানে Clock UI খোলা হয়েছে; বস নিশ্চিত না করা পর্যন্ত সেট হয়েছে বলবে না।
            dialer_opened মানে শুধু ডায়ালার খোলা; কল করা হয়নি। sms_draft_opened মানে খসড়া খোলা; SMS পাঠানো হয়নি।
            system_intent_dispatched মানে Settings পেজ খোলা; অনুমতি granted হয়েছে এমন দাবি করবে না।
            outcome_unknown পেলে একই কাজ আবার চালাবে না; বসকে ফোনে ফলাফল পরীক্ষা করতে বলবে।
            inspect_screen শুধু দৃশ্যমান লেখা পড়ে; সংবেদনশীল অংশ আড়াল করা হয়। স্ক্রিনে যা নেই তা অনুমান করবে না।
            generic tap/type, arbitrary shell/code/URL, payment, deletion, permission grant, call বা message send করার চেষ্টা করবে না।
            Tool নেই বা অনুমতি নেই হলে সীমাবদ্ধতা ও সেটি চালু করার পথ স্পষ্ট করে বলবে।
            আইডিয়া চাইলে বাস্তবসম্মত বিকল্প, সুবিধা-অসুবিধা ও পরবর্তী পদক্ষেপ দেবে; অনুমানকে তথ্য হিসেবে বলবে না।
            ভেতরে পরিকল্পনা করো, কিন্তু গোপন chain-of-thought প্রকাশ করবে না; দরকার হলে সংক্ষিপ্ত কারণ ও ফলাফল বলবে।
            লগে শুধু action/state থাকবে; ফোন নম্বর, মেসেজ, স্ক্রিনের লেখা বা API key সংরক্ষণ করবে না।
            
            ব্যবহারকারীর অনুরোধ ও কথোপকথনের প্রেক্ষাপট:
            ${prompt.replace("\\n", "\n")}
        """.trimIndent()

        val input = JSONArray().put(JSONObject().put("type", "user_input")
            .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", safePrompt))))
        var response = post(apiKey, JSONObject().put("model", MODEL).put("store", false).put("input", input).put("tools", tools))

        repeat(MAX_TOOL_ROUNDS) {
            val steps = response.optJSONArray("steps") ?: response.optJSONArray("output") ?: JSONArray()
            val modelSteps = (0 until steps.length()).mapNotNull { steps.optJSONObject(it) }
            val calls = modelSteps.filter { it.optString("type") == "function_call" }
            if (calls.isEmpty()) return extractText(response)

            // Stateless mode requires the initial user_input plus every model step verbatim.
            modelSteps.forEach { input.put(it) }
            calls.forEachIndexed { index, call ->
                val name = call.optString("name")
                val callId = call.optString("id").ifBlank { call.optString("call_id") }
                val result = when {
                    callId.isBlank() -> fail("Gemini Tool call-এর ID নেই; চালানো হয়নি।", "invalid_call")
                    index >= MAX_CALLS_PER_ROUND -> fail("এক উত্তরে Tool call-এর সীমা অতিক্রম করেছে।", "tool_limit")
                    else -> executeTool(name, parseArgs(call.opt("arguments")))
                }
                input.put(JSONObject().put("type", "function_result").put("name", name).put("call_id", callId)
                    .put("result", JSONArray().put(JSONObject().put("type", "text").put("text", result.toString()))))
            }
            response = post(apiKey, JSONObject().put("model", MODEL).put("store", false).put("input", input).put("tools", tools))
        }
        return extractText(response).ifBlank {
            "বস, নিরাপত্তার জন্য Agent Tool ধাপের সীমায় থেমেছি। শেষ ফলাফল সম্পূর্ণ যাচাই করা যায়নি।"
        }
    }

    internal fun executeTool(name: String, args: JSONObject): JSONObject {
        val taskId = "rumin-tool-${taskIds.incrementAndGet()}"
        val action: String
        val category: String
        val first: String
        val second: String

        when (name) {
            "open_app" -> {
                action = "open_app"; category = "command"; first = args.optString("app").trim().lowercase(); second = ""
                if (first !in ALLOWED_APPS) return fail("এই অ্যাপটি অনুমোদিত তালিকায় নেই।", "rejected")
            }
            "inspect_screen" -> { action = "inspect_screen"; category = "accessibility"; first = ""; second = "" }
            "phone_navigation" -> {
                first = args.optString("action").trim().lowercase()
                action = mapOf("back" to "back", "home" to "home", "recents" to "recents", "notifications" to "notifications", "quicksettings" to "quicksettings")[first]
                    ?: return fail("অজানা নেভিগেশন কমান্ড প্রত্যাখ্যান করা হয়েছে।", "rejected")
                category = "accessibility"; second = ""
            }
            "adjust_volume" -> {
                first = args.optString("action").trim().lowercase()
                action = mapOf("up" to "volumeup", "down" to "volumedown", "mute" to "volumemute", "unmute" to "volumeunmute")[first]
                    ?: return fail("অজানা ভলিউম কমান্ড প্রত্যাখ্যান করা হয়েছে।", "rejected")
                category = "command"; second = ""
            }
            "open_settings" -> {
                first = args.optString("page").trim().lowercase()
                action = mapOf("system" to "settings", "wifi" to "wifi", "bluetooth" to "bluetooth", "display" to "display",
                    "sound" to "sound", "accessibility" to "accessibility", "notification_access" to "notificationaccess",
                    "app_permissions" to "apppermissions", "write_settings" to "writesettings", "text_to_speech" to "ttssettings")[first]
                    ?: return fail("অজানা Settings পেজ প্রত্যাখ্যান করা হয়েছে।", "rejected")
                category = "command"; second = ""
            }
            "create_alarm" -> {
                val hour = args.optInt("hour", -1); val minute = args.optInt("minute", -1)
                if (hour !in 0..23 || minute !in 0..59) return fail("অ্যালার্মের সময় সঠিক নয়।", "invalid_arguments")
                action = "alarm"; category = "command"; first = hour.toString()
                second = minute.toString() + "|" + args.optString("label").take(80)
            }
            "create_timer" -> {
                val seconds = args.optInt("seconds", -1)
                if (seconds !in 1..86400) return fail("টাইমার ১ সেকেন্ড থেকে ২৪ ঘণ্টার মধ্যে হতে হবে।", "invalid_arguments")
                action = "timer"; category = "command"; first = seconds.toString(); second = args.optString("label").take(80)
            }
            "open_dialer" -> {
                first = args.optString("phone_number").trim()
                if (first.isNotBlank() && !PHONE_PATTERN.matches(first)) return fail("ফোন নম্বরের ফরম্যাট সঠিক নয়।", "invalid_arguments")
                action = if (first.isBlank()) "dialer" else "call"; category = "command"; second = ""
            }
            "compose_sms_draft" -> {
                first = args.optString("phone_number").trim(); second = args.optString("message")
                if (!PHONE_PATTERN.matches(first) || second.isBlank() || second.length > 1000) return fail("SMS খসড়ার নম্বর বা বার্তা সঠিক নয়।", "invalid_arguments")
                action = "sms"; category = "command"
            }
            else -> return fail("অজানা Tool প্রত্যাখ্যান করা হয়েছে।", "unknown_tool")
        }

        if (!agentCore.begin(action, first, second, category, taskId)) {
            audit(taskId, action, "rejected")
            return fail("Agent Tool Registry এই কাজটি অনুমোদন করেনি।", "registry_rejected")
        }
        audit(taskId, action, "running")
        nativeActionAttempted = true
        val result = dispatch(taskId, name, action, first, second)
        val verified = result.optBoolean("ok", false) && result.optString("verification").isNotBlank()
        agentCore.complete(taskId, verified, result.optString("message"))
        audit(taskId, action, if (verified) "succeeded" else "failed")
        return result
    }

    private fun dispatch(taskId: String, tool: String, action: String, first: String, second: String): JSONObject {
        val latch = CountDownLatch(1)
        val cancelled = AtomicBoolean(false)
        val started = AtomicBoolean(false)
        val result = AtomicReference<JSONObject?>()
        activity.runOnUiThread {
            if (cancelled.get()) { latch.countDown(); return@runOnUiThread }
            started.set(true)
            try {
                result.set(when (tool) {
                    "open_app" -> openApp(first)
                    "inspect_screen" -> inspectScreen()
                    "phone_navigation" -> {
                        val native = RuminAccessibilityService.nativeAction(action, "")
                        if (native.optBoolean("ok")) native.put("verification", "native_action_acknowledged")
                        native
                    }
                    "adjust_volume" -> adjustVolume(action)
                    "open_settings" -> openSettings(action)
                    "create_alarm" -> createAlarm(first.toInt(), second.substringBefore("|").toInt(), second.substringAfter("|", ""))
                    "create_timer" -> createTimer(first.toInt(), second)
                    "open_dialer" -> openDialer(first)
                    "compose_sms_draft" -> composeSmsDraft(first, second)
                    else -> fail("Tool সমর্থিত নয়।", "unsupported")
                })
            } catch (e: Exception) {
                result.set(fail("Native action ব্যর্থ হয়েছে: ${e.message ?: "অজানা সমস্যা"}", "native_error"))
            } finally { latch.countDown() }
        }
        if (!latch.await(TOOL_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            cancelled.set(true)
            val message = if (started.get())
                "Native action শুরু হয়েছিল কিন্তু ফলাফল সময়মতো পাওয়া যায়নি। একই কাজ আবার চালানো হয়নি; ফোনে ফলাফল পরীক্ষা করুন।"
            else "Native action শুরু হওয়ার আগেই সময় শেষ হয়েছে; এটি চালানো হয়নি।"
            return JSONObject().put("ok", false).put("message", message).put("verification", "outcome_unknown").put("retry_safe", false)
        }
        return result.get() ?: fail("Native action কোনো ফলাফল দেয়নি।", "empty_result")
    }

    private fun openApp(app: String): JSONObject {
        val intent = when (app) {
            "youtube" -> activity.packageManager.getLaunchIntentForPackage("com.google.android.youtube") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com"))
            "facebook" -> activity.packageManager.getLaunchIntentForPackage("com.facebook.katana") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.facebook.com"))
            "whatsapp" -> activity.packageManager.getLaunchIntentForPackage("com.whatsapp") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/"))
            "instagram" -> activity.packageManager.getLaunchIntentForPackage("com.instagram.android") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com"))
            "gmail" -> activity.packageManager.getLaunchIntentForPackage("com.google.android.gm") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://mail.google.com"))
            "maps" -> activity.packageManager.getLaunchIntentForPackage("com.google.android.apps.maps") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps"))
            "camera" -> Intent("android.media.action.IMAGE_CAPTURE")
            "settings" -> Intent(Settings.ACTION_SETTINGS)
            "browser" -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
            "playstore" -> activity.packageManager.getLaunchIntentForPackage("com.android.vending") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store"))
            "github" -> activity.packageManager.getLaunchIntentForPackage("com.github.android") ?: Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com"))
            else -> return fail("অ্যাপটি অনুমোদিত নয়।", "rejected")
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        activity.startActivity(intent)
        return JSONObject().put("ok", true).put("message", "$app খোলার অনুরোধ Android গ্রহণ করেছে।").put("verification", "activity_launch_accepted")
    }

    private fun inspectScreen(): JSONObject {
        val raw = RuminAccessibilityService.nativeAction("inspect_screen", "")
        if (!raw.optBoolean("ok")) return raw.put("verification", "screen_unavailable")
        val source = raw.optJSONArray("items") ?: JSONArray()
        val safe = JSONArray()
        for (i in 0 until source.length()) {
            val line = source.optString(i).trim()
            if (line.isBlank()) continue
            val lower = line.lowercase()
            val sensitive = listOf("password", "passcode", "otp", "verification code", "pin code", "পাসওয়ার্ড", "পাসওয়ার্ড", "ওটিপি", "যাচাইকরণ কোড", "পিন").any { lower.contains(it) }
            val redacted = when {
                sensitive -> "[সংবেদনশীল তথ্য আড়াল করা হয়েছে]"
                EMAIL_PATTERN.containsMatchIn(line) -> EMAIL_PATTERN.replace(line, "[ইমেইল আড়াল]")
                PHONE_IN_TEXT.containsMatchIn(line) -> PHONE_IN_TEXT.replace(line, "[নম্বর আড়াল]")
                LONG_DIGITS.containsMatchIn(line) -> LONG_DIGITS.replace(line, "[সংখ্যা আড়াল]")
                else -> line.take(160)
            }
            safe.put(redacted)
            if (safe.length() >= 80) break
        }
        return JSONObject().put("ok", true).put("items", safe)
            .put("message", if (safe.length() == 0) "স্ক্রিনের লেখা পাওয়া যায়নি।" else "সংবেদনশীল অংশ আড়াল করে স্ক্রিনের লেখা পড়েছি।")
            .put("verification", "screen_observed")
    }

    private fun adjustVolume(action: String): JSONObject {
        val audio = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val adjustment = when (action) {
            "volumeup" -> AudioManager.ADJUST_RAISE
            "volumedown" -> AudioManager.ADJUST_LOWER
            "volumemute" -> AudioManager.ADJUST_MUTE
            "volumeunmute" -> AudioManager.ADJUST_UNMUTE
            else -> return fail("ভলিউম কমান্ডটি সমর্থিত নয়।", "rejected")
        }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, adjustment, AudioManager.FLAG_SHOW_UI)
        return JSONObject().put("ok", true).put("current_media_volume", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("message", "মিডিয়া ভলিউম কমান্ড Android গ্রহণ করেছে।").put("verification", "volume_adjusted")
    }

    private fun openSettings(action: String): JSONObject {
        val intent = when (action) {
            "settings" -> Intent(Settings.ACTION_SETTINGS)
            "wifi" -> Intent(Settings.ACTION_WIFI_SETTINGS)
            "bluetooth" -> Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
            "display" -> Intent(Settings.ACTION_DISPLAY_SETTINGS)
            "sound" -> Intent(Settings.ACTION_SOUND_SETTINGS)
            "accessibility" -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            "notificationaccess" -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            "apppermissions" -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${activity.packageName}"))
            "writesettings" -> Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${activity.packageName}"))
            "ttssettings" -> Intent("com.android.settings.TTS_SETTINGS")
            else -> return fail("Settings পেজটি সমর্থিত নয়।", "rejected")
        }
        activity.startActivity(intent)
        return JSONObject().put("ok", true).put("message", "Android Settings পেজ খোলার অনুরোধ গ্রহণ করেছে; কোনো অনুমতি নিজে থেকে দেওয়া হয়নি.")
            .put("verification", "system_intent_dispatched")
    }

    private fun createAlarm(hour: Int, minute: Int, label: String): JSONObject {
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour); putExtra(AlarmClock.EXTRA_MINUTES, minute)
            if (label.isNotBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        activity.startActivity(intent)
        val time = "${hour.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
        return JSONObject().put("ok", true).put("message", "Clock অ্যাপে $time সময়টি প্রস্তাব করে খুলেছি। সেখানে Save/Confirm করতে হবে.")
            .put("verification", "alarm_ui_opened")
    }

    private fun createTimer(seconds: Int, label: String): JSONObject {
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Rumin AI Timer" })
            putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        }
        activity.startActivity(intent)
        return JSONObject().put("ok", true).put("message", "Clock অ্যাপে $seconds সেকেন্ডের Timer প্রস্তাব করে খুলেছি। প্রয়োজনে Start/Confirm করতে হবে.")
            .put("verification", "timer_ui_opened")
    }

    private fun openDialer(phone: String): JSONObject {
        val intent = if (phone.isBlank()) Intent(Intent.ACTION_DIAL) else Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null))
        activity.startActivity(intent)
        return JSONObject().put("ok", true).put("message", if (phone.isBlank()) "ডায়ালার খুলেছি; কল করা হয়নি।" else "নম্বরটি ডায়ালারে দেখিয়েছি; কল করা হয়নি।")
            .put("verification", "dialer_opened")
    }

    private fun composeSmsDraft(phone: String, message: String): JSONObject {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", phone, null)).apply { putExtra("sms_body", message) }
        activity.startActivity(intent)
        return JSONObject().put("ok", true).put("message", "SMS অ্যাপে খসড়া খুলেছি। বার্তা পাঠানো হয়নি; পাঠানোর আগে বস নিজে যাচাই করবেন.")
            .put("verification", "sms_draft_opened")
    }

    private fun recoverInterruptedTask() {
        val prefs = auditPrefs()
        val active = prefs.getString(KEY_ACTIVE_TASK, null) ?: return
        if (active.isNotBlank()) audit(active, "unknown", "interrupted")
        prefs.edit().remove(KEY_ACTIVE_TASK).apply()
    }

    /** Bounded, persistent and deliberately redacted: no arguments, phone numbers, messages or screen text. */
    private fun audit(taskId: String, action: String, state: String) {
        val prefs = auditPrefs()
        val old = try { JSONArray(prefs.getString(KEY_EVENTS, "[]")) } catch (_: Exception) { JSONArray() }
        old.put(JSONObject().put("time", System.currentTimeMillis()).put("task_id", taskId).put("action", action).put("state", state))
        val bounded = JSONArray()
        for (i in (old.length() - MAX_EVENTS).coerceAtLeast(0) until old.length()) bounded.put(old.getJSONObject(i))
        val editor = prefs.edit().putString(KEY_EVENTS, bounded.toString())
        if (state == "running") editor.putString(KEY_ACTIVE_TASK, taskId)
        else if (prefs.getString(KEY_ACTIVE_TASK, null) == taskId) editor.remove(KEY_ACTIVE_TASK)
        editor.apply()
    }

    private fun auditPrefs() = activity.getSharedPreferences(AUDIT_PREFS, Context.MODE_PRIVATE)

    private fun parseArgs(value: Any?): JSONObject = when (value) {
        is JSONObject -> value
        is String -> try { JSONObject(value) } catch (_: Exception) { JSONObject() }
        else -> JSONObject()
    }

    private fun fail(message: String, code: String) =
        JSONObject().put("ok", false).put("message", message).put("verification", code)

    private fun tool(name: String, description: String, parameters: JSONObject) =
        JSONObject().put("type", "function").put("name", name).put("description", description).put("parameters", parameters)

    private fun schema(vararg fields: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
        val properties = JSONObject()
        fields.forEach { properties.put(it.first, it.second) }
        return JSONObject().put("type", "object").put("properties", properties).put("required", JSONArray(required))
    }

    private fun string(description: String) = JSONObject().put("type", "string").put("description", description)
    private fun integer(description: String) = JSONObject().put("type", "integer").put("description", description)
    private fun enum(description: String, vararg values: String) =
        JSONObject().put("type", "string").put("description", description).put("enum", JSONArray(values.toList()))

    private fun post(apiKey: String, body: JSONObject): JSONObject {
        val connection = URL(INTERACTIONS_URL).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"; connection.connectTimeout = 20000; connection.readTimeout = 45000
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("x-goog-api-key", apiKey)
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val raw = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val parsed = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
            if (status !in 200..299) {
                val detail = parsed.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() } ?: "HTTP $status"
                throw IllegalStateException("Gemini API HTTP $status: $detail")
            }
            return parsed
        } finally { connection.disconnect() }
    }

    private fun extractText(response: JSONObject): String {
        response.optString("output_text").takeIf { it.isNotBlank() }?.let { return it }
        val parts = mutableListOf<String>()
        fun collect(array: JSONArray?) {
            if (array == null) return
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (item.optString("type") == "text") item.optString("text").takeIf { it.isNotBlank() }?.let(parts::add)
                collect(item.optJSONArray("content"))
            }
        }
        collect(response.optJSONArray("output"))
        if (parts.isEmpty()) collect(response.optJSONArray("steps"))
        return parts.joinToString("\n")
    }

    companion object {
        private const val MODEL = "gemini-3.8-flash"
        private const val INTERACTIONS_URL = "https://generativelanguage.googleapis.com/v1beta/interactions"
        private const val MAX_TOOL_ROUNDS = 4
        private const val MAX_CALLS_PER_ROUND = 4
        private const val TOOL_TIMEOUT_SECONDS = 15L
        private const val AUDIT_PREFS = "rumin_agent_audit"
        private const val KEY_EVENTS = "events"
        private const val KEY_ACTIVE_TASK = "active_task"
        private const val MAX_EVENTS = 120
        private val ALLOWED_APPS = setOf("youtube", "facebook", "whatsapp", "instagram", "gmail", "maps", "camera", "settings", "browser", "playstore", "github")
        private val PHONE_PATTERN = Regex("^[+0-9*#]{3,20}$")
        private val PHONE_IN_TEXT = Regex("(?<!\\w)(?:\\+?\\d[\\d .()/-]{7,}\\d)(?!\\w)")
        private val EMAIL_PATTERN = Regex("[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}", RegexOption.IGNORE_CASE)
        private val LONG_DIGITS = Regex("(?<!\\d)\\d{4,8}(?!\\d)")
    }
}
