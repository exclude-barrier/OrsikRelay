package io.github.excludebarrier.orsikrelay.data.chat

/**
 * The single seam between the chat UI and Android's content providers (P5).
 *
 * The picker hands back a `content://` URI; everything else in the app works
 * with the closed [io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentDraft]
 * model. This interface is deliberately Android-free so the view model and its
 * unit tests never touch a ContentResolver, and it never returns a filesystem
 * path, a raw provider error or any dynamic provider text.
 *
 * Implementations MUST:
 * - read only the exact URI they were given (no path guessing, no traversal);
 * - bound every read (the caller's size gate is a UX gate, never a security
 *   boundary: the server re-validates the body);
 * - return `null` instead of throwing when a provider is missing, refuses the
 *   read, or reports unusable data.
 */
interface ChatAttachmentSource {
    /**
     * Reads the display metadata of one picker result, already limited to the
     * fields the UI shows. `null` when the provider cannot be queried.
     */
    suspend fun readMetadata(contentUri: String): ChatAttachmentMetadata?

    /**
     * Base64-encodes the attachment into the inline `data:<mime>;base64,...`
     * URL the server accepts for image content parts. `null` when the stream
     * cannot be read within [maxBytes] or the MIME type is not an inline image
     * type.
     */
    suspend fun readInlineDataUrl(contentUri: String, mimeType: String, maxBytes: Long): String?
}

/** Provider-reported, display-only metadata of one picker result. */
data class ChatAttachmentMetadata(
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long
)

/**
 * Fail-closed default: no picker result can ever be read, so an attachment
 * cannot be attached by accident when no real source is wired. Production
 * always injects [io.github.excludebarrier.orsikrelay.platform.attachment.AndroidChatAttachmentSource].
 */
object NoopChatAttachmentSource : ChatAttachmentSource {
    override suspend fun readMetadata(contentUri: String): ChatAttachmentMetadata? = null

    override suspend fun readInlineDataUrl(
        contentUri: String,
        mimeType: String,
        maxBytes: Long
    ): String? = null
}
