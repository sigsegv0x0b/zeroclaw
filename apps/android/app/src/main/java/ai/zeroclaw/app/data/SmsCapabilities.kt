package ai.zeroclaw.app.data

import android.content.Context
import android.provider.Telephony
import android.telephony.SmsManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * SMS send/search, mirroring OpenClaw's `sms.send` and `sms.search`.
 *
 * These are Play-restricted permissions (`SEND_SMS`, `READ_SMS`); the app is
 * sideloaded, and the operator grants them from Settings. A device without a
 * telephony radio (the emulator, most tablets) returns the platform error
 * rather than a silent success.
 */
object SmsCapabilities {

    suspend fun send(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.IO) {
            if (!DevicePermissions.granted(context, android.Manifest.permission.SEND_SMS)) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "SMS permission is not granted. Open ZeroClaw → Settings → Device " +
                        "capabilities → SMS and grant it.",
                )
            }
            val to = args.string("to")?.trim().orEmpty()
            val body = args.string("body") ?: return@withContext CapabilityOutcome(
                false,
                "",
                "body is required",
            )
            if (to.isEmpty()) return@withContext CapabilityOutcome(false, "", "to is required")
            val manager = context.getSystemService(SmsManager::class.java)
                ?: return@withContext CapabilityOutcome(false, "", "SMS service unavailable.")
            return@withContext runCatching {
                val parts = manager.divideMessage(body)
                if (parts.size > 1) {
                    manager.sendMultipartTextMessage(to, null, parts, null, null)
                } else {
                    manager.sendTextMessage(to, null, body, null, null)
                }
                CapabilityOutcome(true, "SMS queued to $to (${body.length} chars).")
            }.getOrElse { error ->
                CapabilityOutcome(false, "", "Could not send SMS: ${error.message}")
            }
        }

    suspend fun search(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.IO) {
            if (!DevicePermissions.granted(context, android.Manifest.permission.READ_SMS)) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "SMS permission is not granted. Open ZeroClaw → Settings → Device " +
                        "capabilities → SMS and grant it.",
                )
            }
            val query = args.string("query")?.trim().orEmpty()
            val limit = (args.int("limit") ?: 20).coerceIn(1, 100)
            val selection = if (query.isEmpty()) {
                null
            } else {
                "${Telephony.Sms.ADDRESS} LIKE ? OR ${Telephony.Sms.BODY} LIKE ?"
            }
            val selectionArgs = if (query.isEmpty()) {
                null
            } else {
                val like = "%${ContactsCapabilities.escapeLike(query)}%"
                arrayOf(like, like)
            }
            val rows = runCatching {
                context.contentResolver.query(
                    Telephony.Sms.CONTENT_URI,
                    arrayOf(
                        Telephony.Sms.ADDRESS,
                        Telephony.Sms.DATE,
                        Telephony.Sms.TYPE,
                        Telephony.Sms.BODY,
                    ),
                    selection,
                    selectionArgs,
                    "${Telephony.Sms.DATE} DESC",
                )?.use { cursor ->
                    val addressIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
                    val dateIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
                    val typeIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.TYPE)
                    val bodyIndex = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
                    buildList {
                        while (cursor.moveToNext() && size < limit) {
                            val body = cursor.getString(bodyIndex).orEmpty()
                            add(
                                buildString {
                                    append("- ")
                                    append(formatMillis(cursor.getLong(dateIndex)))
                                    append(" ")
                                    append(if (cursor.getInt(typeIndex) == Telephony.Sms.MESSAGE_TYPE_INBOX) "in" else "out")
                                    append("  ")
                                    append(cursor.getString(addressIndex) ?: "(unknown)")
                                    append(": ")
                                    append(body.take(200))
                                    if (body.length > 200) append("…")
                                },
                            )
                        }
                    }
                }
            }.getOrElse { error ->
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Could not read SMS: ${error.message}",
                )
            } ?: emptyList()

            if (rows.isEmpty()) {
                CapabilityOutcome(true, "No SMS matched.")
            } else {
                CapabilityOutcome(true, "${rows.size} message(s):\n" + rows.joinToString("\n"))
            }
        }

    private fun formatMillis(millis: Long): String =
        DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()),
        )
}
