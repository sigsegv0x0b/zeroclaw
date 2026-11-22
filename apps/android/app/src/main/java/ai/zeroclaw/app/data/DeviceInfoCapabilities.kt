package ai.zeroclaw.app.data

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.StatFs
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * Device introspection and local-notification commands, mirroring OpenClaw's
 * `device.*` and `system.notify` node commands. Everything here reads Android
 * framework state; none of it needs a runtime permission except posting the
 * notification itself.
 */
object DeviceInfoCapabilities {

    private const val NOTIFY_CHANNEL = "zeroclaw_agent"

    suspend fun info(context: Context): CapabilityOutcome = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val appVersion = runCatching {
            pm.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull().orEmpty()
        CapabilityOutcome(
            true,
            buildString {
                appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
                appendLine("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                appendLine("Build: ${Build.ID}")
                appendLine("App: ZeroClaw $appVersion (${context.packageName})")
                appendLine("Locale: ${Locale.getDefault().toLanguageTag()}")
                append("Screen: ${context.resources.displayMetrics.widthPixels}x" +
                    "${context.resources.displayMetrics.heightPixels} @ " +
                    "${context.resources.displayMetrics.densityDpi}dpi")
            },
        )
    }

    suspend fun status(context: Context): CapabilityOutcome = withContext(Dispatchers.IO) {
        val battery = batteryStatus(context)
        val power = context.getSystemService(PowerManager::class.java)
        val keyguard = context.getSystemService(KeyguardManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val active = connectivity?.activeNetwork
        val caps = active?.let { connectivity.getNetworkCapabilities(it) }
        val transport = when {
            caps == null -> "offline"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            else -> "other"
        }
        val storage = storageStats(context)
        CapabilityOutcome(
            true,
            buildString {
                appendLine("Battery: ${battery.percent}% ${if (battery.charging) "(charging)" else "(discharging)"}")
                appendLine("Network: $transport" +
                    if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true) {
                        " (internet)"
                    } else {
                        " (no internet)"
                    })
                appendLine("Screen: ${if (power?.isInteractive == true) "on" else "off"}, " +
                    "${if (keyguard?.isKeyguardLocked == true) "locked" else "unlocked"}")
                appendLine("Storage: ${humanBytes(storage.available)} free of ${humanBytes(storage.total)}")
                append("Uptime: ${formatUptime(SystemClock.elapsedRealtime())}")
            },
        )
    }

    suspend fun health(context: Context): CapabilityOutcome = withContext(Dispatchers.IO) {
        val activity = context.getSystemService(ActivityManager::class.java)
        val memory = ActivityManager.MemoryInfo().also { activity?.getMemoryInfo(it) }
        val battery = batteryStatus(context)
        val power = context.getSystemService(PowerManager::class.java)
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            power?.currentThermalStatus?.toString() ?: "unknown"
        } else {
            "unavailable"
        }
        val storage = storageStats(context)
        CapabilityOutcome(
            true,
            buildString {
                appendLine("Memory: ${humanBytes(memory.availMem)} free of ${humanBytes(memory.totalMem)}" +
                    if (memory.lowMemory) " (low)" else "")
                appendLine("Storage: ${humanBytes(storage.available)} free of ${humanBytes(storage.total)}")
                appendLine("Battery: ${battery.percent}% ${battery.health}, ${battery.temperatureC}°C, " +
                    "${battery.voltageMv}mV")
                appendLine("Thermal status: $thermal")
                append("Low-memory flag: ${memory.lowMemory}")
            },
        )
    }

    suspend fun permissions(context: Context): CapabilityOutcome = withContext(Dispatchers.IO) {
        val checks = linkedMapOf(
            "location" to DevicePermissions.hasLocation(context),
            "photos" to DevicePermissions.hasMedia(context),
            "contacts" to DevicePermissions.has(context, DevicePermissions.contacts()),
            "calendar" to DevicePermissions.has(context, DevicePermissions.calendar()),
            "activity" to DevicePermissions.has(context, DevicePermissions.activity()),
            "sms" to DevicePermissions.has(context, DevicePermissions.sms()),
            "callLog" to DevicePermissions.has(context, DevicePermissions.callLog()),
            "postNotifications" to DevicePermissions.granted(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ),
            "notificationAccess" to DevicePermissions.hasNotificationAccess(context),
            "accessibility" to isAccessibilityEnabled(context),
        )
        CapabilityOutcome(
            true,
            checks.entries.joinToString("\n") { (name, granted) ->
                "- $name: ${if (granted) "granted" else "not granted"}"
            },
        )
    }

