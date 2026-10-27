package com.ruminai.app

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Registered listener for the user-enabled Notification Access setting.
 * Notifications are not forwarded or stored; this service currently only
 * establishes the Android service required for future user-authorized features.
 */
class RuminNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) = Unit
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = Unit
}
