package com.ruminai.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/** Accessibility bridge: inspect semantic UI nodes and use node actions with gesture fallbacks. */
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

    private fun gesture(points: List<Pair<Float, Float>>, duration: Long = 350L): Boolean {
        if (Build.VERSION.SDK_INT < 24 || points.isEmpty()) return false
        return try {
            val path = Path().apply {
                moveTo(points.first().first, points.first().second)
                points.drop(1).forEach { lineTo(it.first, it.second) }
            }
            dispatchGesture(GestureDescription.Builder().addStroke(
                GestureDescription.StrokeDescription(path, 0, duration)
            ).build(), null, null)
            true
        } catch (_: Exception) { false }
    }

    private fun walk(root: AccessibilityNodeInfo?, visit: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null) return null
        if (visit(root)) return root
        for (i in 0 until root.childCount) {
            val found = walk(root.getChild(i), visit)
            if (found != null) return found
        }
        return null
    }

    private fun nodeLabel(node: AccessibilityNodeInfo): String =
        listOf(node.text?.toString(), node.contentDescription?.toString())
            .filterNotNull().map { it.trim() }.filter { it.isNotBlank() }.distinct().joinToString(" ")

    private fun nodeJson(node: AccessibilityNodeInfo, index: Int): JSONObject {
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        return JSONObject()
            .put("index", index)
            .put("text", node.text?.toString().orEmpty().take(160))
            .put("description", node.contentDescription?.toString().orEmpty().take(160))
            .put("viewId", node.viewIdResourceName.orEmpty().take(160))
            .put("className", node.className?.toString().orEmpty().take(120))
            .put("clickable", node.isClickable)
            .put("editable", node.isEditable)
            .put("focusable", node.isFocusable)
            .put("focused", node.isFocused)
            .put("scrollable", node.isScrollable)
            .put("x", bounds.centerX()).put("y", bounds.centerY())
            .put("left", bounds.left).put("top", bounds.top)
            .put("right", bounds.right).put("bottom", bounds.bottom)
    }

    fun runNativeAction(name: String, value: String): JSONObject {
        return try {
            when (name) {
                "inspect_screen" -> {
                    val root = rootInActiveWindow
                    if (root == null) JSONObject().put("ok", false).put("message", "সক্রিয় স্ক্রিন পড়া যাচ্ছে না।")
                    else {
                        val rows = JSONArray()
                        val elements = JSONArray()
                        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
                            if (node == null || depth > 22 || elements.length() >= 180) return
                            val label = nodeLabel(node)
                            if (label.isNotBlank()) rows.put(label.take(180))
                            val bounds = Rect(); node.getBoundsInScreen(bounds)
                            if (bounds.width() > 0 && bounds.height() > 0 &&
                                (label.isNotBlank() || node.isClickable || node.isEditable || node.isScrollable)) {
                                elements.put(nodeJson(node, elements.length()))
                            }
                            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
                        }
                        visit(root, 0)
                        JSONObject().put("ok", true).put("items", rows).put("elements", elements)
                            .put("message", if (elements.length() == 0) "স্ক্রিনে শনাক্তযোগ্য উপাদান পাওয়া যায়নি।" else "স্ক্রিনের উপাদান পড়েছি।")
                    }
                }
                "click_text" -> {
                    val root = rootInActiveWindow
                    val wanted = value.trim().lowercase()
                    val matched = walk(root) { node ->
                        val label = nodeLabel(node).lowercase()
                        val id = node.viewIdResourceName.orEmpty().lowercase()
                        wanted.isNotBlank() && (label.contains(wanted) || id.contains(wanted))
                    }
                    var target = matched
                    var climbed = 0
                    while (target != null && !target.isClickable && climbed < 14) {
                        target = target.parent; climbed++
                    }
                    var ok = target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
                    if (!ok && matched != null) {
                        val bounds = Rect(); matched.getBoundsInScreen(bounds)
                        if (bounds.width() > 0 && bounds.height() > 0) ok = gesture(listOf(bounds.centerX().toFloat() to bounds.centerY().toFloat()))
                    }
                    JSONObject().put("ok", ok).put("message", when {
                        ok -> "‘$value’ উপাদানে চাপ দেওয়া হয়েছে।"
                        matched == null -> "‘$value’ নামে লেখা বা উপাদান স্ক্রিনে পাওয়া যায়নি।"
                        else -> "উপাদানটি পাওয়া গেছে, কিন্তু ট্যাপ করা যায়নি।"
                    })
                }
                "type_text" -> {
                    val root = rootInActiveWindow
                    val field = walk(root) { it.isEditable && (it.isFocused || it.isVisibleToUser) }
                    var ok = false
                    if (field != null) {
                        if (!field.isFocused) field.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
                        }
                        ok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        if (!ok) {
                            val bounds = Rect(); field.getBoundsInScreen(bounds)
                            if (bounds.width() > 0 && bounds.height() > 0) {
                                gesture(listOf(bounds.centerX().toFloat() to bounds.centerY().toFloat()))
                            }
                        }
                    }
                    JSONObject().put("ok", ok).put("message", if (ok) "ইনপুট ঘরে লেখা বসানো হয়েছে।" else if (field == null) "স্ক্রিনে কোনো দৃশ্যমান লেখার ঘর পাওয়া যায়নি।" else "লেখার ঘর নির্বাচন হয়েছে, কিন্তু লেখা বসানো যায়নি।")
                }
                "scroll_down", "scroll_up" -> {
                    val root = rootInActiveWindow
                    val target = walk(root) { it.isScrollable }
                    val action = if (name == "scroll_down") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    var ok = target?.performAction(action) == true
                    if (!ok) {
                        val bounds = Rect()
                        (target ?: root)?.getBoundsInScreen(bounds)
                        if (bounds.width() > 0 && bounds.height() > 0) {
                            val x = bounds.centerX().toFloat()
                            val startY = if (name == "scroll_down") bounds.bottom - 100f else bounds.top + 100f
                            val endY = if (name == "scroll_down") bounds.top + 100f else bounds.bottom - 100f
                            ok = gesture(listOf(x to startY, x to endY), 500L)
                        }
                    }
                    JSONObject().put("ok", ok).put("message", if (ok) "স্ক্রল করার চেষ্টা করা হয়েছে।" else "এই স্ক্রিনে স্ক্রল করা যায়নি।")
                }
                "back", "home", "recents", "notifications", "quicksettings", "power" -> {
                    val ok = runGlobalAction(name)
                    JSONObject().put("ok", ok).put("message", if (ok) "ফোনের কমান্ড চালানো হয়েছে।" else "ফোনের কমান্ড চালানো যায়নি।")
                }
                else -> JSONObject().put("ok", false).put("message", "এই Native Action সমর্থিত নয়।")
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("message", "Action চালাতে গিয়ে ত্রুটি: ${e.message ?: "অজানা"}")
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
