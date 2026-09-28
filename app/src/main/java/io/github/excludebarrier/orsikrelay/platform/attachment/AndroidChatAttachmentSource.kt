package io.github.excludebarrier.orsikrelay.platform.attachment

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import io.github.excludebarrier.orsikrelay.data.chat.AttachmentDataUrl
import io.github.excludebarrier.orsikrelay.data.chat.BoundedAttachmentReader
import io.github.excludebarrier.orsikrelay.data.chat.BoundedReadOutcome
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentMetadata
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentReadResult
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentSource
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
 * - the size limit is enforced while reading: the read is aborted at the bound
 *   and reported as [ChatAttachmentReadResult.TooLarge] instead of being
 *   buffered;
 * - every other failure (revoked permission, missing provider, unreadable or
 *   stalled stream) yields [ChatAttachmentReadResult.Unreadable], never an
 *   exception and never provider text; [readInlineDataUrl] maps both failure
 *   outcomes onto its `null` for callers that cannot act on the difference;
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
    ): String? = when (val result = readInlineDataUrlResult(contentUri, mimeType, maxBytes)) {
        is ChatAttachmentReadResult.DataUrl -> result.value
        ChatAttachmentReadResult.TooLarge, ChatAttachmentReadResult.Unreadable -> null
    }

    override suspend fun readInlineDataUrlResult(
        contentUri: String,
        mimeType: String,
        maxBytes: Long
    ): ChatAttachmentReadResult = withContext(Dispatchers.IO) {
        val uri = runCatching { Uri.parse(contentUri) }.getOrNull()
            ?: return@withContext ChatAttachmentReadResult.Unreadable
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            return@withContext ChatAttachmentReadResult.Unreadable
        }
        // The base64 encode is the biggest allocation of this path, so it stays
        // inside the same fail-closed wrapper as the read itself.
        try {
            val stream: InputStream = contentResolver.openInputStream(uri)
                ?: return@withContext ChatAttachmentReadResult.Unreadable
            when (val outcome = BoundedAttachmentReader.read(stream, maxBytes)) {
                is BoundedReadOutcome.Bytes ->
                    ChatAttachmentReadResult.DataUrl(AttachmentDataUrl.inline(mimeType, outcome.bytes))
                BoundedReadOutcome.TooLarge -> ChatAttachmentReadResult.TooLarge
                BoundedReadOutcome.Unreadable -> ChatAttachmentReadResult.Unreadable
            }
        } catch (_: Exception) {
            ChatAttachmentReadResult.Unreadable
        } catch (_: OutOfMemoryError) {
            ChatAttachmentReadResult.Unreadable
        }
    }

    private companion object {
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
