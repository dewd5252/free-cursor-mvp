package com.freecursor.app.core

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlin.concurrent.thread

class ModelForegroundService : Service() {
    private val modelManager by lazy { ModelManager.get(applicationContext) }

    override fun onCreate() {
        super.onCreate()
        createChannelIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification("Free Cursor core running"))

        when (intent?.action) {
            ACTION_START -> {
                thread(name = "fc-model-load") {
                    modelManager.ensureModelLoaded()
                }
            }

            ACTION_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_MODEL_URL).orEmpty()
                if (url.isNotBlank()) {
                    thread(name = "fc-model-download") {
                        modelManager.downloadModel(url)
                    }
                }
            }

            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Free Cursor Core",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps model and orchestration alive for low-latency actions"
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Free Cursor")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setOngoing(true)
            .build()
    }

    companion object {
        const val ACTION_START = "com.freecursor.app.action.START_CORE"
        const val ACTION_STOP = "com.freecursor.app.action.STOP_CORE"
        const val ACTION_DOWNLOAD = "com.freecursor.app.action.DOWNLOAD_MODEL"
        const val EXTRA_MODEL_URL = "extra_model_url"

        private const val CHANNEL_ID = "free_cursor_core"
        private const val NOTIFICATION_ID = 4012

        fun start(context: Context) {
            val intent = Intent(context, ModelForegroundService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, ModelForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun download(context: Context, url: String) {
            val intent = Intent(context, ModelForegroundService::class.java).apply {
                action = ACTION_DOWNLOAD
                putExtra(EXTRA_MODEL_URL, url)
            }
            context.startForegroundService(intent)
        }
    }
}
