package ai.zeroclaw.app.data

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

enum class TtsStatus { Idle, Playing, Paused }

data class TtsPlayback(
    val key: String? = null,
    val status: TtsStatus = TtsStatus.Idle,
    /** Character offset of the last spoken range, used to resume after pause. */
    val offset: Int = 0,
)

/**
 * Small wrapper around Android's platform TTS engine.
 *
 * Android TTS has no pause API, so pause records the last `onRangeStart`
 * offset and stops the engine. Resume speaks the remaining text from that
 * offset. Reset stops and rewinds to the beginning.
 */
class TtsController(context: Context) {
    private var tts: TextToSpeech? = null
    private val _state = MutableStateFlow(TtsPlayback())
    val state: StateFlow<TtsPlayback> = _state.asStateFlow()

    private var ready = false
    private var pending: (() -> Unit)? = null
    private var currentKey: String? = null
    private var baseOffset = 0
    private var activeChunkStart = 0
    private var lastRangeStart = 0
    private var lastChunkId = ""
    private var generation = 0

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val language = tts?.setLanguage(Locale.getDefault())
                if (language == TextToSpeech.LANG_MISSING_DATA ||
                    language == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    tts?.setLanguage(Locale.US)
                }
                pending?.invoke()
                pending = null
            } else {
                pending = null
                _state.value = TtsPlayback()
            }
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                val id = utteranceId ?: return
                activeChunkStart = chunkStart(id)
                lastRangeStart = 0
                _state.value = TtsPlayback(
                    key = currentKey,
                    status = TtsStatus.Playing,
                    offset = activeChunkStart,
                )
            }

            override fun onDone(utteranceId: String?) {
                if (utteranceId == lastChunkId &&
                    _state.value.status == TtsStatus.Playing
                ) {
                    _state.value = TtsPlayback()
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                _state.value = TtsPlayback()
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                _state.value = TtsPlayback()
            }

            override fun onRangeStart(
                utteranceId: String?,
                start: Int,
                end: Int,
                frame: Int,
            ) {
                lastRangeStart = start
                _state.value = TtsPlayback(
                    key = currentKey,
                    status = TtsStatus.Playing,
                    offset = activeChunkStart + start,
                )
            }
        })
    }

    /** Start, pause, or resume [speechText] under [key]. */
    fun toggle(key: String, speechText: String) {
        val current = _state.value
        when {
            current.key == key && current.status == TtsStatus.Playing -> pause()
            current.key == key && current.status == TtsStatus.Paused ->
                start(key, speechText, current.offset)
            else -> start(key, speechText, 0)
        }
    }

    fun pause() {
        val current = _state.value
        if (current.status != TtsStatus.Playing) return
        // Publish Paused before stop(); stop() can deliver onDone/onError and
        // must not overwrite the paused state with Idle.
        _state.value = current.copy(
            status = TtsStatus.Paused,
            offset = activeChunkStart + lastRangeStart,
        )
        tts?.stop()
    }

    fun reset() {
        generation += 1
        lastChunkId = ""
        currentKey = null
        _state.value = TtsPlayback()
        tts?.stop()
    }

    fun shutdown() {
        generation += 1
        lastChunkId = ""
        currentKey = null
        _state.value = TtsPlayback()
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    private fun start(key: String, speechText: String, offset: Int) {
        val action = {
            currentKey = key
            baseOffset = offset.coerceIn(0, speechText.length)
            val remaining = speechText.substring(baseOffset)
            if (remaining.isBlank()) {
                _state.value = TtsPlayback()
            } else {
                generation += 1
                val max = TextToSpeech.getMaxSpeechInputLength().coerceAtLeast(1)
                var cursor = 0
                var first = true
                lastRangeStart = 0
                activeChunkStart = baseOffset
                while (cursor < remaining.length) {
                    val end = (cursor + max).coerceAtMost(remaining.length)
                    val chunkStart = baseOffset + cursor
                    val id = "$generation#$key#$chunkStart"
                    lastChunkId = id
                    tts?.speak(
                        remaining.substring(cursor, end),
                        if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD,
                        Bundle(),
                        id,
                    )
                    first = false
                    cursor = end
                }
                _state.value = TtsPlayback(
                    key = key,
                    status = TtsStatus.Playing,
                    offset = baseOffset,
                )
            }
        }
        if (ready) action() else pending = action
    }

    private fun chunkStart(utteranceId: String): Int =
        utteranceId.substringAfterLast('#').toIntOrNull() ?: baseOffset
}
