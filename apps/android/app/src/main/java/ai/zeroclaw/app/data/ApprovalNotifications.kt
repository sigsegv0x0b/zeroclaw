package ai.zeroclaw.app.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import ai.zeroclaw.app.MainActivity
import ai.zeroclaw.app.R

/**
 * Local heads-up when a tool call is waiting for approval and the app is not
 * visible.
 *
 * This is not a remote push: the chat socket lives in this app process, so it
 * only works while Android keeps that process and socket alive. If the process
 * is gone the gateway's approval window expires and the call is denied. The
 * notification still covers the common case — screen off or another app in
 * front — and tapping it brings the approval dialog back.
 */
object ApprovalNotifications {

    private const val CHANNEL_ID = "zeroclaw_approvals"
    private const val BASE_ID = 4200
    private const val DEFAULT_TIMEOUT_SECS = 120

    fun idFor(requestId: String): Int = BASE_ID + (requestId.hashCode() and 0x0FFF)

    fun show(context: Context, prompt: ApprovalPrompt) {
        if (!canPost(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        ensureChannel(manager)

        val id = idFor(prompt.requestId)
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val tool = prompt.tool.substringAfter("__", prompt.tool)
        val body = buildString {
            append(tool)
            if (prompt.argumentsSummary.isNotBlank()) {
                append(" · ")
                append(prompt.argumentsSummary)
            }
        }
        val timeout = (prompt.timeoutSecs ?: DEFAULT_TIMEOUT_SECS).coerceAtLeast(1)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Approval needed")
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .setTimeoutAfter(timeout * 1_000L)
            .build()
        runCatching { manager.notify(id, notification) }
    }

    fun cancel(context: Context, requestId: String?) {
        if (requestId.isNullOrBlank()) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { manager.cancel(idFor(requestId)) }
    }

    private fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun ensureChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Approvals",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Tool calls waiting for your approval."
            enableVibration(true)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
    }
}
