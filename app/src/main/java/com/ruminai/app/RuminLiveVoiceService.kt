package com.ruminai.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import org.json.JSONObject

class RuminLiveVoiceService : Service() {
    interface Listener {
        fun onStatus(message: String, state: String)
        fun onTurn(userText: String, assistantText: String, audioReceived: Boolean)
        fun onToolCall(callId: String, functionName: String, args: JSONObject)
    }

    companion object {
        const val ACTION_START = "com.ruminai.app.action.START_LIVE"
        const val ACTION_STOP = "com.ruminai.app.action.STOP_LIVE"
        const val EXTRA_API_KEY = "gemini_api_key"
        private const val CHANNEL_ID = "rumin_live_voice"
        private const val NOTIFICATION_ID = 7301

        @Volatile var listener: Listener? = null
        @Volatile var current: RuminLiveVoiceService? = null
            private set
    }

    @Volatile private var liveSession: GeminiLiveVoice? = null

    override fun onCreate() {
        super.onCreate()
        current = this
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopLiveSession()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                val key = intent.getStringExtra(EXTRA_API_KEY).orEmpty()
                if (key.isBlank()) {
                    listener?.onStatus("Gemini API Key পাওয়া যায়নি।", "stopped")
                    stopSelf()
                    return START_NOT_STICKY
                }
                startLiveSession(key)
            }
        }
        return START_REDELIVER_INTENT
    }

    private fun startLiveSession(key: String) {
        stopLiveSession()
        val session = GeminiLiveVoice(
            applicationContext,
            { message, state -> listener?.onStatus(message, state) },
            { userText, assistantText, audioReceived -> listener?.onTurn(userText, assistantText, audioReceived) },
            { callId, functionName, args -> listener?.onToolCall(callId, functionName, args) }
        )
        liveSession = session
        session.start(key)
    }

    fun sendToolResponse(callId: String, functionName: String, response: JSONObject): Boolean =
        liveSession?.sendToolResponse(callId, functionName, response) == true

    private fun stopLiveSession() {
        liveSession?.stop()
        liveSession = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(CHANNEL_ID, "Rumin Live Voice", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Rumin AI-এর চলমান Live Voice সংযোগ"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = launchIntent?.let {
            android.app.PendingIntent.getActivity(
                this, 7302, it,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or
                    (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) android.app.PendingIntent.FLAG_IMMUTABLE else 0)
            )
        }
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setSmallIcon(applicationInfo.icon)
            .setContentTitle("Rumin AI 3.0 — Live Voice চালু")
            .setContentText("অ্যাপের বাইরে গেলেও Rumin কথা শুনবে ও উত্তর দেবে")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Keep the foreground microphone service alive when the user leaves the app task.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopLiveSession()
        if (current === this) current = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
