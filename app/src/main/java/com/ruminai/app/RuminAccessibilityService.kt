package com.ruminai.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Bitmap
import android.graphics.Path
import android.hardware.HardwareBuffer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** User-enabled bridge for reading the active screen and performing explicitly requested UI actions. */
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

    private fun screenshotJpegBase64(timeoutMs: Long): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        val latch = CountDownLatch(1)
        val encoded = AtomicReference<String?>()
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, Executor { command ->
                Handler(Looper.getMainLooper()).post(command)
            }, object : TakeScreenshotCallback {
                override fun onSuccess(screenshot: ScreenshotResult) {
                    var source: Bitmap? = null
                    var software: Bitmap? = null
                    var scaled: Bitmap? = null
                    try {
                        source = Bitmap.wrapHardwareBuffer(screenshot.hardwareBuffer, screenshot.colorSpace)
                        software = source?.copy(Bitmap.Config.ARGB_8888, false)
                        val bitmap = software ?: return
                        val maxDimension = 1280
                        val outputBitmap = if (bitmap.width > maxDimension || bitmap.height > maxDimension) {
                            val ratio = maxDimension.toFloat() / maxOf(bitmap.width, bitmap.height)
                            Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1),
                                (bitmap.height * ratio).toInt().coerceAtLeast(1), true).also { scaled = it }
                        } else bitmap
                        val output = ByteArrayOutputStream()
                        if (outputBitmap.compress(Bitmap.CompressFormat.JPEG, 68, output)) {
                            encoded.set(Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP))
                        }
                    } catch (_: Exception) {
                        encoded.set(null)
                    } finally {
                        scaled?.recycle()
                        software?.recycle()
                        source?.recycle()
                        try { screenshot.hardwareBuffer.close() } catch (_: Exception) {}
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            })
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
            return encoded.get()
        } catch (_: Exception) {
            return null
        }
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
                            .put("verification", "accessibility_tree_read")
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
                        .put("verification", if (ok) "exact_label_click_dispatched" else "click_unavailable")
                }
                "tap_at" -> tapLabeledControl(value)
                "swipe" -> swipeCoordinates(value)
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
                        .put("verification", if (ok) "focused_field_text_set" else "editable_field_unavailable")
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
                    val target = scrollables.maxByOrNull { it.second }?.first
                    val nodeAction = if (name == "scroll_down") AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    val nodeAccepted = target?.performAction(nodeAction) == true
                    if (nodeAccepted) {
                        JSONObject().put("ok", true).put("message", "স্ক্রল কন্টেইনারে নির্দেশ পাঠানো হয়েছে।")
                            .put("verification", "scroll_action_dispatched")
                    } else {
                        val metrics = resources.displayMetrics
                        val x = metrics.widthPixels * 0.5f
                        val fromY = if (name == "scroll_down") metrics.heightPixels * 0.80f else metrics.heightPixels * 0.30f
                        val toY = if (name == "scroll_down") metrics.heightPixels * 0.30f else metrics.heightPixels * 0.80f
                        dispatchSwipe(x, fromY, x, toY, 420)
                    }
                }
                "back", "home", "recents", "notifications", "quicksettings", "power" -> {
                    val ok = runGlobalAction(name)
                    JSONObject().put("ok", ok).put("message", if (ok) "ফোনের কমান্ড চালানো হয়েছে।" else "কমান্ড চালানো যায়নি।")
                        .put("verification", if (ok) "global_action_dispatched" else "global_action_unavailable")
                }
                else -> JSONObject().put("ok", false).put("message", "এই Native Action এখনো সমর্থিত নয়।").put("verification", "unsupported")
            }
        } catch (e: Exception) {
            JSONObject().put("ok", false).put("message", "Native Action ব্যর্থ: ${e.message ?: "অজানা সমস্যা"}").put("verification", "native_error")
        }
    }

    /** Coordinates are normalized to 0..1000. Taps require a labeled, clickable accessibility node. */
    private fun tapLabeledControl(value: String): JSONObject {
        val parts = value.split("|")
        if (parts.size != 2) return JSONObject().put("ok", false).put("message", "Tap coordinate সঠিক নয়।").put("verification", "invalid_coordinates")
        val nx = parts[0].toIntOrNull() ?: return JSONObject().put("ok", false).put("message", "X coordinate সঠিক নয়।").put("verification", "invalid_coordinates")
        val ny = parts[1].toIntOrNull() ?: return JSONObject().put("ok", false).put("message", "Y coordinate সঠিক নয়।").put("verification", "invalid_coordinates")
        if (nx !in 0..1000 || ny !in 0..1000) return JSONObject().put("ok", false).put("message", "Coordinate ০–১০০০ সীমার মধ্যে হতে হবে।").put("verification", "invalid_coordinates")
        val metrics = resources.displayMetrics
        val x = (nx / 1000f * metrics.widthPixels).toInt()
        val y = (ny / 1000f * metrics.heightPixels).toInt()
        val root = rootInActiveWindow ?: return JSONObject().put("ok", false).put("message", "স্ক্রিনের UI tree পাওয়া যায়নি; নিরাপদে tap করা হয়নি।").put("verification", "screen_tree_unavailable")
        var best: AccessibilityNodeInfo? = null
        var bestArea = Long.MAX_VALUE
        fun labelOf(node: AccessibilityNodeInfo?, depth: Int = 0): String {
            if (node == null || depth > 5) return ""
            val own = (node.text ?: node.contentDescription)?.toString()?.trim().orEmpty()
            if (own.isNotBlank()) return own
            for (i in 0 until node.childCount) {
                val found = labelOf(node.getChild(i), depth + 1)
                if (found.isNotBlank()) return found
            }
            return ""
        }
        fun visit(node: AccessibilityNodeInfo?, depth: Int) {
            if (node == null || depth > 24) return
            if (node.isVisibleToUser && node.isClickable) {
                val bounds = android.graphics.Rect()
                node.getBoundsInScreen(bounds)
                if (bounds.contains(x, y)) {
                    val area = bounds.width().toLong().coerceAtLeast(0) * bounds.height().toLong().coerceAtLeast(0)
                    if (area in 1 until bestArea) {
                        best = node
                        bestArea = area
                    }
                }
            }
            for (i in 0 until node.childCount) visit(node.getChild(i), depth + 1)
        }
        visit(root, 0)
        val target = best
        val label = labelOf(target)
        if (target == null || label.isBlank()) {
            return JSONObject().put("ok", false).put("message", "ওই স্থানে চেনা যায় এমন লেখা/লেবেলযুক্ত clickable control নেই; নিরাপত্তার জন্য tap করা হয়নি।").put("verification", "unlabeled_control_blocked")
        }
        val lower = label.lowercase()
        val blocked = listOf("send", "delete", "remove", "pay", "payment", "purchase", "buy", "transfer", "পাঠান", "মুছুন", "ডিলিট", "পেমেন্ট", "কিনুন", "টাকা পাঠান", "নিশ্চিত করে পাঠান")
        if (blocked.any { lower.contains(it) }) {
            return JSONObject().put("ok", false).put("message", "‘$label’ একটি সংবেদনশীল action হতে পারে; রুমিন এটি নিজে চাপবে না।").put("verification", "sensitive_control_blocked")
        }
        val ok = target.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        return JSONObject().put("ok", ok).put("label", label.take(100))
            .put("message", if (ok) "স্ক্রিনে ‘$label’ control-এ চাপ দেওয়ার নির্দেশ পাঠানো হয়েছে।" else "‘$label’ control-এ চাপ দেওয়া যায়নি।")
            .put("verification", if (ok) "labeled_control_click_dispatched" else "click_unavailable")
    }

    private fun swipeCoordinates(value: String): JSONObject {
        val parts = value.split("|")
        if (parts.size < 4) return JSONObject().put("ok", false).put("message", "Swipe coordinate সঠিক নয়।").put("verification", "invalid_coordinates")
        val coords = parts.take(4).map { it.toIntOrNull() ?: -1 }
        if (coords.any { it !in 0..1000 }) return JSONObject().put("ok", false).put("message", "Swipe coordinate ০–১০০০ সীমার মধ্যে হতে হবে।").put("verification", "invalid_coordinates")
        val duration = parts.getOrNull(4)?.toLongOrNull()?.coerceIn(150L, 1200L) ?: 350L
        val metrics = resources.displayMetrics
        val x1 = coords[0] / 1000f * metrics.widthPixels
        val y1 = coords[1] / 1000f * metrics.heightPixels
        val x2 = coords[2] / 1000f * metrics.widthPixels
        val y2 = coords[3] / 1000f * metrics.heightPixels
        return dispatchSwipe(x1, y1, x2, y2, duration)
    }

    private fun dispatchSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long): JSONObject {
        val path = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, duration))
            .build()
        val accepted = dispatchGesture(gesture, null, null)
        return JSONObject().put("ok", accepted)
            .put("message", if (accepted) "Swipe gesture পাঠানো হয়েছে; পরের স্ক্রিন দেখে ফল যাচাই করা হবে।" else "Swipe gesture পাঠানো যায়নি।")
            .put("verification", if (accepted) "swipe_gesture_dispatched" else "swipe_unavailable")
    }

    companion object {
        @Volatile private var instance: RuminAccessibilityService? = null
        fun perform(name: String): Boolean = instance?.runGlobalAction(name) ?: false
        fun nativeAction(name: String, value: String): JSONObject =
            instance?.runNativeAction(name, value)
                ?: JSONObject().put("ok", false).put("message", "RUMIN AI Accessibility Service চালু নেই।").put("verification", "accessibility_unavailable")
        fun captureScreenJpegBase64(timeoutMs: Long = 5000): String? =
            instance?.screenshotJpegBase64(timeoutMs)
    }
}
