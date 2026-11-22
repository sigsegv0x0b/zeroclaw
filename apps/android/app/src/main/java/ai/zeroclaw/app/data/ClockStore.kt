package ai.zeroclaw.app.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

/**
 * Records the alarms and timers ZeroClaw created on this phone.
 *
 * Android has no API to enumerate or cancel another app's clock entries: the
 * clock app owns its alarm/timer database and exposes only fire-and-forget
 * intents. So "list" here means "what ZeroClaw set", never "everything on the
 * device", and "delete" drops the record and asks the clock app to dismiss a
 * currently firing alarm. The tool output says so explicitly rather than
 * pretending the system state is known.
 */
@Serializable
data class TrackedClockEntry(
    val id: String,
    /** `alarm` or `timer`. */
    val kind: String,
    val label: String? = null,
    val hour: Int? = null,
    val minute: Int? = null,
    val seconds: Int? = null,
    /** Epoch millis when ZeroClaw created the entry. */
    val createdAt: Long,
)

object ClockStore {

    private const val FILE_NAME = "tracked_clock.json"
    private val lock = Mutex()

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    suspend fun list(context: Context, kind: String? = null): List<TrackedClockEntry> =
        lock.withLock {
            read(context)
                .filter { kind == null || it.kind == kind }
                .sortedByDescending { it.createdAt }
        }

    suspend fun add(
        context: Context,
        kind: String,
        label: String?,
        hour: Int? = null,
        minute: Int? = null,
        seconds: Int? = null,
    ): TrackedClockEntry = lock.withLock {
        val entry = TrackedClockEntry(
            id = UUID.randomUUID().toString().take(8),
            kind = kind,
            label = label?.takeIf { it.isNotBlank() },
            hour = hour,
            minute = minute,
            seconds = seconds,
            createdAt = System.currentTimeMillis(),
        )
        val entries = read(context) + entry
        write(context, entries)
        entry
    }

    suspend fun remove(context: Context, id: String): TrackedClockEntry? = lock.withLock {
        val entries = read(context)
        val removed = entries.firstOrNull { it.id == id } ?: return@withLock null
        write(context, entries.filterNot { it.id == id })
        removed
    }

    private fun read(context: Context): List<TrackedClockEntry> {
        val file = file(context)
        if (!file.exists()) return emptyList()
        return runCatching {
            zcJson.decodeFromString<List<TrackedClockEntry>>(file.readText())
        }.getOrDefault(emptyList())
    }

    private fun write(context: Context, entries: List<TrackedClockEntry>) {
        runCatching {
            file(context).writeText(zcJson.encodeToString(entries))
        }
    }
}
