package com.foundryos.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.foundryos.app.MainActivity
import com.foundryos.app.data.ApprovalItem
import com.foundryos.app.data.FoundryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object ApprovalNotifier {
    const val CHANNEL_ID = "ceo_approvals"
    const val ACTION_DECIDE = "com.foundryos.app.ACTION_DECIDE_APPROVAL"
    const val ACTION_REFRESH_UI = "com.foundryos.app.ACTION_REFRESH_UI"
    const val EXTRA_APPROVAL_ID = "approval_id"
    const val EXTRA_DECISION = "decision"
    const val EXTRA_NOTIF_ID = "notif_id"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CEO Approvals & Releases",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Human-in-the-loop release gates from your on-device AI CEOs"
            }
            nm.createNotificationChannel(channel)
        }
    }

    fun showApprovalNotification(context: Context, approval: ApprovalItem) {
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = approval.id.hashCode()

        val openIntent = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val shipIntent = PendingIntent.getBroadcast(
            context,
            notifId + 1,
            Intent(context, ApprovalActionReceiver::class.java).apply {
                action = ACTION_DECIDE
                putExtra(EXTRA_APPROVAL_ID, approval.id)
                putExtra(EXTRA_DECISION, "APPROVED")
                putExtra(EXTRA_NOTIF_ID, notifId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val rejectIntent = PendingIntent.getBroadcast(
            context,
            notifId + 2,
            Intent(context, ApprovalActionReceiver::class.java).apply {
                action = ACTION_DECIDE
                putExtra(EXTRA_APPROVAL_ID, approval.id)
                putExtra(EXTRA_DECISION, "REJECTED")
                putExtra(EXTRA_NOTIF_ID, notifId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("CEO")
            .setContentText("${approval.title} It needs your yes.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("${approval.title}\n${approval.subtitle}. It needs your yes."))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .addAction(0, "Not yet", rejectIntent)
            .addAction(0, "Ship", shipIntent)
            .build()

        try {
            nm.notify(notifId, notif)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS permission not yet granted
        }
    }
}

class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ApprovalNotifier.ACTION_DECIDE) return
        val approvalId = intent.getStringExtra(ApprovalNotifier.EXTRA_APPROVAL_ID) ?: return
        val decision = intent.getStringExtra(ApprovalNotifier.EXTRA_DECISION) ?: return
        val notifId = intent.getIntExtra(ApprovalNotifier.EXTRA_NOTIF_ID, 0)

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (notifId != 0) nm.cancel(notifId)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val repo = FoundryRepository(context.applicationContext)
                repo.decideApproval(approvalId, decision, device = "Android Notification")
            } catch (_: Exception) {
            } finally {
                context.sendBroadcast(Intent(ApprovalNotifier.ACTION_REFRESH_UI).setPackage(context.packageName))
                pendingResult.finish()
            }
        }
    }
}
