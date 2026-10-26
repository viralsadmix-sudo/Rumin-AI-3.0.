package com.ruminai.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/** Optional navigation bridge; the user must enable it in Android settings. */
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
    companion object {
        @Volatile private var instance: RuminAccessibilityService? = null
        fun perform(name: String): Boolean = instance?.runGlobalAction(name) ?: false
    }
}
