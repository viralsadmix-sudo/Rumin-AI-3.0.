package com.ruminai.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** User-enabled bridge for reading the active screen and basic UI actions. */
class RuminAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    fun runGlobalAction(name: String): Boolean {
        val action = when (name) {
            "home" -> GLOBAL_ACTION_HOME
            "back" -> GLOBAL_ACTION_BACK
            "recents" -> GLOBAL_ACTION_RECENTS
            "notifications" -> GLOBAL_ACTION_NOTIFICATIONS
            "quicksettings" -> GLOBAL_ACTION_QUICK_SETTINGS
            "power" -> GLOBAL_ACTION_POWER_DIALOG
            else -> return false
        }
        return performGlobalAction(action)
    }

    fun runNativeAction(name: String, value: String): JSONObject {
        return try {
            when (name) {
                "inspect_screen" -> {
                    val root = rootInActiveWindow
                    if (root == null) JSONObject().put("ok", false).put("message", "সক্রিয় স্ক্রিন পড়া যাচ্ছে না।")
                    else {
                        val rows = JSONArray()
                        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
                            if (node == null || depth > 16 || rows.length() >= 100) return
                            val label = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
                            if (label.isNotBlank()) rows.put(label.take(160))
                            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
                        }
                        visit(root, 0)
                        JSONObject().put("ok", true).put("items", rows)
                            .put("message", if (rows.length() == 0) "স্ক্রিনে পড়ার মতো লেখা পাওয়া যায়নি।" else "স্ক্রিনের লেখা পড়েছি।")
                    }
                }
                "click_text" -> {
                    val root = rootInActiveWindow
                    val nodes = root?.findAccessibilityNodeInfosByText(value)
                        ?.filter { node -> (node.text ?: node.contentDescription)?.toString()?.trim() == value.trim() }
                    var target = nodes?.firstOrNull { it.isClickable }
                    if (target == null) {
                        var node = nodes?.firstOrNull()
                        while (node != null && !node.isClickable) node = node.parent
                        target = node
                    }
                    val ok = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    JSONObject().put("ok", ok).put("message", if (ok) "‘$value’ লেখা উপাদানে চাপ দেওয়া হয়েছে।" else "‘$value’ লেখা উপাদান পাওয়া যায়নি বা চাপ দেওয়া যায়নি।")
                }
                "type_text" -> {
                    val root = rootInActiveWindow
                    fun findEditable(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                        if (node == null) return null
                        if (node.isFocused && node.isEditable) return node
                        for (i in 0 until node.childCount) {
                            val found = findEditable(node.getChild(i))
                            if (found != null) return found
                        }
                        return null
                    }
                    val field = findEditable(root)
                    val args = android.os.Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                    }
                    val ok = field?.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args) == true
                    JSONObject().put("ok", ok).put("message", if (ok) "নির্বাচিত ইনপুট ঘরে লেখা বসানো হয়েছে।" else "ফোকাস করা লেখার ঘর পাওয়া যায়নি। আগে ইনপুট ঘরটি নির্বাচন করুন।")
                }
                "scroll_down", "scroll_up" -> {
                    val root = rootInActiveWindow
                    val scrollables = mutableListOf<Pair<AccessibilityNodeInfo, Int>>()
                    fun collectScrollable(node: AccessibilityNodeInfo?, depth: Int) {
                        if (node == null || depth > 24 || scrollables.size >= 80) return
                        if (node.isVisibleToUser && node.isScrollable) {
                            val bounds = android.graphics.Rect()
                            node.getBoundsInScreen(bounds)
                            val area = bounds.width().coerceAtLeast(0) * bounds.height().coerceAtLeast(0)
                            if (area > 0) scrollables.add(node to area)
                        }
                        for (i in 0 until node.childCount) collectScrollable(node.getChild(i), depth + 1)
                    }
                    collectScrollable(root, 0)
                    // Prefer the largest visible scroll container (typically the Facebook feed),
                    // rather than an unrelated small nested control discovered first.
                    val target = scrollables.maxByOrNull { it.second }?.first
                    val nodeAction = if (name == "scroll_down") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    val nodeAccepted = target?.performAction(nodeAction) == true
                    if (nodeAccepted) {
                        JSONObject().put("ok", true)
                            .put("message", "স্ক্রল কন্টেইনারে নির্দেশ পাঠানো হয়েছে।")
                            .put("verification", "scroll_action_dispatched")
                    } else {
                        // Some social feeds expose no scrollable accessibility node. Fall back
                        // to a real swipe gesture; the service XML already declares canPerformGestures.
                        val metrics = resources.displayMetrics
                        val width = metrics.widthPixels.toFloat()
                        val height = metrics.heightPixels.toFloat()
                        val x = width * 0.5f
                        val fromY = if (name == "scroll_down") height * 0.80f else height * 0.30f
                        val toY = if (name == "scroll_down") height * 0.30f else height * 0.80f
                        val path = android.graphics.Path().apply {
                            moveTo(x, fromY)
                            lineTo(x, toY)
                        }
                        val gesture = android.accessibilityservice.GestureDescription.Builder()
                            .addStroke(android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 420))
                            .build()
                        val accepted = dispatchGesture(gesture, null, null)
                        JSONObject().put("ok", accepted)
                            .put("message", if (accepted) "স্ক্রল swipe পাঠানো হয়েছে; স্ক্রিনে পরিবর্তন হয়েছে কি না দেখুন।" else "স্ক্রল gesture পাঠানো যায়নি।")
                            .put("verification", if (accepted) "scroll_gesture_dispatched" else "scroll_unavailable")
                    }
                }
                "back", "home", "recents", "notifications", "quicksettings", "power" -> {
                    val ok = runGlobalAction(name)
                    JSONObject().put("ok", ok).put("message", if (ok) "ফোনের কমান্ড চালানো হয়েছে।" else "কমান্ড চালানো যায়নি।")
                }
                else -> JSONObject().put("ok", false).put("message", "এই Native Action এখনো সমর্থিত নয়।")
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("message", "Native Action ব্যর্থ: ${e.message ?: "অজানা সমস্যা"}")
        }
    }

    companion object {
        @Volatile private var instance: RuminAccessibilityService? = null
        fun perform(name: String): Boolean = instance?.runGlobalAction(name) ?: false
        fun nativeAction(name: String, value: String): JSONObject =
            instance?.runNativeAction(name, value)
                ?: JSONObject().put("ok", false).put("message", "RUMIN AI Accessibility Service চালু নেই।")
    }
}
