package ai.zeroclaw.app.data

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@Serializable
data class NodeCapabilityDef(
    val name: String,
    val description: String,
    val parameters: JsonObject,
)

@Serializable
data class RegisterFrame(
    val type: String = "register",
    @SerialName("node_id") val nodeId: String,
    val capabilities: List<NodeCapabilityDef>,
)

/** One media attachment returned with an invocation result. */
@Serializable
data class NodeAttachment(
    /** Disposition; the gateway surfaces `image` today. */
    val kind: String,
    val mime: String,
    @SerialName("data_b64") val dataB64: String,
)

@Serializable
data class InvokeResultFrame(
    val type: String = "result",
    @SerialName("call_id") val callId: String,
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val attachments: List<NodeAttachment> = emptyList(),
)

data class CapabilityOutcome(
    val success: Boolean,
    val output: String,
    val error: String? = null,
    val attachments: List<NodeAttachment> = emptyList(),
)

/**
 * Capabilities this phone advertises to the gateway. Each name becomes an
 * agent tool (`node_<id>__file_list`), so the descriptions and parameter
 * schemas are the contract the model reads: say that the action runs on the
 * phone, and tell it to ask when the user has not chosen a target (phone vs
 * the ZeroClaw host) so it never guesses.
 */
object DeviceCapabilities {

    const val ALARM_SET = "alarm.set"
    const val TIMER_SET = "timer.set"
    const val ALARM_LIST = "alarm.list"
    const val TIMER_LIST = "timer.list"
    const val ALARM_DELETE = "alarm.delete"
    const val TIMER_DELETE = "timer.delete"
    const val TIME_GET = "time.get"
    const val DATE_GET = "date.get"
    const val LOCATION_GET = "location.get"
    const val FILE_LIST = "file.list"
    const val FILE_READ = "file.read"
    const val FILE_CREATE = "file.create"
    const val GALLERY_LIST = "gallery.list"
    const val GALLERY_READ = "gallery.read"
    const val DEVICE_INFO = "device.info"
    const val DEVICE_STATUS = "device.status"
    const val DEVICE_HEALTH = "device.health"
    const val DEVICE_PERMISSIONS = "device.permissions"
    const val DEVICE_APPS = "device.apps"
    const val SYSTEM_NOTIFY = "system.notify"
    const val PHOTOS_LATEST = "photos.latest"
    const val CONTACTS_SEARCH = "contacts.search"
    const val CONTACTS_ADD = "contacts.add"
    const val CALENDAR_EVENTS = "calendar.events"
    const val CALENDAR_ADD = "calendar.add"
    const val MOTION_ACTIVITY = "motion.activity"
    const val MOTION_PEDOMETER = "motion.pedometer"
    const val SMS_SEND = "sms.send"
    const val SMS_SEARCH = "sms.search"
    const val CALL_LOG_SEARCH = "callLog.search"
    const val NOTIFICATIONS_LIST = "notifications.list"
    const val NOTIFICATIONS_ACTIONS = "notifications.actions"
    const val CAMERA_LIST = "camera.list"
    const val CAMERA_SNAP = "camera.snap"
    const val CAMERA_CLIP = "camera.clip"
    const val MOBILE_UI_OBSERVE = "mobile.ui.observe"
    const val MOBILE_UI_ACT = "mobile.ui.act"
    const val TALK_PTT_START = "talk.ptt.start"
    const val TALK_PTT_STOP = "talk.ptt.stop"
    const val TALK_PTT_CANCEL = "talk.ptt.cancel"
    const val TALK_PTT_ONCE = "talk.ptt.once"

    private const val PHONE_NOTE = " This runs on the phone, not on the ZeroClaw host."

    private fun capability(
        name: String,
        description: String,
        parameters: String,
    ): NodeCapabilityDef = NodeCapabilityDef(
        name = name,
        description = description + PHONE_NOTE,
        parameters = zcJson.parseToJsonElement(parameters) as JsonObject,
    )

