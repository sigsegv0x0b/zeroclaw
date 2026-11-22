package ai.zeroclaw.app.data

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

/**
 * Push-to-talk for the `zeroclaw` voice backend: record 16 kHz mono PCM on the
 * phone, stop on release or silence, and POST the WAV to the gateway's
 * `/api/transcribe`, which uses its configured STT provider. Audio leaves the
 * phone only when this backend is selected.
 */
class GatewayVoiceInput(private val context: Context) {

    interface Listener {
        fun onStarted()
        fun onTranscript(text: String)
        fun onFailed(message: String)
        fun onStopped()
    }

    var listener: Listener? = null

    private var job: Job? = null

    @Volatile
    private var stopRequested = false

    @Volatile
    private var cancelled = false

    @Volatile
    private var recorder: AudioRecord? = null

    val isActive: Boolean get() = job?.isActive == true

    fun start(scope: CoroutineScope, baseUrl: String, token: String) {
        if (job?.isActive == true) return
        if (baseUrl.isBlank() || token.isBlank()) {
            listener?.onFailed("Not paired with a gateway.")
            return
        }
        stopRequested = false
        cancelled = false
        job = scope.launch(Dispatchers.IO) {
            listener?.onStarted()
            val pcm = recordUntilSilence()
            if (cancelled) {
                listener?.onStopped()
                return@launch
            }
            if (pcm == null || pcm.isEmpty()) {
                listener?.onFailed("No speech was recorded.")
                return@launch
            }
            val wav = wav(pcm, SAMPLE_RATE)
            runCatching { transcribe(baseUrl, token, wav) }
                .onSuccess { text ->
                    if (cancelled) return@onSuccess
                    if (text.isBlank()) {
                        listener?.onFailed("The gateway returned an empty transcript.")
                    } else {
                        listener?.onTranscript(text)
                    }
                }
                .onFailure { error ->
                    if (!cancelled) {
                        listener?.onFailed(error.message ?: "Transcription failed.")
                    }
                }
        }
    }

    /** Release-to-send: finalize the current recording. */
    fun stop() {
        stopRequested = true
        runCatching { recorder?.stop() }
    }

    fun cancel() {
        cancelled = true
        stopRequested = true
        runCatching { recorder?.stop() }
        job?.cancel()
        job = null
        listener?.onStopped()
    }

    fun destroy() = cancel()

    // ── Recording ──────────────────────────────────────────────────────────

    @SuppressLint("MissingPermission")
    private fun recordUntilSilence(): ByteArray? {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            listener?.onFailed("The microphone does not support 16 kHz mono recording.")
            return null
        }
        val bufferSize = maxOf(minBuffer, SAMPLE_RATE / 10 * 2)
        val record = runCatching {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize,
            )
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            listener?.onFailed("The microphone could not be opened.")
            runCatching { record?.release() }
            return null
        }
        recorder = record
        val pcm = ByteArrayOutputStream()
        val buffer = ByteArray(bufferSize)
        var speechStarted = false
        var silenceMs = 0
        var elapsedMs = 0
        return try {
            record.startRecording()
            while (!stopRequested && !cancelled && elapsedMs < MAX_RECORD_MS) {
                val read = record.read(buffer, 0, buffer.size)
                if (read <= 0) break
                pcm.write(buffer, 0, read)
                val rms = rms(buffer, read)
                if (rms > SPEECH_RMS) {
                    speechStarted = true
                    silenceMs = 0
                } else if (speechStarted) {
                    silenceMs += (read / 2) * 1000 / SAMPLE_RATE
                }
                elapsedMs += (read / 2) * 1000 / SAMPLE_RATE
                if (speechStarted && silenceMs >= SILENCE_STOP_MS) break
                if (!speechStarted && elapsedMs >= NO_SPEECH_MS) break
            }
            if (!speechStarted) {
                listener?.onFailed("No speech was heard.")
                null
            } else {
                pcm.toByteArray()
            }
        } catch (error: Exception) {
            if (!cancelled) listener?.onFailed("Recording failed: ${error.message}")
            null
        } finally {
            runCatching { record.stop() }
            runCatching { record.release() }
            recorder = null
        }
    }

    private fun rms(buffer: ByteArray, length: Int): Double {
        var sum = 0.0
        var index = 0
        while (index + 1 < length) {
            val sample = (buffer[index].toInt() and 0xFF) or (buffer[index + 1].toInt() shl 8)
            val signed = sample.toShort().toDouble()
            sum += signed * signed
            index += 2
        }
        val count = length / 2
        return if (count == 0) 0.0 else sqrt(sum / count)
    }

    // ── Upload ─────────────────────────────────────────────────────────────

    private fun transcribe(baseUrl: String, token: String, wav: ByteArray): String {
        val media = "audio/wav".toMediaType()
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/api/transcribe")
            .header("Authorization", "Bearer $token")
            .post(wav.toRequestBody(media))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val json = runCatching {
                zcJson.parseToJsonElement(body) as? JsonObject
            }.getOrNull()
            val text = json?.get("text")?.jsonPrimitive?.contentOrNull
            if (!response.isSuccessful || text == null) {
                val gatewayError = json?.get("error")?.jsonPrimitive?.contentOrNull
                throw IllegalStateException(
                    gatewayError
                        ?: "Gateway transcription failed (HTTP ${response.code}).",
                )
            }
            return text
        }
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val SAMPLE_RATE = 16_000
        private const val SPEECH_RMS = 900.0
        private const val SILENCE_STOP_MS = 1_200
        private const val NO_SPEECH_MS = 7_000
        private const val MAX_RECORD_MS = 30_000

        private fun wav(pcm: ByteArray, sampleRate: Int): ByteArray {
            val out = ByteArrayOutputStream(44 + pcm.size)
            fun le32(value: Int) {
                out.write(value and 0xFF)
                out.write((value shr 8) and 0xFF)
                out.write((value shr 16) and 0xFF)
                out.write((value shr 24) and 0xFF)
            }
            fun le16(value: Int) {
                out.write(value and 0xFF)
                out.write((value shr 8) and 0xFF)
            }
            out.write("RIFF".toByteArray())
            le32(36 + pcm.size)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            le32(16)
            le16(1)
            le16(1)
            le32(sampleRate)
            le32(sampleRate * 2)
            le16(2)
            le16(16)
            out.write("data".toByteArray())
            le32(pcm.size)
            out.write(pcm)
            return out.toByteArray()
        }
    }
}