    suspend fun apps(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.IO) {
            val query = args.string("query")?.trim()?.lowercase().orEmpty()
            val includeSystem = args.bool("include_system", default = false)
            val limit = (args.int("limit") ?: 50).coerceIn(1, 200)
            val pm = context.packageManager
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val resolved = pm.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
            val entries = resolved
                .asSequence()
                .mapNotNull { info ->
                    val app = info.activityInfo?.applicationInfo ?: return@mapNotNull null
                    val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    if (isSystem && !includeSystem) return@mapNotNull null
                    val label = pm.getApplicationLabel(app).toString()
                    if (query.isNotEmpty() && !label.lowercase().contains(query) &&
                        !app.packageName.lowercase().contains(query)
                    ) {
                        return@mapNotNull null
                    }
                    "$label (${app.packageName})"
                }
                .distinct()
                .sorted()
                .take(limit)
                .toList()
            if (entries.isEmpty()) {
                CapabilityOutcome(true, "No launchable apps matched.")
            } else {
                CapabilityOutcome(true, "${entries.size} app(s):\n" + entries.joinToString("\n") { "- $it" })
            }
        }

    suspend fun notify(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.IO) {
            val title = args.string("title")?.trim().orEmpty()
            val body = args.string("body")?.trim().orEmpty()
            if (title.isEmpty() || body.isEmpty()) {
                return@withContext CapabilityOutcome(false, "", "title and body are required")
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !DevicePermissions.granted(context, android.Manifest.permission.POST_NOTIFICATIONS)
            ) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Notification permission is not granted. Allow notifications for ZeroClaw " +
                        "in system settings.",
                )
            }
            val manager = NotificationManagerCompat.from(context)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    manager.createNotificationChannel(
                        NotificationChannel(
                            NOTIFY_CHANNEL,
                            "Agent notifications",
                            NotificationManager.IMPORTANCE_DEFAULT,
                        ),
                    )
                }
                val notification = NotificationCompat.Builder(context, NOTIFY_CHANNEL)
                    .setSmallIcon(ai.zeroclaw.app.R.drawable.ic_notification)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                    .setPriority(
                        when (args.string("priority")?.lowercase()) {
                            "min", "low" -> NotificationCompat.PRIORITY_LOW
                            "high" -> NotificationCompat.PRIORITY_HIGH
                            else -> NotificationCompat.PRIORITY_DEFAULT
                        },
                    )
                    .setAutoCancel(true)
                    .build()
                manager.notify((System.currentTimeMillis() and 0xFFFF).toInt(), notification)
            }.getOrElse { error ->
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Could not post the notification: ${error.message}",
                )
            }
            CapabilityOutcome(true, "Posted notification \"$title\".")
        }

    // ── Helpers ────────────────────────────────────────────────────────────

    private data class Battery(
        val percent: Int,
        val charging: Boolean,
        val health: String,
        val temperatureC: String,
        val voltageMv: Int,
    )

    private fun batteryStatus(context: Context): Battery {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val percent = if (level >= 0 && scale > 0) level * 100 / scale else -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
        val health = when (intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "over-voltage"
            BatteryManager.BATTERY_HEALTH_COLD -> "cold"
            else -> "unknown"
        }
        val temperature = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0
        return Battery(
            percent = percent,
            charging = charging,
            health = health,
            temperatureC = String.format(Locale.US, "%.1f", temperature),
            voltageMv = voltage,
        )
    }

    private data class Storage(val total: Long, val available: Long)

    private fun storageStats(context: Context): Storage {
        val stat = StatFs(context.filesDir.absolutePath)
        val available = stat.availableBytes
        val total = stat.totalBytes
        return Storage(total = total, available = available)
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val manager = context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
        return manager?.getEnabledAccessibilityServiceList(
            android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK,
        )?.any { it.resolveInfo.serviceInfo.packageName == context.packageName } == true
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.1f GB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun formatUptime(millis: Long): String {
        val totalMinutes = millis / 60_000
        val days = totalMinutes / (24 * 60)
        val hours = (totalMinutes % (24 * 60)) / 60
        val minutes = totalMinutes % 60
        return buildList {
            if (days > 0) add("${days}d")
            if (hours > 0) add("${hours}h")
            add("${minutes}m")
        }.joinToString(" ")
    }
}