    val definitions: List<NodeCapabilityDef> = listOf(
        capability(
            ALARM_SET,
            "Set an alarm on this Android phone's clock app at a local wall-clock time. " +
                "Pass hour and minute in 24-hour local time; skip_ui=true creates it " +
                "without a confirmation screen.",
            """
            {
              "type": "object",
              "properties": {
                "hour": { "type": "integer", "minimum": 0, "maximum": 23,
                          "description": "Local wall-clock hour, 24-hour" },
                "minute": { "type": "integer", "minimum": 0, "maximum": 59 },
                "label": { "type": "string", "description": "Text shown on the alarm" },
                "skip_ui": { "type": "boolean", "default": true,
                             "description": "Create the alarm without opening a confirmation screen" },
                "vibrate": { "type": "boolean", "default": true }
              },
              "required": ["hour", "minute"]
            }
            """.trimIndent(),
        ),
        capability(
            TIMER_SET,
            "Start a countdown timer on this Android phone's clock app. " +
                "Use this for 'in N minutes' or 'timer for N seconds' requests; prefer " +
                "it over an alarm when the request is a duration rather than a clock time.",
            """
            {
              "type": "object",
              "properties": {
                "seconds": { "type": "integer", "minimum": 1, "maximum": 86400,
                             "description": "Countdown length in seconds" },
                "label": { "type": "string", "description": "Text shown with the timer" },
                "skip_ui": { "type": "boolean", "default": true,
                             "description": "Start the timer without opening a confirmation screen" }
              },
              "required": ["seconds"]
            }
            """.trimIndent(),
        ),
        capability(
            ALARM_LIST,
            "List the alarms that ZeroClaw created on this phone. Android gives no API to " +
                "read the clock app's own alarm list, so alarms the user set by hand are not " +
                "visible here; say that when the list looks incomplete.",
            """
            { "type": "object", "properties": {} }
            """.trimIndent(),
        ),
        capability(
            TIMER_LIST,
            "List the timers that ZeroClaw started on this phone. Android gives no API to " +
                "read the clock app's running timers, so only ZeroClaw-created entries appear.",
            """
            { "type": "object", "properties": {} }
            """.trimIndent(),
        ),
        capability(
            ALARM_DELETE,
            "Forget a ZeroClaw-created alarm by its id (from alarm.list) and send the clock " +
                "app's dismiss request. Whether the clock app removes a pending alarm varies " +
                "by vendor, so do not claim it is cancelled unless the tool output says so.",
            """
            {
              "type": "object",
              "properties": {
                "id": { "type": "string", "description": "Entry id from alarm.list" },
                "label": { "type": "string", "description": "Optional label match when no id is known" }
              }
            }
            """.trimIndent(),
        ),
        capability(
            TIMER_DELETE,
            "Forget a ZeroClaw-created timer by its id (from timer.list). Android exposes no " +
                "intent that cancels a running third-party timer, so this only removes " +
                "ZeroClaw's record; never claim the countdown stopped.",
            """
            {
              "type": "object",
              "properties": {
                "id": { "type": "string", "description": "Entry id from timer.list" },
                "label": { "type": "string", "description": "Optional label match when no id is known" }
              }
            }
            """.trimIndent(),
        ),
        capability(
            TIME_GET,
            "Read the phone's current local time, time zone and epoch milliseconds.",
            """
            { "type": "object", "properties": {} }
            """.trimIndent(),
        ),
        capability(
            DATE_GET,
            "Read the phone's current local date and weekday.",
            """
            { "type": "object", "properties": {} }
            """.trimIndent(),
        ),
        capability(
            LOCATION_GET,
            "Read the phone's current location (latitude, longitude, accuracy and provider). " +
                "Requires the operator to grant the Location permission in ZeroClaw Settings.",
            """
            {
              "type": "object",
              "properties": {
                "timeout_secs": { "type": "integer", "minimum": 1, "maximum": 30, "default": 10,
                                  "description": "How long to wait for a fresh fix" },
                "max_age_secs": { "type": "integer", "minimum": 0, "maximum": 3600, "default": 300,
                                  "description": "Reuse a cached fix at most this old" }
              }
            }
            """.trimIndent(),
        ),
        capability(
            FILE_LIST,
            "List files in the folder the operator granted to ZeroClaw through the system " +
                "folder picker. `path` is relative to that folder (empty means its root).",
            """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "Relative directory path; omit for the root" }
              }
            }
            """.trimIndent(),
        ),
        capability(
            FILE_READ,
            "Read a text file from the operator-granted folder. Binary files come back as " +
                "base64 with a note; size is capped.",
            """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "Relative file path inside the granted folder" }
              },
              "required": ["path"]
            }
            """.trimIndent(),
        ),
        capability(
            FILE_CREATE,
            "Create or overwrite a text file inside the operator-granted folder. Parent " +
                "directories are created as needed.",
            """
            {
              "type": "object",
              "properties": {
                "path": { "type": "string", "description": "Relative file path inside the granted folder" },
                "content": { "type": "string", "description": "UTF-8 text to write" },
                "overwrite": { "type": "boolean", "default": false,
                               "description": "Replace the file if it already exists" }
              },
              "required": ["path", "content"]
            }
            """.trimIndent(),
        ),
        capability(
            GALLERY_LIST,
            "List images from this phone's photo library (newest first). Requires the " +
                "operator to grant the Photos permission in ZeroClaw Settings. Ids from this " +
                "list feed gallery.read.",
            """
            {
              "type": "object",
              "properties": {
                "limit": { "type": "integer", "minimum": 1, "maximum": 100, "default": 25 }
              }
            }
            """.trimIndent(),
        ),
        capability(
            GALLERY_READ,
            "Read one image from this phone's photo library by id (from gallery.list) and " +
                "attach it to the reply so you can actually look at it. The image is " +
                "downscaled to a sane size before it leaves the phone.",
            """
            {
              "type": "object",
              "properties": {
                "id": { "type": "string", "description": "MediaStore id from gallery.list" }
              },
              "required": ["id"]
            }
            """.trimIndent(),
        ),

        capability(
            DEVICE_INFO,
            "Read this phone's manufacturer, model, Android version, build id, locale and screen size.",
            """
            { "type": "object", "properties": {} }
            """.trimIndent(),
        ),
        capability(
            DEVICE_STATUS,
            "Read this phone's battery, charging, network transport, screen/lock state, storage and uptime.",
            """
            { "type": "object", "properties": {} }
            """.trimIndent(),
        ),
        capability(
            DEVICE_HEALTH,
            "Read this phone's memory, storage, battery health/temperature and thermal status.",
            """
            { "type": "object", "properties": {} }
            """.trimIndent(),
        ),
        capability(
            DEVICE_PERMISSIONS,
            "List which ZeroClaw device permissions are granted on this phone, including the " +
                "special notification-access and accessibility grants.",
            """
            { "type": "object", "properties": {} }
            """.trimIndent(),
        ),
        capability(
            DEVICE_APPS,
            "List launchable apps installed on this phone.",
            """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string", "description": "Filter by app label or package substring" },
                "include_system": { "type": "boolean", "default": false },
                "limit": { "type": "integer", "minimum": 1, "maximum": 200, "default": 50 }
              }
            }
            """.trimIndent(),
        ),
        capability(
            SYSTEM_NOTIFY,
            "Post a notification on this phone with a title and body.",
            """
            {
              "type": "object",
              "properties": {
                "title": { "type": "string" },
                "body": { "type": "string" },
                "sound": { "type": "string", "description": "Optional sound hint (best effort)" },
                "priority": { "type": "string", "enum": ["min", "low", "default", "high"] }
              },
              "required": ["title", "body"]
            }
            """.trimIndent(),
        ),
        capability(
            PHOTOS_LATEST,
            "Return the newest images from this phone's photo library as attachments you can see.",
            """
            {
              "type": "object",
              "properties": {
                "limit": { "type": "integer", "minimum": 1, "maximum": 20, "default": 5 },
                "maxWidth": { "type": "integer", "minimum": 240, "maximum": 4096, "default": 1024 },
                "quality": { "type": "number", "minimum": 0.1, "maximum": 1.0, "default": 0.85 }
              }
            }
            """.trimIndent(),
        ),
        capability(
            CONTACTS_SEARCH,
            "Search this phone's contacts by display name. Returns phone numbers and emails.",
            """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string", "description": "Name substring; empty lists recent contacts" },
                "limit": { "type": "integer", "minimum": 1, "maximum": 50, "default": 10 }
              }
            }
            """.trimIndent(),
        ),
        capability(
            CONTACTS_ADD,
            "Create a contact on this phone.",
            """
            {
              "type": "object",
              "properties": {
                "given_name": { "type": "string" },
                "family_name": { "type": "string" },
                "display_name": { "type": "string" },
                "phone_numbers": { "type": "array", "items": { "type": "string" } },
                "emails": { "type": "array", "items": { "type": "string" } }
              }
            }
            """.trimIndent(),
        ),
        capability(
            CALENDAR_EVENTS,
            "List events from this phone's calendars in the next N days.",
            """
            {
              "type": "object",
              "properties": {
                "range_days": { "type": "integer", "minimum": 1, "maximum": 30, "default": 7 },
                "limit": { "type": "integer", "minimum": 1, "maximum": 100, "default": 20 }
              }
            }
            """.trimIndent(),
        ),
        capability(
            CALENDAR_ADD,
            "Create a calendar event on this phone. Times are ISO-8601 or epoch millis.",
            """
            {
              "type": "object",
              "properties": {
                "title": { "type": "string" },
                "begin": { "type": "string", "description": "ISO-8601 or epoch millis" },
                "end": { "type": "string", "description": "ISO-8601 or epoch millis; defaults to begin + duration" },
                "duration_minutes": { "type": "integer", "minimum": 1, "default": 60 },
                "calendar_id": { "type": "integer" },
                "calendar_title": { "type": "string" },
                "description": { "type": "string" },
                "location": { "type": "string" },
                "all_day": { "type": "boolean", "default": false }
              },
              "required": ["title", "begin"]
            }
            """.trimIndent(),
        ),
        capability(
            MOTION_ACTIVITY,
            "Classify this phone's current motion (stationary, walking or running) from its accelerometer.",
            """
            { "type": "object", "properties": {} }
            """.trimIndent(),
        ),
        capability(
            MOTION_PEDOMETER,
            "Read this phone's step counter (steps since boot).",
            """
            { "type": "object", "properties": {} }
            """.trimIndent(),
        ),
        capability(
            SMS_SEND,
            "Send an SMS from this phone.",
            """
            {
              "type": "object",
              "properties": {
                "to": { "type": "string", "description": "Recipient phone number" },
                "body": { "type": "string" }
              },
              "required": ["to", "body"]
            }
            """.trimIndent(),
        ),
        capability(
            SMS_SEARCH,
            "Search this phone's SMS messages by address or body substring.",
            """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string" },
                "limit": { "type": "integer", "minimum": 1, "maximum": 100, "default": 20 }
              }
            }
            """.trimIndent(),
        ),
        capability(
            CALL_LOG_SEARCH,
            "Search this phone's call log by number or contact name.",
            """
            {
              "type": "object",
              "properties": {
                "query": { "type": "string" },
                "limit": { "type": "integer", "minimum": 1, "maximum": 200, "default": 20 }
              }
            }
            """.trimIndent(),
        ),
        capability(
            NOTIFICATIONS_LIST,
            "List notifications currently posted on this phone. Requires the operator to enable " +
                "Notification access for ZeroClaw.",
            """
            {
              "type": "object",
              "properties": {
                "limit": { "type": "integer", "minimum": 1, "maximum": 50, "default": 20 }
              }
            }
            """.trimIndent(),
        ),
        capability(
            NOTIFICATIONS_ACTIONS,
            "Open, dismiss or reply to a posted notification by its key (from notifications.list).",
            """
            {
              "type": "object",
              "properties": {
                "key": { "type": "string" },
                "action": { "type": "string", "enum": ["open", "dismiss", "reply"] },
                "reply_text": { "type": "string", "description": "Required for action=reply" }
              },
              "required": ["key", "action"]
            }
            """.trimIndent(),
        ),


        capability(
            CAMERA_LIST,
            "List this phone's cameras and their facing direction.",
            """
            { "type": "object", "properties": {} }
            """.trimIndent(),
        ),
        capability(
            CAMERA_SNAP,
            "Take a photo with this phone's camera and attach it to the result. The app must " +
                "be in the foreground.",
            """
            {
              "type": "object",
              "properties": {
                "facing": { "type": "string", "enum": ["back", "front"], "default": "back" },
                "maxWidth": { "type": "integer", "minimum": 320, "maximum": 4096, "default": 1280 }
              }
            }
            """.trimIndent(),
        ),
        capability(
            CAMERA_CLIP,
            "Record a short video with this phone's camera. The file stays on the phone and is " +
                "not attached to the tool result.",
            """
            {
              "type": "object",
              "properties": {
                "facing": { "type": "string", "enum": ["back", "front"], "default": "back" },
                "durationMs": { "type": "integer", "minimum": 500, "maximum": 15000, "default": 3000 }
              }
            }
            """.trimIndent(),
        ),
        capability(
            MOBILE_UI_OBSERVE,
            "Snapshot this phone's active screen through the accessibility service. Returns a " +
                "snapshot id and the visible nodes with refs, text, ids and bounds. Requires " +
                "Accessibility to be enabled for ZeroClaw.",
            """
            { "type": "object", "properties": {} }
            """.trimIndent(),
        ),
        capability(
            MOBILE_UI_ACT,
            "Act on the last accessibility snapshot: click a ref, set text, scroll, tap or " +
                "swipe coordinates, or perform a global action (back, home, recents, " +
                "notifications, quick_settings).",
            """
            {
              "type": "object",
              "properties": {
                "snapshot_id": { "type": "string" },
                "action": {
                  "type": "object",
                  "properties": {
                    "type": { "type": "string",
                              "enum": ["click", "set_text", "scroll", "tap", "swipe", "global"] },
                    "ref": { "type": "string" },
                    "text": { "type": "string" },
                    "direction": { "type": "string", "enum": ["forward", "backward"] },
                    "x": { "type": "integer" },
                    "y": { "type": "integer" },
                    "x1": { "type": "integer" },
                    "y1": { "type": "integer" },
                    "x2": { "type": "integer" },
                    "y2": { "type": "integer" },
                    "duration_ms": { "type": "integer" },
                    "name": { "type": "string",
                              "enum": ["back", "home", "recents", "notifications", "quick_settings"] }
                  },
                  "required": ["type"]
                }
              },
              "required": ["snapshot_id", "action"]
            }
            """.trimIndent(),
        ),
        capability(
            TALK_PTT_ONCE,
            "Record one spoken utterance with this phone's microphone and return the transcript. " +
                "Use this when the user wants to talk instead of typing.",
            """
            {
              "type": "object",
              "properties": {
                "timeout_secs": { "type": "integer", "minimum": 3, "maximum": 30, "default": 15 }
              }
            }
            """.trimIndent(),
        ),
        capability(
            TALK_PTT_START,
            "Start a push-to-talk capture session on this phone. Call talk.ptt.stop to get the " +
                "transcript or talk.ptt.cancel to discard it.",
            """
            { "type": "object", "properties": {} }
            """.trimIndent(),
        ),
        capability(
            TALK_PTT_STOP,
            "Stop the active push-to-talk session and return its transcript.",
            """
            { "type": "object", "properties": {} }
            """.trimIndent(),
        ),
        capability(
            TALK_PTT_CANCEL,
            "Cancel the active push-to-talk session without returning a transcript.",
            """
            { "type": "object", "properties": {} }
            """.trimIndent(),
        ),
    )

