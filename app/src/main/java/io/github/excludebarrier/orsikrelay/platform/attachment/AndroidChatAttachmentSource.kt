package io.github.excludebarrier.orsikrelay.platform.attachment

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentMetadata
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentSource
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Production [ChatAttachmentSource]: reads exactly the `content://` URI the
 * user picked, through the app's own ContentResolver permission, and bounds
 * every read.
 *
 * Safety rules:
 * - only the given URI is opened; no path is derived from the display name and
 *   no filesystem path is ever constructed;
 * - the size limit is enforced while reading (an over-sized stream is aborted
 *   and reported as unusable instead of being buffered);
 * - every failure (revoked permission, missing provider, unreadable stream)
 *   returns `null`, never an exception or provider text;
 * - the decoded thumbnail is bounded by [THUMBNAIL_MAX_PIXELS] so a malformed
 *   or huge image cannot exhaust memory.
 */
class AndroidChatAttachmentSource(context: Context) : ChatAttachmentSource {

    private val contentResolver: ContentResolver = context.applicationContext.contentResolver

    override suspend fun readMetadata(contentUri: String): ChatAttachmentMetadata? =
        withContext(Dispatchers.IO) {
            val uri = runCatching { Uri.parse(contentUri) }.getOrNull() ?: return@withContext null
            if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@withContext null
            try {
                var name: String? = null
                var size = -1L
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                            name = cursor.getString(nameIndex)
                        }
                        val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                            size = cursor.getLong(sizeIndex)
                        }
                    }
                }
                val type = contentResolver.getType(uri)
                ChatAttachmentMetadata(
                    displayName = name.orEmpty(),
                    mimeType = type.orEmpty(),
                    sizeBytes = size
                )
            } catch (_: Exception) {
                null
            }
        }

    override suspend fun readInlineDataUrl(
        contentUri: String,
        mimeType: String,
        maxBytes: Long
    ): String? = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(contentUri) }.getOrNull() ?: return@withContext null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return@withContext null
        val bytes = try {
            val stream: InputStream = contentResolver.openInputStream(uri)
                ?: return@withContext null
            stream.use { readBounded(it, maxBytes) }
        } catch (_: Exception) {
            null
        } ?: return@withContext null
        try {
            val encoded = Base64.encodeToString(bytes, Base64.NO_WRAP)
            "data:$mimeType;base64,$encoded"
        } catch (_: Exception) {
            null
        }
    }

    /** Reads at most [maxBytes]; returns `null` as soon as the stream is larger. */
    private fun readBounded(stream: InputStream, maxBytes: Long): ByteArray? {
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        var total = 0L
        while (true) {
            val read = stream.read(chunk)
            if (read < 0) break
            total += read
            if (total > maxBytes) return null
            buffer.write(chunk, 0, read)
        }
        val bytes = buffer.toByteArray()
        return if (bytes.isEmpty()) null else bytes
    }

    private companion object {
        const val READ_CHUNK_BYTES = 64 * 1024
        const val THUMBNAIL_MAX_PIXELS = 256
    }
}

/**
 * Decodes a small square-ish preview of one picked image for the attachment
 * chip. Purely cosmetic and bounded: the returned bitmap is at most
 * [maxPixels] on its longer edge, and every failure (unreadable URI, undecodable
 * bytes, out-of-memory) yields `null` so the UI falls back to a generic icon.
 * No new image dependency is introduced for this.
 */
object ChatAttachmentThumbnails {

    fun decode(context: Context, contentUri: String, maxPixels: Int = 256): Bitmap? {
        val resolver = context.applicationContext.contentResolver
        val uri = runCatching { Uri.parse(contentUri) }.getOrNull() ?: return null
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxPixels)
            }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (_: Exception) {
            null
        } catch (_: OutOfMemoryError) {
            null
        }
    }

    private fun sampleSizeFor(width: Int, height: Int, maxPixels: Int): Int {
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxPixels) {
            sample *= 2
            longest /= 2
        }
        return sample
    }
}
