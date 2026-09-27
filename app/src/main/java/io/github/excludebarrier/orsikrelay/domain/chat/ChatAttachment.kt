package io.github.excludebarrier.orsikrelay.domain.chat

/**
 * Closed client-side model of one chat attachment the user picked (P5 v1).
 *
 * The picker result is normalized into this value before it can reach any
 * sending code. [contentUri] is the picker's `content://` reference: it stays
 * local, is only ever read back through the [ChatAttachmentSource] seam and is
 * never sent to the server, logged, rendered or persisted. [displayName] is
 * display-only text and is never used as a path. Only values that passed
 * [ChatAttachmentRules.validate] can be constructed.
 */
data class ChatAttachmentDraft(
    /** Stable, locally generated id (UI list key and remove target). */
    val localId: String,
    /** Picker `content://` reference; local-only, never transmitted. */
    val contentUri: String,
    /** Display-only file name as reported by the provider, already sanitized. */
    val displayName: String,
    /** Lower-case MIME type as reported by the provider and checked by the rules. */
    val mimeType: String,
    val sizeBytes: Long,
    val kind: ChatAttachmentKind
) {
    /** `true` for every attachment kind whose bytes travel inline in the turn body. */
    val inline: Boolean
        get() = kind == ChatAttachmentKind.IMAGE
}

/** Closed set of attachment kinds P5 v1 supports. */
enum class ChatAttachmentKind { IMAGE }

/** Closed, generic rejection reasons of the attachment gate; none carries provider data. */
enum class ChatAttachmentError {
    /** MIME type is not in [ChatAttachmentRules.SUPPORTED_MIME_TYPES]. */
    UnsupportedType,

    /** Reported size is zero, negative or above [ChatAttachmentRules.MAX_IMAGE_BYTES]. */
    TooLarge,

    /** The provider could not be read (missing stream, revoked permission, unreadable). */
    Unreadable
}

/**
 * The single client-side gate for attachments (P5 v1). Client validation is a
 * UX gate, never a security boundary: the server validates the request body
 * itself (content-part allowlist, 10 MB body cap, MIME/type checks).
 *
 * Scope of P5 v1 is deliberately narrow, derived from the confirmed server
 * contract: the session-chat endpoint accepts `text` and `image_url`/
 * `input_image` content parts, where an image part is either an `http(s)` URL
 * or a `data:image/...` URL, and it rejects `file`/`input_file` parts with
 * `unsupported_content_type`. So images are supported end-to-end and documents
 * are not offered at all.
 */
object ChatAttachmentRules {
    /** Image types the server accepts (`data:image/...`) and Android decodes reliably. */
    val SUPPORTED_MIME_TYPES: Set<String> = setOf(
        "image/jpeg",
        "image/png",
        "image/webp"
    )

    /**
     * Hard client bound for one image's raw bytes (5 MiB). Rationale, not a
     * guess: the server caps the whole request body at
     * `MAX_REQUEST_BYTES = 10_000_000` bytes and answers 413 `body_too_large`
     * above it, and the server's own inline-image path uses the same 5 MiB
     * convention (`_MEDIA_DATA_URL_MAX_BYTES = 5 * 1024 * 1024`). 5 MiB raw
     * becomes ~6.7 MiB as base64 plus JSON overhead, comfortably inside the
     * server cap.
     */
    const val MAX_IMAGE_BYTES: Long = 5L * 1024 * 1024

    /**
     * Bound for the encoded turn body the client will send (base64 image data
     * URL plus message text, before JSON escaping). Kept below the server's
     * 10 MB body cap with margin for the JSON envelope and headers.
     */
    const val MAX_ENCODED_BODY_BYTES: Int = 8 * 1024 * 1024

    /**
     * Validates one picker result. Returns the normalized draft or the closed
     * [ChatAttachmentError]; provider-reported values are never trusted beyond
     * this gate.
     */
    fun validate(
        localId: String,
        contentUri: String,
        displayName: String,
        mimeType: String,
        sizeBytes: Long
    ): Result<ChatAttachmentDraft> {
        val normalizedMime = mimeType.trim().lowercase()
        val kind = when (normalizedMime) {
            in SUPPORTED_MIME_TYPES -> ChatAttachmentKind.IMAGE
            else -> return Result.failure(AttachmentRejected(ChatAttachmentError.UnsupportedType))
        }
        // A provider may not expose OpenableColumns.SIZE at all (common for
        // cloud-backed picker items): a non-positive size means "unknown", not
        // "too large". The real bound is enforced while reading the bytes at
        // send time (readInlineDataUrl with MAX_IMAGE_BYTES), so only a KNOWN
        // size above the limit is rejected here.
        if (sizeBytes > MAX_IMAGE_BYTES) {
            return Result.failure(AttachmentRejected(ChatAttachmentError.TooLarge))
        }
        if (localId.isBlank() || contentUri.isBlank()) {
            return Result.failure(AttachmentRejected(ChatAttachmentError.Unreadable))
        }
        return Result.success(
            ChatAttachmentDraft(
                localId = localId,
                contentUri = contentUri,
                displayName = sanitizeDisplayName(displayName, normalizedMime),
                mimeType = normalizedMime,
                sizeBytes = sizeBytes,
                kind = kind
            )
        )
    }

    /**
     * Display name safety: the provider-reported name is display-only, so it is
     * reduced to a bounded single line of printable characters with path
     * separators and control characters removed. It is never used as a path and
     * never sent to the server.
     */
    fun sanitizeDisplayName(rawName: String, mimeType: String): String {
        val cleaned = rawName
            // Unicode FORMAT (Cf) characters carry no visible glyph but reorder
            // or hide text (e.g. U+202E RIGHT-TO-LEFT OVERRIDE, bidi isolates,
            // zero-width marks). A provider-controlled file name must not be
            // able to spoof its own chip label, so they are dropped entirely
            // instead of leaving stray separators behind.
            .filter { character -> Character.getType(character) != Character.FORMAT.toInt() }
            .map { character ->
                when {
                    character.isISOControl() -> ' '
                    character == '/' || character == '\\' -> '_'
                    else -> character
                }
            }
            .joinToString("")
            .trim()
        val base = cleaned.ifBlank { defaultDisplayName(mimeType) }
        return if (base.length > MAX_DISPLAY_NAME_CHARS) {
            base.take(MAX_DISPLAY_NAME_CHARS)
        } else {
            base
        }
    }

    private fun defaultDisplayName(mimeType: String): String = when (mimeType) {
        "image/jpeg" -> "image.jpg"
        "image/png" -> "image.png"
        "image/webp" -> "image.webp"
        else -> "attachment"
    }

    /** Closed rejection of one picker result. */
    class AttachmentRejected(val error: ChatAttachmentError) : Exception() {
        init {
            require(error.name.isNotBlank())
        }
    }

    /** Bounded display name; the full provider name is never modelled. */
    const val MAX_DISPLAY_NAME_CHARS: Int = 120
}