    suspend fun execute(context: Context, capability: String, args: JsonObject): CapabilityOutcome =
        when (capability) {
            ALARM_SET -> setAlarm(context, args)
            TIMER_SET -> setTimer(context, args)
            ALARM_LIST -> listTracked(context, "alarm")
            TIMER_LIST -> listTracked(context, "timer")
            ALARM_DELETE -> deleteTracked(context, "alarm", args)
            TIMER_DELETE -> deleteTracked(context, "timer", args)
            TIME_GET -> currentTime()
            DATE_GET -> currentDate()
            LOCATION_GET -> LocationCapability.read(context, args)
            FILE_LIST -> FileCapabilities.list(context, args)
            FILE_READ -> FileCapabilities.read(context, args)
            FILE_CREATE -> FileCapabilities.create(context, args)
            GALLERY_LIST -> GalleryCapabilities.list(context, args)
            GALLERY_READ -> GalleryCapabilities.read(context, args)
            PHOTOS_LATEST -> GalleryCapabilities.latest(context, args)
            DEVICE_INFO -> DeviceInfoCapabilities.info(context)
            DEVICE_STATUS -> DeviceInfoCapabilities.status(context)
            DEVICE_HEALTH -> DeviceInfoCapabilities.health(context)
            DEVICE_PERMISSIONS -> DeviceInfoCapabilities.permissions(context)
            DEVICE_APPS -> DeviceInfoCapabilities.apps(context, args)
            SYSTEM_NOTIFY -> DeviceInfoCapabilities.notify(context, args)
            CONTACTS_SEARCH -> ContactsCapabilities.search(context, args)
            CONTACTS_ADD -> ContactsCapabilities.add(context, args)
            CALENDAR_EVENTS -> CalendarCapabilities.events(context, args)
            CALENDAR_ADD -> CalendarCapabilities.add(context, args)
            MOTION_ACTIVITY -> MotionCapabilities.activity(context, args)
            MOTION_PEDOMETER -> MotionCapabilities.pedometer(context, args)
            SMS_SEND -> SmsCapabilities.send(context, args)
            SMS_SEARCH -> SmsCapabilities.search(context, args)
            CALL_LOG_SEARCH -> CallLogCapabilities.search(context, args)
            NOTIFICATIONS_LIST -> NotificationCapabilities.list(context, args)
            NOTIFICATIONS_ACTIONS -> NotificationCapabilities.actions(context, args)
            CAMERA_LIST -> CameraCapabilities.list(context)
            CAMERA_SNAP -> CameraCapabilities.snap(context, args)
            CAMERA_CLIP -> CameraCapabilities.clip(context, args)
            MOBILE_UI_OBSERVE -> MobileUiCapabilities.observe(context)
            MOBILE_UI_ACT -> MobileUiCapabilities.act(context, args)
            TALK_PTT_ONCE -> VoiceCapabilities.once(context, args)
            TALK_PTT_START -> VoiceCapabilities.start(context)
            TALK_PTT_STOP -> VoiceCapabilities.stop()
            TALK_PTT_CANCEL -> VoiceCapabilities.cancel()
            else -> CapabilityOutcome(false, "", "Unsupported capability: $capability")
        }

