package ai.zeroclaw.app.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Base64
import java.util.Locale
import kotlin.coroutines.resume

/**
 * Camera commands mirroring OpenClaw's `camera.list`, `camera.snap` and
 * `camera.clip`.
 *
 * Camera2 is used directly (no CameraX dependency). Capture is headless: the
 * node service drives the device, so the app must be in the foreground on
 * Android versions that restrict background camera access.
 */
object CameraCapabilities {

    private const val MAX_CLIP_MS = 15_000

    suspend fun list(context: Context): CapabilityOutcome = withContext(Dispatchers.IO) {
        if (!hasCameraPermission(context)) return@withContext PERMISSION_DENIED
        val manager = context.getSystemService(CameraManager::class.java)
            ?: return@withContext CapabilityOutcome(false, "", "Camera service unavailable.")
        val cameras = runCatching {
            manager.cameraIdList.map { id ->
                val chars = manager.getCameraCharacteristics(id)
                val facing = when (chars.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "front"
                    CameraCharacteristics.LENS_FACING_BACK -> "back"
                    else -> "external"
                }
                "- id=$id facing=$facing"
            }
        }.getOrElse { error ->
            return@withContext CapabilityOutcome(false, "", "Could not read cameras: ${error.message}")
        }
        if (cameras.isEmpty()) {
            CapabilityOutcome(true, "No cameras are available.")
        } else {
            CapabilityOutcome(true, "${cameras.size} camera(s):\n" + cameras.joinToString("\n"))
        }
    }

