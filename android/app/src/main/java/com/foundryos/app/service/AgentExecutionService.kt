package com.foundryos.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.foundryos.app.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Persistent Foreground Service + Partial WakeLock so Android NEVER kills autonomous bots
 * mid-task when the screen turns off or the Founder switches apps.
 */
class AgentExecutionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_ALL) {
            onStopRequested?.invoke()
            releaseWakeLock()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val label = intent?.getStringExtra(EXTRA_STATUS) ?: currentStatusLabel
        acquireWakeLock()
        val notif = buildNotification(label)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        drainQueue()
        return START_STICKY
    }

    private fun acquireWakeLock() {
        runCatching {
            if (wakeLock == null) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CEO:AutonomousAgentWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(30 * 60 * 1000L) // safety cap 30m, released early when finished
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
    }

    private fun drainQueue() {
        if (activeWorkers.get() > 0) return
        val next = pendingTasks.poll() ?: run {
            if (activeWorkers.get() == 0) {
                releaseWakeLock()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            return
        }
        activeWorkers.incrementAndGet()
        serviceScope.launch {
            try {
                updateNotification(next.first)
                next.second()
            } catch (_: Exception) {
            } finally {
                activeWorkers.decrementAndGet()
                if (pendingTasks.isNotEmpty()) {
                    drainQueue()
                } else {
                    releaseWakeLock()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    private fun updateNotification(status: String) {
        currentStatusLabel = status
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIF_ID, buildNotification(status))
        }
    }

    private fun buildNotification(status: String): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            101,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = PendingIntent.getService(
            this,
            102,
            Intent(this, AgentExecutionService::class.java).apply { action = ACTION_STOP_ALL },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_popup_sync)
            .setContentTitle("CEO · Exécution Autonome")
            .setContentText(status.ifBlank { "Agents en cours d'exécution..." })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(openIntent)
            .addAction(0, "Stop", stopIntent)
            .build()
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "ceo_agent_runner"
        const val NOTIF_ID = 4102
        const val ACTION_STOP_ALL = "com.foundryos.app.ACTION_STOP_AGENTS"
        const val EXTRA_STATUS = "extra_status"

        private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val pendingTasks = ConcurrentLinkedQueue<Pair<String, suspend () -> Unit>>()
        private val activeWorkers = AtomicInteger(0)

        @Volatile
        private var currentStatusLabel: String = "Agents en cours..."

        @Volatile
        var onStopRequested: (() -> Unit)? = null

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                    nm.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            "CEO Autonomous Agent Runner",
                            NotificationManager.IMPORTANCE_LOW,
                        ).apply {
                            description = "Keeps autonomous bots running reliably in the background until tasks complete."
                        }
                    )
                }
            }
        }

        fun enqueueTask(context: Context, label: String, block: suspend () -> Unit) {
            ensureChannel(context)
            currentStatusLabel = label
            pendingTasks.add(label to block)
            val intent = Intent(context, AgentExecutionService::class.java).apply {
                putExtra(EXTRA_STATUS, label)
            }
            val started = runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            }.getOrDefault(false)

            // Fallback in headless/unit-test environments where Android Service lifecycle doesn't pump
            if (!started) {
                val task = pendingTasks.poll() ?: return
                serviceScope.launch { runCatching { task.second() } }
            }
        }

        fun updateStatus(context: Context, status: String) {
            currentStatusLabel = status
            if (activeWorkers.get() > 0) {
                runCatching {
                    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(android.R.drawable.ic_popup_sync)
                        .setContentTitle("CEO · Exécution Autonome")
                        .setContentText(status)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .build()
                    nm.notify(NOTIF_ID, notif)
                }
            }
        }
    }
}
