package com.example.kolmafiaandroid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * Keeps the process alive while a script or command runs (autoscend can run for an hour), shows what is
 * running with a Stop action, and posts a note when something long finishes.
 */
class ScriptService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            KolEngineHost.abort()
            return START_NOT_STICKY
        }
        val label = intent?.getStringExtra("label") ?: "a script"
        // always promote at once (Android kills the app if a started foreground service does not), then step down if the command already ended
        startForeground(NOTIF_ID, build(this, label), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        running = true
        if (active.get() == 0) {
            running = false
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL = "scripts"
        private const val NOTIF_ID = 1002
        private const val DONE_ID = 1003
        const val ACTION_STOP = "com.example.kolmafiaandroid.STOP_SCRIPT"
        private val active = AtomicInteger(0)
        @Volatile private var startedAt = 0L
        @Volatile private var lastLabel = ""
        @Volatile private var running = false
        private val handler = android.os.Handler(android.os.Looper.getMainLooper())

        private fun channel(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Running scripts", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Shown while a script or command is running, with a Stop button" })
        }

        private fun build(ctx: Context, label: String): Notification {
            channel(ctx)
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            val stop = PendingIntent.getService(ctx, 1, Intent(ctx, ScriptService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.tab_prep)
                .setContentTitle("Running: $label")
                .setContentText("Tap to open Kolmob")
                .setContentIntent(open)
                .setOngoing(true)
                .addAction(0, "Stop", stop)
                .build()
        }

        /** Call when a command starts running. */
        fun begin(ctx: Context, label: String) {
            if (active.getAndIncrement() == 0) {
                startedAt = System.currentTimeMillis()
                lastLabel = label
                // quick commands (a rest, an equip) never need a service: only start one if this is still running after 3 s
                handler.postDelayed({
                    if (active.get() > 0 && !running) {
                        try {
                            androidx.core.content.ContextCompat.startForegroundService(ctx, Intent(ctx, ScriptService::class.java).putExtra("label", label.take(60)))
                        } catch (_: Throwable) { /* not allowed right now (background start): the command still runs */ }
                    }
                }, 3000)
            }
        }

        /** Call when a command ends. */
        fun end(ctx: Context) {
            if (active.decrementAndGet() > 0) return
            active.set(0)
            if (running) {
                running = false
                try { ctx.stopService(Intent(ctx, ScriptService::class.java)) } catch (_: Throwable) {}
            } // else: the service (if its start is still pending) sees active == 0 and stops itself
            val secs = (System.currentTimeMillis() - startedAt) / 1000
            if (secs >= 60) {
                try {
                    val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
                    channel(ctx)
                    ctx.getSystemService(NotificationManager::class.java).notify(DONE_ID,
                        NotificationCompat.Builder(ctx, CHANNEL)
                            .setSmallIcon(R.drawable.tab_prep)
                            .setContentTitle("Finished: $lastLabel")
                            .setContentText("Ran for ${secs / 60} min ${secs % 60} s")
                            .setContentIntent(open)
                            .setAutoCancel(true)
                            .build())
                } catch (_: Throwable) {}
            }
        }
    }
}
