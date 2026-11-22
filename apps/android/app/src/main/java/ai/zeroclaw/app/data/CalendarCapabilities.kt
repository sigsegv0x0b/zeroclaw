package ai.zeroclaw.app.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.provider.CalendarContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Calendar read/write, mirroring OpenClaw's `calendar.events` and
 * `calendar.add`. Times are epoch millis or ISO-8601; output is local ISO.
 */
object CalendarCapabilities {

    private val ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    suspend fun events(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.IO) {
            if (!DevicePermissions.granted(context, android.Manifest.permission.READ_CALENDAR)) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Calendar permission is not granted. Open ZeroClaw → Settings → Device " +
                        "capabilities → Calendar and grant it.",
                )
            }
            val days = (args.int("range_days") ?: 7).coerceIn(1, 30)
            val limit = (args.int("limit") ?: 20).coerceIn(1, 100)
            val begin = System.currentTimeMillis()
            val end = begin + days * 24L * 60 * 60 * 1000
            val builder = CalendarContract.Instances.CONTENT_URI.buildUpon()
            ContentUris.appendId(builder, begin)
            ContentUris.appendId(builder, end)
            val rows = runCatching {
                context.contentResolver.query(
                    builder.build(),
                    arrayOf(
                        CalendarContract.Instances.TITLE,
                        CalendarContract.Instances.BEGIN,
                        CalendarContract.Instances.END,
                        CalendarContract.Instances.ALL_DAY,
                        CalendarContract.Instances.EVENT_LOCATION,
                        CalendarContract.Instances.CALENDAR_DISPLAY_NAME,
                    ),
                    null,
                    null,
                    "${CalendarContract.Instances.BEGIN} ASC LIMIT $limit",
                )?.use { cursor ->
                    val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                    val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                    val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
                    val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.ALL_DAY)
                    val locationIndex =
                        cursor.getColumnIndexOrThrow(CalendarContract.Instances.EVENT_LOCATION)
                    val calendarIndex =
                        cursor.getColumnIndexOrThrow(CalendarContract.Instances.CALENDAR_DISPLAY_NAME)
                    buildList {
                        while (cursor.moveToNext()) {
                            add(
                                buildString {
                                    append("- ")
                                    append(formatMillis(cursor.getLong(beginIndex)))
                                    append("  ")
                                    append(cursor.getString(titleIndex) ?: "(untitled)")
                                    if (cursor.getInt(allDayIndex) == 1) append("  [all day]")
                                    cursor.getString(locationIndex)?.takeIf { it.isNotBlank() }
                                        ?.let { append("  @ ").append(it) }
                                    cursor.getString(calendarIndex)?.takeIf { it.isNotBlank() }
                                        ?.let { append("  (").append(it).append(")") }
                                    append("  ends ").append(formatMillis(cursor.getLong(endIndex)))
                                },
                            )
                        }
                    }
                }
            }.getOrElse { error ->
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Could not read the calendar: ${error.message}",
                )
            } ?: emptyList()

            if (rows.isEmpty()) {
                CapabilityOutcome(true, "No calendar events in the next $days day(s).")
            } else {
                CapabilityOutcome(true, "${rows.size} event(s) in the next $days day(s):\n" + rows.joinToString("\n"))
            }
        }

    suspend fun add(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.IO) {
            if (!DevicePermissions.granted(context, android.Manifest.permission.WRITE_CALENDAR)) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Calendar permission is not granted. Open ZeroClaw → Settings → Device " +
                        "capabilities → Calendar and grant it.",
                )
            }
            val title = args.string("title")?.trim().orEmpty()
            if (title.isEmpty()) return@withContext CapabilityOutcome(false, "", "title is required")
            val beginMillis = parseWhen(args.string("begin"))
                ?: return@withContext CapabilityOutcome(
                    false,
                    "",
                    "begin is required (ISO-8601 or epoch millis).",
                )
            val durationMinutes = (args.int("duration_minutes") ?: 60).coerceIn(1, 24 * 60)
            val endMillis = parseWhen(args.string("end"))
                ?: (beginMillis + durationMinutes * 60_000L)
            if (endMillis <= beginMillis) {
                return@withContext CapabilityOutcome(false, "", "end must be after begin")
            }
            val calendarId = resolveCalendarId(
                context,
                args.long("calendar_id"),
                args.string("calendar_title"),
            ) ?: return@withContext CapabilityOutcome(
                false,
                "",
                "No writable calendar was found. Set one up in the Calendar app, or pass calendar_id.",
            )
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, beginMillis)
                put(CalendarContract.Events.DTEND, endMillis)
                put(CalendarContract.Events.EVENT_TIMEZONE, ZoneId.systemDefault().id)
                put(CalendarContract.Events.ALL_DAY, if (args.bool("all_day", default = false)) 1 else 0)
                args.string("description")?.takeIf { it.isNotBlank() }
                    ?.let { put(CalendarContract.Events.DESCRIPTION, it) }
                args.string("location")?.takeIf { it.isNotBlank() }
                    ?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            }
            val uri = runCatching {
                context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
            }.getOrNull() ?: return@withContext CapabilityOutcome(
                false,
                "",
                "Could not create the event (calendar may be read-only).",
            )
            CapabilityOutcome(
                true,
                "Created \"$title\" at ${formatMillis(beginMillis)} (calendar id $calendarId, ${uri}).",
            )
        }

    private fun resolveCalendarId(
        context: Context,
        calendarId: Long?,
        title: String?,
    ): Long? {
        val resolver = context.contentResolver
        if (calendarId != null) {
            val exists = resolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                "${CalendarContract.Calendars._ID}=?",
                arrayOf(calendarId.toString()),
                null,
            )?.use { it.moveToFirst() } == true
            return calendarId.takeIf { exists }
        }
        val projection = arrayOf(CalendarContract.Calendars._ID)
        val selection = buildString {
            append("${CalendarContract.Calendars.VISIBLE}=1 AND ")
            append("${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL}>=?")
            if (!title.isNullOrBlank()) append(" AND ${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME}=?")
        }
        val selectionArgs = buildList {
            add(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR.toString())
            if (!title.isNullOrBlank()) add(title)
        }.toTypedArray()
        return resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            selection,
            selectionArgs,
            "${CalendarContract.Calendars.IS_PRIMARY} DESC, ${CalendarContract.Calendars._ID} ASC",
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
    }

    internal fun parseWhen(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null
        text.toLongOrNull()?.let { return it }
        runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        runCatching {
            LocalDateTime.parse(text).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }.getOrNull()?.let { return it }
        return null
    }

    private fun formatMillis(millis: Long): String =
        ISO.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalDateTime())
}
