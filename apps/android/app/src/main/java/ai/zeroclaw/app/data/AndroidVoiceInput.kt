package ai.zeroclaw.app.data

import android.content.Context
import android.content.Intent
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * On-phone push-to-talk for the composer, using Android's SpeechRecognizer.
 * Audio never leaves the device; this is the `android` voice backend.
 */
class AndroidVoiceInput(private val context: Context) {

    interface Listener {
        fun onStarted()
        fun onTranscript(text: String)
        fun onFailed(message: String)
        fun onStopped()
    }

    var listener: Listener? = null

    private var recognizer: SpeechRecognizer? = null

    val isActive: Boolean get() = recognizer != null

    fun start() {
        if (recognizer != null) return
        val recognizer = runCatching { SpeechRecognizer.createSpeechRecognizer(context) }
            .getOrElse { error ->
                listener?.onFailed("Speech recognizer unavailable: ${error.message}")
                return
            }
        this.recognizer = recognizer
        recognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: android.os.Bundle?) {
                    listener?.onStarted()
                }

                override fun onResults(results: android.os.Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                    release()
                    if (text.isNullOrEmpty()) {
                        listener?.onFailed("No speech was recognized.")
                    } else {
                        listener?.onTranscript(text)
                    }
                }

                override fun onError(error: Int) {
                    release()
                    listener?.onFailed(describeError(error))
                }

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
        runCatching { recognizer.startListening(intent) }.onFailure { error ->
            release()
            listener?.onFailed("Could not start speech recognition: ${error.message}")
        }
    }

    /** Finalize after a long press; a quick tap lets end-of-speech do it. */
    fun stop() {
        runCatching { recognizer?.stopListening() }
    }

    fun cancel() {
        runCatching { recognizer?.cancel() }
        release()
        listener?.onStopped()
    }

    fun destroy() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun release() {
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognized."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was heard."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission is not granted."
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            "The speech recognizer could not reach its service."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "The speech recognizer is busy."
        SpeechRecognizer.ERROR_AUDIO -> "The microphone could not be opened."
        else -> "Speech recognition failed (code $error)."
    }
}
