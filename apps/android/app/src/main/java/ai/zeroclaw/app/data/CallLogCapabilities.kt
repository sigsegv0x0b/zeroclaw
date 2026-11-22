package ai.zeroclaw.app.data

import android.content.Context
import android.provider.CallLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Call-log search, mirroring OpenClaw's `callLog.search`. `READ_CALL_LOG` is
 * Play-restricted; the app is sideloaded and the operator grants it in
 * Settings.
 */
object CallLogCapabilities {

    suspend fun search(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.IO) {
            if (!DevicePermissions.granted(context, android.Manifest.permission.READ_CALL_LOG)) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Call-log permission is not granted. Open ZeroClaw → Settings → Device " +
                        "capabilities → Call log and grant it.",
                )
            }
            val query = args.string("query")?.trim().orEmpty()
            val limit = (args.int("limit") ?: 20).coerceIn(1, 200)
            val selection = if (query.isEmpty()) {
                null
            } else {
                "${CallLog.Calls.NUMBER} LIKE ? OR ${CallLog.Calls.CACHED_NAME} LIKE ?"
            }
            val selectionArgs = if (query.isEmpty()) {
                null
            } else {
                val like = "%${ContactsCapabilities.escapeLike(query)}%"
                arrayOf(like, like)
            }
            val rows = runCatching {
                context.contentResolver.query(
                    CallLog.Calls.CONTENT_URI,
                    arrayOf(
                        CallLog.Calls.NUMBER,
                        CallLog.Calls.CACHED_NAME,
                        CallLog.Calls.TYPE,
                        CallLog.Calls.DATE,
                        CallLog.Calls.DURATION,
                    ),
                    selection,
                    selectionArgs,
                    "${CallLog.Calls.DATE} DESC",
                )?.use { cursor ->
                    val numberIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.NUMBER)
                    val nameIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.CACHED_NAME)
                    val typeIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.TYPE)
                    val dateIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DATE)
                    val durationIndex = cursor.getColumnIndexOrThrow(CallLog.Calls.DURATION)
                    buildList {
                        while (cursor.moveToNext() && size < limit) {
                            val name = cursor.getString(nameIndex)
                            add(
                                buildString {
                                    append("- ")
                                    append(formatMillis(cursor.getLong(dateIndex)))
                                    append(" ")
                                    append(typeLabel(cursor.getInt(typeIndex)))
                                    append("  ")
                                    append(name ?: cursor.getString(numberIndex) ?: "(unknown)")
                                    cursor.getString(numberIndex)?.takeIf { !name.isNullOrBlank() && it != name }
                                        ?.let { append(" <").append(it).append(">") }
                                    append("  ")
                                    append(cursor.getLong(durationIndex))
                                    append("s")
                                },
                            )
                        }
                    }
                }
            }.getOrElse { error ->
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Could not read the call log: ${error.message}",
                )
            } ?: emptyList()

            if (rows.isEmpty()) {
                CapabilityOutcome(true, "No calls matched.")
            } else {
                CapabilityOutcome(true, "${rows.size} call(s):\n" + rows.joinToString("\n"))
            }
        }

    private fun typeLabel(type: Int): String = when (type) {
        CallLog.Calls.INCOMING_TYPE -> "incoming"
        CallLog.Calls.OUTGOING_TYPE -> "outgoing"
        CallLog.Calls.MISSED_TYPE -> "missed"
        CallLog.Calls.REJECTED_TYPE -> "rejected"
        CallLog.Calls.BLOCKED_TYPE -> "blocked"
        CallLog.Calls.VOICEMAIL_TYPE -> "voicemail"
        else -> "other"
    }

    private fun formatMillis(millis: Long): String =
        DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()),
        )
}