    // ── Clock ──────────────────────────────────────────────────────────────

    private suspend fun setAlarm(context: Context, args: JsonObject): CapabilityOutcome {
        val hour = args.int("hour")
        val minute = args.int("minute")
        if (hour == null || hour !in 0..23) {
            return CapabilityOutcome(false, "", "hour must be 0-23")
        }
        if (minute == null || minute !in 0..59) {
            return CapabilityOutcome(false, "", "minute must be 0-59")
        }
        val label = args.string("label")
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, args.bool("skip_ui", default = true))
            putExtra(AlarmClock.EXTRA_VIBRATE, args.bool("vibrate", default = true))
            label?.takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val time = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
        val outcome = launch(context, intent, "Alarm set for $time on this phone.")
        if (!outcome.success) return outcome
        val entry = ClockStore.add(context, "alarm", label, hour = hour, minute = minute)
        return outcome.copy(
            output = outcome.output + " Tracked as alarm ${entry.id} (alarm.list).",
        )
    }

    private suspend fun setTimer(context: Context, args: JsonObject): CapabilityOutcome {
        val seconds = args.int("seconds")
        if (seconds == null || seconds !in 1..86_400) {
            return CapabilityOutcome(false, "", "seconds must be between 1 and 86400")
        }
        val label = args.string("label")
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, args.bool("skip_ui", default = true))
            label?.takeIf { it.isNotBlank() }?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val spoken = formatDuration(seconds)
        val outcome = launch(context, intent, "Started a $spoken timer on this phone.")
        if (!outcome.success) return outcome
        val entry = ClockStore.add(context, "timer", label, seconds = seconds)
        return outcome.copy(
            output = outcome.output + " Tracked as timer ${entry.id} (timer.list).",
        )
    }

    private suspend fun listTracked(context: Context, kind: String): CapabilityOutcome {
        val entries = ClockStore.list(context, kind)
        val plural = if (kind == "alarm") "alarm" else "timer"
        if (entries.isEmpty()) {
            return CapabilityOutcome(
                true,
                "No ZeroClaw-created ${plural}s are tracked on this phone. " +
                    "Android does not expose the clock app's own ${plural}s to other apps.",
            )
        }
        val lines = entries.joinToString("\n") { entry ->
            buildString {
                append("- id=${entry.id}")
                if (entry.hour != null && entry.minute != null) {
                    append(" at=")
                    append(String.format(Locale.US, "%02d:%02d", entry.hour, entry.minute))
                }
                if (entry.seconds != null) append(" seconds=${entry.seconds}")
                entry.label?.let { append(" label=\"$it\"") }
                append(" created=")
                append(DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
                    LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(entry.createdAt),
                        ZoneId.systemDefault(),
                    ),
                ))
            }
        }
        return CapabilityOutcome(
            true,
            "ZeroClaw-created ${plural}s on this phone (${entries.size}):\n$lines\n" +
                "Only entries ZeroClaw created are tracked; the clock app's own list is not readable.",
        )
    }

    private suspend fun deleteTracked(
        context: Context,
        kind: String,
        args: JsonObject,
    ): CapabilityOutcome {
        val id = args.string("id")?.trim().orEmpty()
        val label = args.string("label")?.trim().orEmpty()
        if (id.isEmpty() && label.isEmpty()) {
            return CapabilityOutcome(
                false,
                "",
                "Pass the entry id from ${kind}.list (or a label to match).",
            )
        }
        val entries = ClockStore.list(context, kind)
        val entry = entries.firstOrNull { id.isNotEmpty() && it.id == id }
            ?: entries.firstOrNull { label.isNotEmpty() && it.label == label }
            ?: return CapabilityOutcome(
                false,
                "",
                "No tracked $kind matched ${if (id.isNotEmpty()) "id=$id" else "label=$label"}.",
            )
        ClockStore.remove(context, entry.id)
        val dismissNote = if (kind == "alarm") dismissFiringAlarm(context) else
            "Android exposes no intent that cancels a running third-party timer; " +
                "stop it from the clock app's Timers tab if needed."
        return CapabilityOutcome(
            true,
            "Removed $kind ${entry.id} from ZeroClaw's tracked list. $dismissNote",
        )
    }

    private fun dismissFiringAlarm(context: Context): String {
        val intent = Intent(AlarmClock.ACTION_DISMISS_ALARM).apply {
            putExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE, AlarmClock.ALARM_SEARCH_MODE_ALL)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return try {
            context.startActivity(intent)
            "Sent the clock app's dismiss request. Whether it removes a pending alarm is the " +
                "clock app's decision (AOSP DeskClock does; some OEMs only dismiss a firing " +
                "alarm), so check the Clock app if it must not fire."
        } catch (_: ActivityNotFoundException) {
            "No clock app handled the dismiss request; delete the alarm in the Clock app if " +
                "it must not fire."
        } catch (error: SecurityException) {
            "Android refused the dismiss request (${error.message}); delete the alarm in the " +
                "Clock app if it must not fire."
        }
    }

    // ── Time / date ────────────────────────────────────────────────────────

    private fun currentTime(): CapabilityOutcome {
        val now = LocalDateTime.now()
        val zone = ZoneId.systemDefault()
        val text = buildString {
            append("Phone local time: ")
            append(DateTimeFormatter.ofPattern("HH:mm:ss").format(now))
            append(" (")
            append(zone.getDisplayName(TextStyle.SHORT, Locale.getDefault()))
            append(", ")
            append(zone.id)
            append(")\nISO: ")
            append(DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(now.atZone(zone)))
            append("\nEpoch millis: ")
            append(System.currentTimeMillis())
        }
        return CapabilityOutcome(true, text)
    }

    private fun currentDate(): CapabilityOutcome {
        val now = LocalDateTime.now()
        val zone = ZoneId.systemDefault()
        val text = buildString {
            append("Phone local date: ")
            append(DateTimeFormatter.ISO_LOCAL_DATE.format(now))
            append(" (")
            append(now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()))
            append(")\nTime zone: ")
            append(zone.id)
        }
        return CapabilityOutcome(true, text)
    }

    // ── Intent plumbing ────────────────────────────────────────────────────

    private suspend fun launch(
        context: Context,
        intent: Intent,
        successText: String,
    ): CapabilityOutcome = withContext(Dispatchers.Main) {
        // Do not pre-check with resolveActivity: from API 30 package-visibility
        // filtering can hide the clock app and produce a false negative.
        try {
            context.startActivity(intent)
            CapabilityOutcome(true, successText)
        } catch (_: ActivityNotFoundException) {
            CapabilityOutcome(false, "", "No clock app handled the request.")
        } catch (error: SecurityException) {
            CapabilityOutcome(false, "", "Android refused the request: ${error.message}")
        }
    }

    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        val rest = seconds % 60
        return buildList {
            if (hours > 0) add("${hours}h")
            if (minutes > 0) add("${minutes}m")
            if (rest > 0 || isEmpty()) add("${rest}s")
        }.joinToString(" ")
    }
}
