package com.foundryos.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import com.foundryos.app.MainActivity
import com.foundryos.app.data.ApprovalItem
import com.foundryos.app.data.ChoicePromptData
import com.foundryos.app.data.FoundryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object ApprovalNotifier {
    const val CHANNEL_ID = "ceo_approvals"
    const val ACTION_DECIDE = "com.foundryos.app.ACTION_DECIDE_APPROVAL"
    const val ACTION_ANSWER_CHOICE = "com.foundryos.app.ACTION_ANSWER_CHOICE"
    const val ACTION_REFRESH_UI = "com.foundryos.app.ACTION_REFRESH_UI"
    const val EXTRA_APPROVAL_ID = "approval_id"
    const val EXTRA_DECISION = "decision"
    const val EXTRA_NOTIF_ID = "notif_id"
    const val EXTRA_COMPANY_ID = "company_id"
    const val EXTRA_MESSAGE_ID = "message_id"
    const val EXTRA_CHOICE_OPTION_ID = "choice_option_id"
    const val EXTRA_CHOICE_VALUE = "choice_value"
    const val KEY_DIRECT_REPLY = "key_ceo_choice_reply"

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CEO Decisions & Interactive Questions",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Interactive CEO choice questions and release gates from your on-device AI CEOs"
            }
            nm.createNotificationChannel(channel)
        }
    }

    /**
     * Posts a high-priority Android notification for an interactive CEO choice prompt so the Founder
     * can pick an option in 1 tap (or reply inline via RemoteInput) directly from the notification shade.
     */
    fun showChoiceNotification(
        context: Context,
        companyId: String,
        companyName: String,
        messageId: String,
        question: String,
        choices: ChoicePromptData,
    ) {
        if (choices.options.isEmpty()) return
        ensureChannel(context)
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notifId = ("choice_$messageId").hashCode()

        val openIntent = PendingIntent.getActivity(
            context,
            notifId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val bigBody = buildString {
            append(question.trim())
            append("\n\n")
            choices.options.forEachIndexed { i, opt ->
                append("${i + 1}. ${opt.label}")
                if (opt.description.isNotBlank()) append(" — ${opt.description}")
                if (i < choices.options.lastIndex) append("\n")
            }
        }

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("CEO · ${companyName.ifBlank { "Question" }}")
            .setContentText(question.trim().take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigBody))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(openIntent)

        // Android supports up to 3 notification action buttons:
        // - Up to 3 options -> show direct 1-tap buttons (or 2 buttons + inline RemoteInput reply when > 3 or 2 options)
        val directButtonsCount = if (choices.options.size == 3) 3 else minOf(2, choices.options.size)
        choices.options.take(directButtonsCount).forEachIndexed { idx, opt ->
            val actionIntent = PendingIntent.getBroadcast(
                context,
                notifId + 10 + idx,
                Intent(context, ApprovalActionReceiver::class.java).apply {
                    action = ACTION_ANSWER_CHOICE
                    putExtra(EXTRA_COMPANY_ID, companyId)
                    putExtra(EXTRA_MESSAGE_ID, messageId)
                    putExtra(EXTRA_CHOICE_OPTION_ID, opt.id)
                    putExtra(EXTRA_CHOICE_VALUE, opt.value)
                    putExtra(EXTRA_NOTIF_ID, notifId)
                },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val shortLabel = "${idx + 1}. ${opt.label}".take(24)
            builder.addAction(0, shortLabel, actionIntent)
        }

        if (directButtonsCount < 3) {
            val remoteInput = RemoteInput.Builder(KEY_DIRECT_REPLY)
                .setLabel("Répondre au CEO (ou n° 1-${choices.options.size})...")
                .build()
            val replyFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val replyIntent = PendingIntent.getBroadcast(
                context,
                notifId + 99,
                Intent(context, ApprovalActionReceiver::class.java).apply {
                    action = ACTION_ANSWER_CHOICE
                    putExtra(EXTRA_COMPANY_ID, companyId)
                    putExtra(EXTRA_MESSAGE_ID, messageId)
                    putExtra(EXTRA_NOTIF_ID, notifId)
                },
                replyFlags,
            )
            val replyAction = NotificationCompat.Action.Builder(0, "Répondre", replyIntent)
                .addRemoteInput(remoteInput)
                .build()
            builder.addAction(replyAction)
        }

        try {
            nm.notify(notifId, builder.build())
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS permission not yet granted
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
        val appCtx = context.applicationContext
        val notifId = intent.getIntExtra(ApprovalNotifier.EXTRA_NOTIF_ID, 0)
        val nm = appCtx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        when (intent.action) {
            ApprovalNotifier.ACTION_DECIDE -> {
                val approvalId = intent.getStringExtra(ApprovalNotifier.EXTRA_APPROVAL_ID) ?: return
                val decision = intent.getStringExtra(ApprovalNotifier.EXTRA_DECISION) ?: return
                if (notifId != 0) nm.cancel(notifId)

                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val repo = FoundryRepository(appCtx)
                        repo.decideApproval(approvalId, decision, device = "Android Notification")
                    } catch (_: Exception) {
                    } finally {
                        appCtx.sendBroadcast(Intent(ApprovalNotifier.ACTION_REFRESH_UI).setPackage(appCtx.packageName))
                        pendingResult.finish()
                    }
                }
            }

            ApprovalNotifier.ACTION_ANSWER_CHOICE -> {
                val companyId = intent.getStringExtra(ApprovalNotifier.EXTRA_COMPANY_ID) ?: return
                val messageId = intent.getStringExtra(ApprovalNotifier.EXTRA_MESSAGE_ID).orEmpty()
                val remoteReply = RemoteInput.getResultsFromIntent(intent)
                    ?.getCharSequence(ApprovalNotifier.KEY_DIRECT_REPLY)
                    ?.toString()
                    ?.trim()
                val directOptionId = intent.getStringExtra(ApprovalNotifier.EXTRA_CHOICE_OPTION_ID).orEmpty()
                val directChoiceValue = intent.getStringExtra(ApprovalNotifier.EXTRA_CHOICE_VALUE).orEmpty()

                val answerText = remoteReply?.ifBlank { null } ?: directChoiceValue.ifBlank { null } ?: return
                if (notifId != 0) nm.cancel(notifId)

                // Immediately mark choice selected in DB and run CEO + full autonomous team in ForegroundService!
                val pendingResult = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val repo = FoundryRepository(appCtx)
                        if (messageId.isNotBlank()) {
                            val resolvedOptId = directOptionId.ifBlank {
                                val num = answerText.trim().toIntOrNull()
                                if (num != null && num >= 1) "opt_$num" else ""
                            }
                            if (resolvedOptId.isNotBlank()) {
                                repo.markMessageChoiceSelected(messageId, resolvedOptId)
                            }
                        }
                        appCtx.sendBroadcast(Intent(ApprovalNotifier.ACTION_REFRESH_UI).setPackage(appCtx.packageName))

                        AgentExecutionService.enqueueTask(appCtx, "CEO executing your notification choice...") {
                            try {
                                repo.sendFounderMessage(
                                    companyId = companyId,
                                    text = answerText,
                                    targetBotId = null, // Always route notification choices to CEO for full autonomous orchestration
                                    onStatusUpdate = { st ->
                                        AgentExecutionService.updateStatus(appCtx, st)
                                        appCtx.sendBroadcast(Intent(ApprovalNotifier.ACTION_REFRESH_UI).setPackage(appCtx.packageName))
                                    },
                                )
                            } finally {
                                appCtx.sendBroadcast(Intent(ApprovalNotifier.ACTION_REFRESH_UI).setPackage(appCtx.packageName))
                            }
                        }
                    } catch (_: Exception) {
                    } finally {
                        pendingResult.finish()
                    }
                }
            }
        }
    }
}
