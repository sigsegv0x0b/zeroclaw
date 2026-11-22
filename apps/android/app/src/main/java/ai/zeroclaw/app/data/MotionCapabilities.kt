package ai.zeroclaw.app.data

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.coroutines.resume

/**
 * Motion sensors, mirroring OpenClaw's `motion.activity` and
 * `motion.pedometer`. Activity recognition is done from a short accelerometer
 * sample rather than the Play Services activity API, so it works on any
 * device (and in the emulator) without extra dependencies.
 */
object MotionCapabilities {

    private const val SAMPLE_WINDOW_MS = 1_500L

    suspend fun activity(context: Context, _args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.Main.immediate) {
            if (!hasActivityPermission(context)) return@withContext PERMISSION_DENIED
            val manager = context.getSystemService(SensorManager::class.java)
                ?: return@withContext CapabilityOutcome(false, "", "Sensor service unavailable.")
            val sensor = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
                ?: return@withContext CapabilityOutcome(
                    false,
                    "",
                    "This device has no accelerometer.",
                )
            val samples = ArrayList<FloatArray>()
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    samples += event.values.copyOf()
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
            try {
                withTimeoutOrNull(SAMPLE_WINDOW_MS) {
                    suspendCancellableCoroutine<Unit> { /* released by the timeout */ }
                }
            } finally {
                manager.unregisterListener(listener)
            }
            if (samples.size < 3) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Not enough accelerometer samples (${samples.size}).",
                )
            }
            var deltaSum = 0.0
            var previous = magnitude(samples.first())
            for (index in 1 until samples.size) {
                val current = magnitude(samples[index])
                deltaSum += abs(current - previous)
                previous = current
            }
            val averageDelta = deltaSum / (samples.size - 1)
            val classification = when {
                averageDelta > 2.5 -> "running"
                averageDelta > 0.7 -> "walking"
                else -> "stationary"
            }
            CapabilityOutcome(
                true,
                "Activity: $classification (avg delta ${"%.2f".format(averageDelta)}, " +
                    "${samples.size} samples over ${SAMPLE_WINDOW_MS}ms).",
            )
        }

    suspend fun pedometer(context: Context, _args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.Main.immediate) {
            if (!hasActivityPermission(context)) return@withContext PERMISSION_DENIED
            val manager = context.getSystemService(SensorManager::class.java)
                ?: return@withContext CapabilityOutcome(false, "", "Sensor service unavailable.")
            val sensor = manager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
                ?: return@withContext CapabilityOutcome(
                    false,
                    "",
                    "This device has no step counter.",
                )
            val steps = withTimeoutOrNull(2_000L) {
                suspendCancellableCoroutine { continuation ->
                    val listener = object : SensorEventListener {
                        override fun onSensorChanged(event: SensorEvent) {
                            manager.unregisterListener(this)
                            if (continuation.isActive) {
                                continuation.resume(event.values.firstOrNull() ?: -1f)
                            }
                        }

                        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
                    }
                    manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_UI)
                    continuation.invokeOnCancellation { manager.unregisterListener(listener) }
                }
            }
            if (steps == null || steps < 0f) {
                CapabilityOutcome(
                    false,
                    "",
                    "No step-counter reading was available. Keep the app in the foreground and retry.",
                )
            } else {
                CapabilityOutcome(
                    true,
                    "Steps since boot: ${steps.toLong()} (this device counter resets on reboot).",
                )
            }
        }

    private fun magnitude(values: FloatArray): Double =
        sqrt(values.fold(0.0) { acc, value -> acc + value * value })

    private fun hasActivityPermission(context: Context): Boolean =
        DevicePermissions.has(context, DevicePermissions.activity())

    private val PERMISSION_DENIED = CapabilityOutcome(
        false,
        "",
        "Physical activity permission is not granted. Open ZeroClaw → Settings → Device " +
            "capabilities → Activity and grant it.",
    )
}
