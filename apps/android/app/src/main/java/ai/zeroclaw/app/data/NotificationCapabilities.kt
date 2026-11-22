package ai.zeroclaw.app.data

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import ai.zeroclaw.app.service.NotificationListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Notification list/action commands, mirroring OpenClaw's
 * `notifications.list` and `notifications.actions`.
 *
 * The phone's notifications are only readable while the NotificationListener
 * special access is enabled; otherwise the command explains how to grant it.
 */
object NotificationCapabilities {

    suspend fun list(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.IO) {
            if (!DevicePermissions.hasNotificationAccess(context)) return@withContext DENIED
            val notifications = runCatching { NotificationListener.snapshots() }.getOrDefault(emptyList())
            if (notifications.isEmpty()) {
                return@withContext CapabilityOutcome(
                    true,
                    "Notification access is enabled, but no notifications are currently posted.",
                )
            }
            val limit = (args.int("limit") ?: 20).coerceIn(1, 50)
            val lines = notifications.take(limit).map { sbn -> describe(context, sbn) }
            CapabilityOutcome(
                true,
                "${notifications.size} posted notification(s):\n" + lines.joinToString("\n") +
                    "\nPass a key to notifications.actions with action=open|dismiss|reply.",
            )
        }

    suspend fun actions(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.IO) {
            if (!DevicePermissions.hasNotificationAccess(context)) return@withContext DENIED
            val key = args.string("key")?.trim().orEmpty()
            val action = args.string("action")?.trim()?.lowercase().orEmpty()
            if (key.isEmpty()) return@withContext CapabilityOutcome(false, "", "key is required")
            val done = when (action) {
                "open" -> NotificationListener.open(key)
                "dismiss" -> NotificationListener.dismiss(key)
                "reply" -> {
                    val text = args.string("reply_text")?.trim()
                        ?: args.string("replyText")?.trim().orEmpty()
                    if (text.isEmpty()) {
                        return@withContext CapabilityOutcome(
                            false,
                            "",
                            "reply_text is required for action=reply",
                        )
                    }
                    NotificationListener.reply(key, text)
                }
                else -> return@withContext CapabilityOutcome(
                    false,
                    "",
                    "action must be one of open, dismiss, reply",
                )
            }
            if (done) {
                CapabilityOutcome(true, "Notification action '$action' applied to $key.")
            } else {
                CapabilityOutcome(
                    false,
                    "",
                    "The notification action '$action' did not apply (the notification may be gone " +
                        "or have no such action).",
                )
            }
        }

    private fun describe(context: Context, sbn: StatusBarNotification): String {
        val notification = sbn.notification
        val extras = notification.extras
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val app = runCatching {
            context.packageManager.getApplicationLabel(
                context.packageManager.getApplicationInfo(sbn.packageName, 0),
            ).toString()
        }.getOrDefault(sbn.packageName)
        val actions = notification.actions.orEmpty().mapNotNull { it.title?.toString() }
        val time = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
            Instant.ofEpochMilli(sbn.postTime).atZone(ZoneId.systemDefault()),
        )
        return buildString {
            append("- key=").append(sbn.key)
            append("  app=").append(app)
            append("  time=").append(time)
            if (title.isNotBlank()) append("\n  title: ").append(title)
            if (text.isNotBlank()) append("\n  text: ").append(text.take(200))
            if (actions.isNotEmpty()) append("\n  actions: ").append(actions.joinToString(", "))
        }
    }

    private val DENIED = CapabilityOutcome(
        false,
        "",
        "Notification access is not enabled. Open ZeroClaw → Settings → Device capabilities → " +
            "Notification access and enable it for ZeroClaw.",
    )
}
