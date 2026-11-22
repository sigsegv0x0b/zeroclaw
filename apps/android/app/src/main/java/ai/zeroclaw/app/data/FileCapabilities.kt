package ai.zeroclaw.app.data

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * File tools confined to the folder the operator picked with the system folder
 * picker (SAF). The grant is a persistable tree URI, so the agent never needs
 * broad storage permission and cannot escape the chosen tree: every path is
 * resolved by walking that tree, and `..` is rejected before any lookup.
 */
object FileCapabilities {

    private const val MAX_READ_BYTES = 1_048_576
    private const val MAX_TEXT_CHARS = 131_072

    suspend fun list(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.IO) {
            if (!hasFolder(context)) return@withContext NOT_CONFIGURED
            val requested = args.string("path")
            val dir = resolveDirectory(context, requested) ?: run {
                val label = if (requested.isNullOrBlank()) "/" else requested
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Directory not found in the granted folder: $label",
                )
            }
            val children = runCatching { dir.listFiles() }.getOrElse { error ->
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Could not read the granted folder: ${error.message}",
                )
            }
            if (children.isEmpty()) {
                return@withContext CapabilityOutcome(
                    true,
                    "The folder is empty: ${displayPath(context, args.string("path"))}",
                )
            }
            val base = displayPath(context, args.string("path"))
            val lines = children
                .sortedWith(compareBy({ !it.isDirectory }, { it.name?.lowercase() ?: "" }))
                .joinToString("\n") { child ->
                    buildString {
                        append(if (child.isDirectory) "- [dir] " else "- [file] ")
                        append(child.name ?: "(unnamed)")
                        val size = child.length()
                        if (!child.isDirectory && size > 0) {
                            append("  ")
                            append(humanSize(size))
                        }
                        child.type?.let { append("  ").append(it) }
                        append("  modified=")
                        append(formatMillis(child.lastModified()))
                    }
                }
            CapabilityOutcome(
                true,
                "Contents of $base (${children.size}):\n$lines",
            )
        }

    suspend fun read(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.IO) {
            val path = args.string("path")?.trim().orEmpty()
            if (path.isEmpty()) {
                return@withContext CapabilityOutcome(false, "", "path is required")
            }
            if (!hasFolder(context)) return@withContext NOT_CONFIGURED
            val file = resolveFile(context, path) ?: return@withContext CapabilityOutcome(
                false,
                "",
                "File not found in the granted folder: $path",
            )
            if (!file.isFile) {
                return@withContext CapabilityOutcome(false, "", "Not a file: $path")
            }
            val size = file.length()
            if (size > MAX_READ_BYTES) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "File is ${humanSize(size)}; the read cap is ${humanSize(MAX_READ_BYTES.toLong())}.",
                )
            }
            val resolver = context.contentResolver
            val bytes = runCatching {
                resolver.openInputStream(file.uri)?.use { it.readBytes() }
            }.getOrNull() ?: return@withContext CapabilityOutcome(
                false,
                "",
                "Could not open $path in the granted folder.",
            )
            if (bytes.size > MAX_READ_BYTES) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "File grew past the ${humanSize(MAX_READ_BYTES.toLong())} read cap.",
                )
            }
            val mime = file.type
            val text = if (looksLikeText(mime, bytes)) {
                decodeUtf8(bytes)
            } else {
                null
            }
            if (text != null) {
                val clipped = if (text.length > MAX_TEXT_CHARS) {
                    text.take(MAX_TEXT_CHARS) + "\n…[truncated at $MAX_TEXT_CHARS characters]"
                } else {
                    text
                }
                CapabilityOutcome(
                    true,
                    "Contents of $path (${humanSize(bytes.size.toLong())}, ${mime ?: "text"}):\n" +
                        clipped,
                )
            } else {
                CapabilityOutcome(
                    true,
                    "Binary file $path (${humanSize(bytes.size.toLong())}, ${mime ?: "unknown"}). " +
                        "Base64 bytes follow.\n" +
                        Base64.getEncoder().encodeToString(bytes),
                )
            }
        }

    suspend fun create(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.IO) {
            val path = args.string("path")?.trim().orEmpty()
            val content = args.string("content") ?: return@withContext CapabilityOutcome(
                false,
                "",
                "content is required",
            )
            if (path.isEmpty()) {
                return@withContext CapabilityOutcome(false, "", "path is required")
            }
            val segments = normalize(path) ?: return@withContext CapabilityOutcome(
                false,
                "",
                "Invalid path: $path",
            )
            val root = rootDirectory(context) ?: return@withContext NOT_CONFIGURED
            var parent = root
            for (segment in segments.dropLast(1)) {
                parent = parent.findFile(segment)?.takeIf { it.isDirectory }
                    ?: parent.createDirectory(segment)
                    ?: return@withContext CapabilityOutcome(
                        false,
                        "",
                        "Could not create directory $segment under the granted folder.",
                    )
            }
            val name = segments.last()
            val existing = parent.findFile(name)
            if (existing != null && !args.bool("overwrite", default = false)) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "File already exists: $path. Pass overwrite=true to replace it.",
                )
            }
            val target = existing ?: parent.createFile(mimeFor(name), name)
            if (target == null) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Could not create $path in the granted folder.",
                )
            }
            val bytes = content.toByteArray(StandardCharsets.UTF_8)
            val written = runCatching {
                context.contentResolver.openOutputStream(target.uri, "wt")
                    ?.use { it.write(bytes) }
            }
            if (written.isFailure || written.getOrNull() == null) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Could not write $path: ${written.exceptionOrNull()?.message ?: "stream unavailable"}",
                )
            }
            CapabilityOutcome(
                true,
                "Wrote ${humanSize(bytes.size.toLong())} to $path in the granted folder.",
            )
        }

    // ── Resolution ─────────────────────────────────────────────────────────

    private const val NOT_CONFIGURED_MESSAGE =
        "No folder is granted to ZeroClaw. Open ZeroClaw → Settings → Device capabilities → " +
            "Folder and choose one with the system picker."

    private val NOT_CONFIGURED = CapabilityOutcome(false, "", NOT_CONFIGURED_MESSAGE)

    private suspend fun hasFolder(context: Context): Boolean =
        SettingsStore(context).current().nodeFolderUri.isNotBlank()

    private suspend fun rootDirectory(context: Context): DocumentFile? {
        val settings = SettingsStore(context).current()
        if (settings.nodeFolderUri.isBlank()) return null
        val uri = runCatching { Uri.parse(settings.nodeFolderUri) }.getOrNull()
            ?: return null
        return DocumentFile.fromTreeUri(context, uri)
    }

    private suspend fun resolveDirectory(context: Context, path: String?): DocumentFile? {
        val root = rootDirectory(context) ?: return null
        val segments = normalize(path.orEmpty()) ?: return null
        var current = root
        for (segment in segments) {
            current = current.findFile(segment) ?: return null
            if (!current.isDirectory) return null
        }
        return current
    }

    private suspend fun resolveFile(context: Context, path: String): DocumentFile? {
        val root = rootDirectory(context) ?: return null
        val segments = normalize(path) ?: return null
        var current = root
        for (segment in segments) {
            current = current.findFile(segment) ?: return null
        }
        return current
    }

    /** Split a relative path, rejecting anything that could leave the tree. */
    internal fun normalize(path: String): List<String>? {
        val segments = path.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) return null
        return segments
    }

    private suspend fun displayPath(context: Context, path: String?): String {
        val settings = SettingsStore(context).current()
        val folder = settings.nodeFolderName.ifBlank { "the granted folder" }
        val suffix = path?.trim().orEmpty()
        return if (suffix.isEmpty()) "$folder/" else "$folder/$suffix"
    }

    private fun mimeFor(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase(Locale.US)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "text/plain"
    }

    private fun looksLikeText(mime: String?, bytes: ByteArray): Boolean {
        if (mime != null) {
            if (mime.startsWith("text/")) return true
            if (mime in TEXT_APPLICATION_TYPES) return true
            if (mime.startsWith("image/") || mime.startsWith("audio/") ||
                mime.startsWith("video/") || mime == "application/zip" ||
                mime == "application/pdf"
            ) {
                return false
            }
        }
        val decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        return runCatching { decoder.decode(ByteBuffer.wrap(bytes)) }.isSuccess
    }

    private fun decodeUtf8(bytes: ByteArray): String = String(bytes, StandardCharsets.UTF_8)

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private fun formatMillis(millis: Long): String =
        if (millis <= 0) "unknown" else DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
            Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()),
        )

    private val TEXT_APPLICATION_TYPES = setOf(
        "application/json",
        "application/xml",
        "application/x-yaml",
        "application/yaml",
        "application/javascript",
        "application/x-sh",
    )
}