    suspend fun snap(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.IO) {
            if (!hasCameraPermission(context)) return@withContext PERMISSION_DENIED
            val facing = args.string("facing")?.lowercase(Locale.US) ?: "back"
            val maxWidth = (args.int("maxWidth") ?: args.int("max_width") ?: 1280)
                .coerceIn(320, 4096)
            val id = selectCamera(context, facing)
                ?: return@withContext CapabilityOutcome(false, "", "No $facing camera was found.")
            val bytes = captureJpeg(context, id, maxWidth)
                ?: return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Camera capture failed or timed out (the app must be in the foreground).",
                )
            CapabilityOutcome(
                true,
                "Captured a ${bytes.size / 1024} KB JPEG from the $facing camera.",
                attachments = listOf(
                    NodeAttachment(
                        kind = "image",
                        mime = "image/jpeg",
                        dataB64 = Base64.getEncoder().encodeToString(bytes),
                    ),
                ),
            )
        }

    suspend fun clip(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.IO) {
            if (!hasCameraPermission(context)) return@withContext PERMISSION_DENIED
            val facing = args.string("facing")?.lowercase(Locale.US) ?: "back"
            val durationMs = (args.int("durationMs") ?: args.int("duration_ms") ?: 3_000)
                .coerceIn(500, MAX_CLIP_MS)
            val id = selectCamera(context, facing)
                ?: return@withContext CapabilityOutcome(false, "", "No $facing camera was found.")
            val output = File(context.cacheDir, "clip-${System.currentTimeMillis()}.mp4")
            recordClip(context, id, output, durationMs)
            if (!output.exists() || output.length() == 0L) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Video capture failed or timed out (the app must be in the foreground; " +
                        "audio recording also needs the Microphone permission).",
                )
            }
            CapabilityOutcome(
                true,
                "Recorded a ${durationMs}ms clip to ${output.absolutePath} " +
                    "(${output.length() / 1024} KB). Video files are not attached to tool " +
                    "results; use the path on this phone.",
            )
        }

    // ── Camera2 plumbing ───────────────────────────────────────────────────

    private fun hasCameraPermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun selectCamera(context: Context, facing: String): String? {
        val manager = context.getSystemService(CameraManager::class.java) ?: return null
        val wanted = if (facing == "front") {
            CameraCharacteristics.LENS_FACING_FRONT
        } else {
            CameraCharacteristics.LENS_FACING_BACK
        }
        return runCatching {
            manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == wanted
            } ?: manager.cameraIdList.firstOrNull()
        }.getOrNull()
    }

    private suspend fun captureJpeg(
        context: Context,
        cameraId: String,
        maxWidth: Int,
    ): ByteArray? = withTimeoutOrNull(10_000) {
        suspendCancellableCoroutine { continuation ->
            val manager = context.getSystemService(CameraManager::class.java)
            if (manager == null) {
                continuation.resume(null)
                return@suspendCancellableCoroutine
            }
            val handler = Handler(Looper.getMainLooper())
            val size = runCatching {
                manager.getCameraCharacteristics(cameraId)
                    .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    ?.getOutputSizes(ImageFormat.JPEG)
                    ?.filter { it.width <= maxWidth }
                    ?.maxByOrNull { it.width }
            }.getOrNull() ?: android.util.Size(1280, 720)
            val reader = ImageReader.newInstance(size.width, size.height, ImageFormat.JPEG, 1)
            var device: CameraDevice? = null
            var session: CameraCaptureSession? = null
            var delivered = false

            fun finish(bytes: ByteArray?) {
                if (delivered) return
                delivered = true
                runCatching { session?.close() }
                runCatching { device?.close() }
                runCatching { reader.close() }
                if (continuation.isActive) continuation.resume(bytes)
            }

            reader.setOnImageAvailableListener({ source ->
                val image = source.acquireNextImage()
                val bytes = image?.let {
                    runCatching {
                        val buffer = it.planes[0].buffer
                        ByteArray(buffer.remaining()).also { out -> buffer.get(out) }
                    }.getOrNull()
                }
                image?.close()
                finish(bytes)
            }, handler)

            val stateCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    runCatching {
                        val surface = reader.surface
                        camera.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(configured: CameraCaptureSession) {
                                    session = configured
                                    runCatching {
                                        val request = camera.createCaptureRequest(
                                            CameraDevice.TEMPLATE_STILL_CAPTURE,
                                        ).apply {
                                            addTarget(surface)
                                            set(
                                                CaptureRequest.CONTROL_AF_MODE,
                                                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
                                            )
                                        }.build()
                                        configured.capture(request, null, handler)
                                    }.onFailure { finish(null) }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    finish(null)
                                }
                            },
                            handler,
                        )
                    }.onFailure { finish(null) }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    finish(null)
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    finish(null)
                }
            }
            runCatching {
                if (context.checkSelfPermission(Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    finish(null)
                } else {
                    manager.openCamera(cameraId, stateCallback, handler)
                }
            }.onFailure { finish(null) }

            continuation.invokeOnCancellation {
                delivered = true
                runCatching { session?.close() }
                runCatching { device?.close() }
                runCatching { reader.close() }
            }
        }
    }

    private suspend fun recordClip(
        context: Context,
        cameraId: String,
        output: File,
        durationMs: Int,
    ) = withTimeoutOrNull(durationMs + 10_000L) {
        suspendCancellableCoroutine { continuation ->
            val manager = context.getSystemService(CameraManager::class.java)
            if (manager == null) {
                continuation.resume(Unit)
                return@suspendCancellableCoroutine
            }
            val handler = Handler(Looper.getMainLooper())
            @Suppress("DEPRECATION")
            val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                MediaRecorder()
            }
            var device: CameraDevice? = null
            var session: CameraCaptureSession? = null
            var done = false

            fun finish() {
                if (done) return
                done = true
                runCatching { session?.close() }
                runCatching { device?.close() }
                runCatching { recorder.reset() }
                runCatching { recorder.release() }
                if (continuation.isActive) continuation.resume(Unit)
            }

            runCatching {
                recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                recorder.setVideoFrameRate(30)
                recorder.setVideoSize(1280, 720)
                recorder.setOutputFile(output.absolutePath)
                recorder.prepare()
            }.onFailure {
                finish()
                return@suspendCancellableCoroutine
            }

            val stateCallback = object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    device = camera
                    runCatching {
                        val surface: Surface = recorder.surface
                        camera.createCaptureSession(
                            listOf(surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(configured: CameraCaptureSession) {
                                    session = configured
                                    runCatching {
                                        val request = camera.createCaptureRequest(
                                            CameraDevice.TEMPLATE_RECORD,
                                        ).apply { addTarget(surface) }.build()
                                        configured.setRepeatingRequest(request, null, handler)
                                        recorder.start()
                                        handler.postDelayed({ finish() }, durationMs.toLong())
                                    }.onFailure { finish() }
                                }

                                override fun onConfigureFailed(session: CameraCaptureSession) {
                                    finish()
                                }
                            },
                            handler,
                        )
                    }.onFailure { finish() }
                }

                override fun onDisconnected(camera: CameraDevice) = finish()

                override fun onError(camera: CameraDevice, error: Int) = finish()
            }
            runCatching {
                if (context.checkSelfPermission(Manifest.permission.CAMERA) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    finish()
                } else {
                    manager.openCamera(cameraId, stateCallback, handler)
                }
            }.onFailure { finish() }

            continuation.invokeOnCancellation {
                runCatching { session?.close() }
                runCatching { device?.close() }
                runCatching { recorder.reset() }
                runCatching { recorder.release() }
            }
        }
    }

    private val PERMISSION_DENIED = CapabilityOutcome(
        false,
        "",
        "Camera permission is not granted. Open ZeroClaw → Settings → Device capabilities → " +
            "Camera and grant it.",
    )
}
