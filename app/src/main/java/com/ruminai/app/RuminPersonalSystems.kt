package com.ruminai.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Small, privacy-first local memory and bounded performance telemetry. */
internal class RuminPersonalSystems(private val context: Context) {
    private val prefs = context.getSharedPreferences("rumin_personal_systems", Context.MODE_PRIVATE)

    @Synchronized fun remember(text: String, tag: String = "general"): Boolean {
        val value = text.trim().take(1200)
        if (value.isBlank()) return false
        val rows = try { JSONArray(prefs.getString("memory", "[]")) } catch (_: Exception) { JSONArray() }
        rows.put(JSONObject().put("id", UUID.randomUUID().toString())
            .put("text", value).put("tag", tag.trim().take(40))
            .put("createdAt", System.currentTimeMillis()))
        val bounded = JSONArray()
        for (i in maxOf(0, rows.length() - 100) until rows.length()) bounded.put(rows.getJSONObject(i))
        prefs.edit().putString("memory", bounded.toString()).apply()
        return true
    }

    @Synchronized fun memories(): String = try {
        JSONArray(prefs.getString("memory", "[]")).toString()
    } catch (_: Exception) { "[]" }

    @Synchronized fun clearMemories() { prefs.edit().remove("memory").apply() }

    @Synchronized fun metric(name: String, durationMs: Long, success: Boolean) {
        val key = "metrics"
        val rows = try { JSONArray(prefs.getString(key, "[]")) } catch (_: Exception) { JSONArray() }
        rows.put(JSONObject().put("name", name.take(60)).put("durationMs", durationMs.coerceAtLeast(0))
            .put("success", success).put("at", System.currentTimeMillis()))
        val bounded = JSONArray()
        for (i in maxOf(0, rows.length() - 200) until rows.length()) bounded.put(rows.getJSONObject(i))
        prefs.edit().putString(key, bounded.toString()).apply()
    }

    @Synchronized fun metrics(): String = try {
        JSONArray(prefs.getString("metrics", "[]")).toString()
    } catch (_: Exception) { "[]" }

    fun scheduleReminder(title: String, message: String, atMillis: Long): String {
        val safeTitle = title.trim().take(100).ifBlank { "Rumin AI Reminder" }
        val safeMessage = message.trim().take(500).ifBlank { safeTitle }
        if (atMillis <= System.currentTimeMillis()) return "সময়টি ভবিষ্যতের হতে হবে।"
        val id = (System.currentTimeMillis() and 0x7fffffff).toInt()
        val intent = Intent(context, RuminReminderReceiver::class.java)
            .putExtra("title", safeTitle).putExtra("message", safeMessage)
        val pending = PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val alarm = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        if (Build.VERSION.SDK_INT >= 31 && alarm.canScheduleExactAlarms()) {
            alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        } else {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, atMillis, pending)
        }
        return "ফোনে রিমাইন্ডার সেট হয়েছে।"
    }
}

class RuminReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = "rumin_reminders"
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(channelId, "Rumin Reminders", NotificationManager.IMPORTANCE_HIGH))
        }
        val title = intent.getStringExtra("title") ?: "Rumin AI Reminder"
        val message = intent.getStringExtra("message") ?: title
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        manager.notify((System.currentTimeMillis() and 0x7fffffff).toInt(), notification)
    }
}
