package com.ruminai.app.core.phone

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.os.Bundle

/**
 * User-enabled bridge for narrowly scoped, explicit phone-control tools.
 * This service does not observe or upload screen data in the background.
 * Callers must invoke these methods only as part of a user-requested Agent task.
 */
class PhoneAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        activeService = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Deliberately do not collect, retain, or forward accessibility events.
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (activeService === this) activeService = null
        super.onDestroy()
    }

    /** Returns visible text from the current window; caller decides what to display. */
    fun readVisibleText(maxCharacters: Int = 4000): String {
        val root = rootInActiveWindow ?: return ""
        return try {
            val lines = mutableListOf<String>()
            collectText(root, lines, maxCharacters.coerceIn(1, 12000))
            lines.distinct().joinToString("\n").take(maxCharacters.coerceIn(1, 12000))
        } finally {
            root.recycle()
        }
    }

    /** Clicks only when exactly one visible node matches the supplied text. */
    fun clickUniqueText(label: String): Boolean {
        if (label.isBlank()) return false
        val root = rootInActiveWindow ?: return false
        return try {
            val matches = root.findAccessibilityNodeInfosByText(label).orEmpty()
            .filter { it.isVisibleToUser }
        if (matches.size != 1) {
            matches.forEach { it.recycle() }
            return false
        }
        var target: AccessibilityNodeInfo? = matches.first()
        var ancestor: AccessibilityNodeInfo? = null
        try {
            while (target != null && !target.isClickable) {
                ancestor?.recycle()
                ancestor = target.parent
                target = ancestor
            }
            target?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
        } finally {
            ancestor?.recycle()
            matches.forEach { it.recycle() }
        }
        } finally {
            root.recycle()
        }
    }

    /** Scrolls the active window only; no coordinates or arbitrary gestures are accepted. */
    fun scrollActiveWindowForward(): Boolean {
        val root = rootInActiveWindow ?: return false
        return try {
            root.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        } finally {
            root.recycle()
        }
    }

    /** Types only into the currently focused editable field after explicit user request. */
    fun typeIntoFocusedField(value: String): Boolean {
        if (value.length > 2000) return false
        val root = rootInActiveWindow ?: return false
        return try {
            val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
            try {
                if (!focused.isEditable || !focused.isFocused) return false
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        value
                    )
                }
                focused.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            } finally {
                focused.recycle()
            }
        } finally {
            root.recycle()
        }
    }

    private fun collectText(node: AccessibilityNodeInfo, output: MutableList<String>, limit: Int) {
        if (output.sumOf { it.length } >= limit) return
        if (node.isVisibleToUser) {
            val text = node.text?.toString()?.trim().orEmpty()
            val description = node.contentDescription?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) output += text
            if (description.isNotEmpty() && description != text) output += description
        }
        for (index in 0 until node.childCount) {
            if (output.sumOf { it.length } >= limit) break
            val child = node.getChild(index) ?: continue
            try {
                collectText(child, output, limit)
            } finally {
                child.recycle()
            }
        }
    }

    companion object {
        @Volatile
        private var activeService: PhoneAccessibilityService? = null

        fun isEnabledInProcess(): Boolean = activeService != null

        fun current(): PhoneAccessibilityService? = activeService
    }
}
