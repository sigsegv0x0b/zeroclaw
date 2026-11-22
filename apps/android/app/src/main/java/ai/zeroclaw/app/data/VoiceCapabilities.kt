package ai.zeroclaw.app.data

import android.content.Context
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * Push-to-talk commands with the shape of OpenClaw's `talk.ptt.*`.
 *
 * OpenClaw routes PTT audio to the gateway's talk subsystem; ZeroClaw has no
 * talk pipeline yet, so this implementation transcribes on the phone with
 * Android's SpeechRecognizer and returns the text. The tool-facing contract is
 * the same: start listening, stop and get the transcript, cancel, or do one
 * bounded capture.
 */
object VoiceCapabilities {

    private data class Outcome(val text: String?, val error: String?)

    private class Session(
        val recognizer: SpeechRecognizer,
        val results: CompletableDeferred<Outcome>,
    )

    private var session: Session? = null

    suspend fun once(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.Main.immediate) {
            if (!hasMicrophone(context)) return@withContext PERMISSION_DENIED
            if (session != null) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "A push-to-talk session is already active. Stop or cancel it first.",
                )
            }
            val timeoutSecs = (args.int("timeout_secs") ?: 15).coerceIn(3, 30)
            val current = begin(context)
            val outcome = withTimeoutOrNull(timeoutSecs * 1000L) { current.results.await() }
            end(current)
            outcome?.let { toOutcome(it, timeoutSecs) }
                ?: CapabilityOutcome(
                    false,
                    "",
                    "No speech was recognized within ${timeoutSecs}s.",
                )
        }

    suspend fun start(context: Context): CapabilityOutcome =
        withContext(Dispatchers.Main.immediate) {
            if (!hasMicrophone(context)) return@withContext PERMISSION_DENIED
            if (session != null) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "A push-to-talk session is already active.",
                )
            }
            session = begin(context)
            CapabilityOutcome(true, "Listening. Call talk.ptt.stop to get the transcript.")
        }

    suspend fun stop(): CapabilityOutcome = withContext(Dispatchers.Main.immediate) {
        val current = session ?: return@withContext CapabilityOutcome(
            false,
            "",
            "No push-to-talk session is active.",
        )
        runCatching { current.recognizer.stopListening() }
        val outcome = withTimeoutOrNull(10_000L) { current.results.await() }
        end(current)
        session = null
        outcome?.let { toOutcome(it, 10) }
            ?: CapabilityOutcome(false, "", "No speech was recognized within 10s.")
    }

    suspend fun cancel(): CapabilityOutcome = withContext(Dispatchers.Main.immediate) {
        val current = session ?: return@withContext CapabilityOutcome(
            true,
            "No push-to-talk session was active.",
        )
        runCatching { current.recognizer.cancel() }
        end(current)
        session = null
        CapabilityOutcome(true, "Push-to-talk session cancelled.")
    }

    // ── SpeechRecognizer plumbing ──────────────────────────────────────────

    private fun begin(context: Context): Session {
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        val results = CompletableDeferred<Outcome>()
        recognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onResults(bundle: android.os.Bundle?) {
                    val text = bundle
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                    results.complete(
                        if (text.isNullOrEmpty()) {
                            Outcome(null, "No speech was recognized.")
                        } else {
                            Outcome(text, null)
                        },
                    )
                }

                override fun onError(error: Int) {
                    results.complete(Outcome(null, describeError(error)))
                }

                override fun onReadyForSpeech(params: android.os.Bundle?) = Unit
                override fun onBeginningOfSpeech() = Unit
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = Unit
                override fun onPartialResults(partialResults: android.os.Bundle?) = Unit
                override fun onEvent(eventType: Int, params: android.os.Bundle?) = Unit
            },
        )
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        recognizer.startListening(intent)
        return Session(recognizer, results)
    }

    private fun end(current: Session) {
        runCatching { current.recognizer.destroy() }
    }

    private fun toOutcome(outcome: Outcome, timeoutSecs: Int): CapabilityOutcome =
        if (outcome.text != null) {
            CapabilityOutcome(true, "Transcript: ${outcome.text}")
        } else {
            CapabilityOutcome(
                false,
                "",
                outcome.error ?: "No speech was recognized within ${timeoutSecs}s.",
            )
        }

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognized."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was heard before the timeout."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is not granted."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "The speech recognizer could not reach its service."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be opened."
        SpeechRecognizer.ERROR_SERVER -> "The speech recognition server returned an error."
        else -> "Speech recognition failed (code $error)."
    }

    private fun hasMicrophone(context: Context): Boolean =
        DevicePermissions.has(context, DevicePermissions.microphone())

    private val PERMISSION_DENIED = CapabilityOutcome(
        false,
        "",
        "Microphone permission is not granted. Open ZeroClaw → Settings → Device capabilities → " +
            "Microphone and grant it.",
    )
}
