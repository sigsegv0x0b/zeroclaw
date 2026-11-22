package ai.zeroclaw.app.data

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale

/**
 * Photo-library access for `gallery.list` / `gallery.read`.
 *
 * Reads go through MediaStore with the narrowest permission the platform
 * offers (`READ_MEDIA_IMAGES` on 33+, `READ_EXTERNAL_STORAGE` below that), and
 * `gallery.read` downscales before sending: the bytes travel over the node
 * WebSocket as a data URI so the vision model can see them, and a full-size
 * camera JPEG would blow past the frame and image-size budgets.
 */
object GalleryCapabilities {

    private const val MAX_EDGE_PX = 1280
    private const val JPEG_QUALITY = 85

    private val PROJECTION = arrayOf(
        MediaStore.Images.Media._ID,
        MediaStore.Images.Media.DISPLAY_NAME,
        MediaStore.Images.Media.MIME_TYPE,
        MediaStore.Images.Media.SIZE,
        MediaStore.Images.Media.DATE_ADDED,
        MediaStore.Images.Media.WIDTH,
        MediaStore.Images.Media.HEIGHT,
    )

    suspend fun list(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.IO) {
            if (!DevicePermissions.hasMedia(context)) return@withContext PERMISSION_DENIED
            val limit = (args.int("limit") ?: 25).coerceIn(1, 100)
            val resolver = context.contentResolver
            val rows = runCatching {
                resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    PROJECTION,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC",
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameIndex =
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val mimeIndex =
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                    val sizeIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                    val dateIndex =
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                    val widthIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                    val heightIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)

                    buildList {
                        while (cursor.moveToNext() && size < limit) {
                            add(
                                listOf(
                                    cursor.getLong(idIndex).toString(),
                                    cursor.getString(nameIndex) ?: "(unnamed)",
                                    cursor.getString(mimeIndex) ?: "image/*",
                                    cursor.getLong(sizeIndex).toString(),
                                    cursor.getLong(dateIndex).toString(),
                                    cursor.getInt(widthIndex).toString(),
                                    cursor.getInt(heightIndex).toString(),
                                ),
                            )
                        }
                    }
                }
            }.getOrElse { error ->
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Could not query the photo library: ${error.message}",
                )
            } ?: emptyList()

            if (rows.isEmpty()) {
                return@withContext CapabilityOutcome(
                    true,
                    "No images were found in the phone's photo library.",
                )
            }
            val lines = rows.joinToString("\n") { row ->
                val date = DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(
                    Instant.ofEpochSecond(row[4].toLong()).atZone(ZoneId.systemDefault()),
                )
                "- id=${row[0]}  ${row[1]}  ${row[5]}x${row[6]}  " +
                    "${humanSize(row[3].toLong())}  ${row[2]}  added=$date"
            }
            CapabilityOutcome(
                true,
                "Newest ${rows.size} image(s) in the phone's photo library:\n$lines\n" +
                    "Pass an id to gallery.read to look at it.",
            )
        }

    /**
     * `photos.latest`: return the newest images as attachments, mirroring
     * OpenClaw's command of the same name.
     */
    suspend fun latest(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.IO) {
            if (!DevicePermissions.hasMedia(context)) return@withContext PERMISSION_DENIED
            val limit = (args.int("limit") ?: 5).coerceIn(1, 20)
            val maxWidth = (args.int("maxWidth") ?: args.int("max_width") ?: 1024)
                .coerceIn(240, 4096)
            val quality = ((args.double("quality") ?: 0.85).coerceIn(0.1, 1.0) * 100).toInt()
            val resolver = context.contentResolver
            val rows = runCatching {
                resolver.query(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    PROJECTION,
                    null,
                    null,
                    "${MediaStore.Images.Media.DATE_ADDED} DESC",
                )?.use { cursor ->
                    val idIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                    val nameIndex =
                        cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                    val widthIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.WIDTH)
                    val heightIndex = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.HEIGHT)
                    buildList {
                        while (cursor.moveToNext() && size < limit) {
                            add(
                                Quad(
                                    cursor.getLong(idIndex),
                                    cursor.getString(nameIndex) ?: "image",
                                    cursor.getInt(widthIndex),
                                    cursor.getInt(heightIndex),
                                ),
                            )
                        }
                    }
                }
            }.getOrElse { error ->
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Could not query the photo library: ${error.message}",
                )
            } ?: emptyList()

            if (rows.isEmpty()) {
                return@withContext CapabilityOutcome(
                    true,
                    "No images were found in the phone's photo library.",
                )
            }
            val attachments = ArrayList<NodeAttachment>()
            val lines = ArrayList<String>()
            var totalBytes = 0
            for (row in rows) {
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    row.id,
                )
                val bitmap = runCatching { decodeScaled(context, uri, maxWidth) }.getOrNull()
                    ?: continue
                val oriented = runCatching { applyExifRotation(context, uri, bitmap) }
                    .getOrDefault(bitmap)
                val buffer = ByteArrayOutputStream()
                val ok = runCatching {
                    oriented.compress(Bitmap.CompressFormat.JPEG, quality, buffer)
                }.getOrDefault(false)
                if (oriented !== bitmap) {
                    bitmap.recycleIfNotRecycled()
                    oriented.recycleIfNotRecycled()
                } else {
                    bitmap.recycleIfNotRecycled()
                }
                if (!ok) continue
                val bytes = buffer.toByteArray()
                if (totalBytes + bytes.size > MAX_TOTAL_ATTACHMENT_BYTES) {
                    lines += "- id=${row.id} skipped (attachment budget reached)"
                    continue
                }
                totalBytes += bytes.size
                attachments += NodeAttachment(
                    kind = "image",
                    mime = "image/jpeg",
                    dataB64 = Base64.getEncoder().encodeToString(bytes),
                )
                lines += "- id=${row.id}  ${row.name}  ${row.width}x${row.height}  " +
                    "sent as JPEG ${humanSize(bytes.size.toLong())}"
            }
            if (attachments.isEmpty()) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "None of the latest images could be decoded.",
                )
            }
            CapabilityOutcome(
                true,
                "Attached the newest ${attachments.size} image(s):\n" + lines.joinToString("\n"),
                attachments = attachments,
            )
        }

    private data class Quad(val id: Long, val name: String, val width: Int, val height: Int)

    private const val MAX_TOTAL_ATTACHMENT_BYTES = 6 * 1024 * 1024

    suspend fun read(context: Context, args: JsonObject): CapabilityOutcome =
        withContext(Dispatchers.IO) {
            if (!DevicePermissions.hasMedia(context)) return@withContext PERMISSION_DENIED
            val id = args.string("id")?.trim().orEmpty()
            if (id.isEmpty() || id.any { !it.isDigit() }) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "id must be a numeric MediaStore id from gallery.list.",
                )
            }
            val uri = ContentUris.withAppendedId(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                id.toLong(),
            )
            val resolver = context.contentResolver
            val info = imageInfo(context, uri)
                ?: return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Image $id was not found (it may have been deleted).",
                )
            val bitmap = runCatching { decodeScaled(context, uri) }.getOrNull()
                ?: return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Could not decode image $id (unsupported format or unreadable file).",
                )
            val oriented = runCatching { applyExifRotation(context, uri, bitmap) }
                .getOrDefault(bitmap)
            val buffer = ByteArrayOutputStream()
            val compressed = runCatching {
                oriented.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, buffer)
            }.getOrDefault(false)
            val original = if (oriented !== bitmap) {
                bitmap.recycle()
                oriented
            } else {
                bitmap
            }
            original.recycleIfNotRecycled()
            if (!compressed) {
                return@withContext CapabilityOutcome(
                    false,
                    "",
                    "Could not encode image $id for the gateway.",
                )
            }
            val bytes = buffer.toByteArray()
            val attachment = NodeAttachment(
                kind = "image",
                mime = "image/jpeg",
                dataB64 = Base64.getEncoder().encodeToString(bytes),
            )
            CapabilityOutcome(
                true,
                "Attached image $id (${info.name}, ${info.width}x${info.height}, " +
                    "sent ${humanSize(bytes.size.toLong())} as JPEG at ${MAX_EDGE_PX}px max edge).",
                attachments = listOf(attachment),
            )
        }

    private data class ImageInfo(val name: String, val width: Int, val height: Int)

    private fun imageInfo(context: Context, uri: Uri): ImageInfo? {
        val nameAndType = context.contentResolver.query(
            uri,
            arrayOf(
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
            ),
            null,
            null,
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            ImageInfo(
                name = cursor.getString(0) ?: "image",
                width = cursor.getInt(1),
                height = cursor.getInt(2),
            )
        }
        if (nameAndType != null) return nameAndType
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }
        return if (bounds.outWidth > 0) {
            ImageInfo("image", bounds.outWidth, bounds.outHeight)
        } else {
            null
        }
    }

    private fun decodeScaled(context: Context, uri: Uri, maxEdge: Int = MAX_EDGE_PX): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > maxEdge * 2) {
            sample *= 2
        }
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, options)
        }
    }

    private fun applyExifRotation(context: Context, uri: Uri, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            }
        }.getOrNull() ?: ExifInterface.ORIENTATION_NORMAL
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun Bitmap.recycleIfNotRecycled() {
        if (!isRecycled) runCatching { recycle() }
    }

    private fun humanSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }

    private val PERMISSION_DENIED = CapabilityOutcome(
        false,
        "",
        "Photo-library permission is not granted. Open ZeroClaw → Settings → Device " +
            "capabilities → Photos and grant it.",
    )
}
