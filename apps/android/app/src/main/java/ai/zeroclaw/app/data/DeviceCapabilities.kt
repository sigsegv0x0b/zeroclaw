package ai.zeroclaw.app.data

import android.provider.AlarmClock
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

@Serializable
data class InvokeResultFrame(
    val type: String = "result",
    @SerialName("call_id") val callId: String,
    val success: Boolean,
    val output: String,
    val error: String? = null,
)

data class CapabilityOutcome(
    val success: Boolean,
    val output: String,
    val error: String? = null,
)

/**
 * Capabilities this phone advertises to the gateway. Each name becomes an
 * agent tool (`node_<id>__alarm_set`), so the descriptions and parameter
 * schemas are the contract the model reads: say when to use it, and accept
 * only values the Android clock intents take.
 */
object DeviceCapabilities {

    const val ALARM_SET = "alarm.set"
    const val TIMER_SET = "timer.set"

    val definitions: List<NodeCapabilityDef> = listOf(
        NodeCapabilityDef(
            name = ALARM_SET,
            description = "Set an alarm on this Android phone's clock app at a local wall-clock time. " +
                "Use this when the user asks for an alarm on their phone. " +
                "Pass hour and minute in 24-hour local time; skip_ui=true creates it without a confirmation screen.",
            parameters = zcJson.parseToJsonElement(
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
            ).let { it as JsonObject },
        ),
        NodeCapabilityDef(
            name = TIMER_SET,
            description = "Start a countdown timer on this Android phone's clock app. " +
                "Use this for 'in N minutes' or 'timer for N seconds' requests; " +
                "prefer this over an alarm when the request is a duration rather than a clock time.",
            parameters = zcJson.parseToJsonElement(
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
            ).let { it as JsonObject },
        ),
    )

    suspend fun execute(context: Context, capability: String, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.Main) {
            when (capability) {
                ALARM_SET -> setAlarm(context, args)
                TIMER_SET -> setTimer(context, args)
                else -> CapabilityOutcome(false, "", "Unsupported capability: $capability")
            }
        }

    private fun setAlarm(context: Context, args: JsonObject): CapabilityOutcome {
        val hour = args.int("hour")
        val minute = args.int("minute")
        if (hour == null || hour !in 0..23) {
            return CapabilityOutcome(false, "", "hour must be 0-23")
        }
        if (minute == null || minute !in 0..59) {
            return CapabilityOutcome(false, "", "minute must be 0-59")
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, args.bool("skip_ui", default = true))
            putExtra(AlarmClock.EXTRA_VIBRATE, args.bool("vibrate", default = true))
            args.string("label")?.takeIf { it.isNotBlank() }
                ?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val time = String.format(Locale.getDefault(), "%02d:%02d", hour, minute)
        return launch(context, intent, "Alarm set for $time on this phone.")
    }

    private fun setTimer(context: Context, args: JsonObject): CapabilityOutcome {
        val seconds = args.int("seconds")
        if (seconds == null || seconds !in 1..86_400) {
            return CapabilityOutcome(false, "", "seconds must be between 1 and 86400")
        }
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, args.bool("skip_ui", default = true))
            args.string("label")?.takeIf { it.isNotBlank() }
                ?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val spoken = formatDuration(seconds)
        return launch(context, intent, "Started a $spoken timer on this phone.")
    }

    private fun launch(context: Context, intent: Intent, successText: String): CapabilityOutcome {
        // Do not pre-check with resolveActivity: from API 30 package-visibility
        // filtering can hide the clock app and produce a false negative.
        return try {
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

private fun JsonObject.string(key: String): String? {
    val value = this[key] as? JsonPrimitive ?: return null
    return if (value.isString) value.content else null
}

private fun JsonObject.int(key: String): Int? {
    val value = this[key] as? JsonPrimitive ?: return null
    return value.content.toIntOrNull()
}

private fun JsonObject.bool(key: String, default: Boolean): Boolean {
    val value = this[key] as? JsonPrimitive ?: return default
    return when (value.content.lowercase()) {
        "true", "1" -> true
        "false", "0" -> false
        else -> default
    }
}
