package ai.zeroclaw.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.coroutines.resume

/**
 * One-shot location read for the `location.get` capability.
 *
 * The device link is a foreground service, so the operator grants the Location
 * permission from Settings; this object never prompts. A cached fix younger
 * than `max_age_secs` avoids waking the radio for every request.
 */
object LocationCapability {

    suspend fun read(context: Context, args: JsonObject): CapabilityOutcome {
        if (!DevicePermissions.hasLocation(context)) {
            return CapabilityOutcome(
                false,
                "",
                "Location permission is not granted. Open ZeroClaw → Settings → " +
                    "Device capabilities → Location and grant it.",
            )
        }
        val timeoutSecs = (args.int("timeout_secs") ?: 10).coerceIn(1, 30)
        val maxAgeSecs = (args.int("max_age_secs") ?: 300).coerceIn(0, 3600)
        val manager = context.getSystemService(LocationManager::class.java)
            ?: return CapabilityOutcome(false, "", "Location service is unavailable on this device.")

        val cached = bestLastKnown(manager, maxAgeSecs)
        val location = cached ?: requestFix(manager, timeoutSecs)
        if (location == null) {
            return CapabilityOutcome(
                false,
                "",
                "No location fix was available within ${timeoutSecs}s. Check that location is " +
                    "turned on, or keep the app in the foreground and retry.",
            )
        }

        val ageSecs = ((System.currentTimeMillis() - location.time) / 1000).coerceAtLeast(0)
        val source = if (cached != null) "cached" else "fresh"
        val time = DateTimeFormatter.ISO_OFFSET_DATE_TIME.format(
            Instant.ofEpochMilli(location.time).atZone(ZoneId.systemDefault()),
        )
        val text = buildString {
            append("Phone location: lat=")
            append(String.format(java.util.Locale.US, "%.6f", location.latitude))
            append(", lon=")
            append(String.format(java.util.Locale.US, "%.6f", location.longitude))
            append(", accuracy=")
            append(String.format(java.util.Locale.US, "%.0f", location.accuracy))
            append("m, provider=")
            append(location.provider ?: "unknown")
            append(", fix_age=")
            append(ageSecs)
            append("s (")
            append(source)
            append("), time=")
            append(time)
        }
        return CapabilityOutcome(true, text)
    }

    // Only reached after `read` has checked location permission.
    @SuppressLint("MissingPermission")
    private fun bestLastKnown(manager: LocationManager, maxAgeSecs: Int): Location? {
        if (maxAgeSecs <= 0) return null
        val cutoff = System.currentTimeMillis() - maxAgeSecs * 1000L
        return activeProviders(manager)
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
            .filter { it.time >= cutoff }
            .maxByOrNull { it.time }
    }

    // `read` refuses before reaching here unless location permission is
    // granted; lint cannot follow the check across these private helpers.
    @SuppressLint("MissingPermission")
    private suspend fun requestFix(manager: LocationManager, timeoutSecs: Int): Location? {
        val provider = activeProviders(manager).firstOrNull() ?: return null
        return withContext(Dispatchers.Main.immediate) {
            val listener = OneShotListener()
            try {
                manager.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            } catch (error: SecurityException) {
                return@withContext null
            }
            try {
                withTimeoutOrNull(timeoutSecs * 1000L) { listener.await() }
            } finally {
                runCatching { manager.removeUpdates(listener) }
            }
        }
    }

    private fun activeProviders(manager: LocationManager): List<String> =
        listOf(
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
        ).filter { provider ->
            runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)
        }

    private class OneShotListener : LocationListener {
        private var pending: ((Location) -> Unit)? = null
        private var last: Location? = null

        suspend fun await(): Location? {
            last?.let { return it }
            return suspendCancellableCoroutine { continuation ->
                pending = { location ->
                    if (continuation.isActive) continuation.resume(location)
                }
            }
        }

        override fun onLocationChanged(location: Location) {
            last = location
            val deliver = pending
            pending = null
            deliver?.invoke(location)
        }

        override fun onProviderEnabled(provider: String) = Unit

        override fun onProviderDisabled(provider: String) = Unit

        @Deprecated("Required by the platform interface on older API levels")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }
}
