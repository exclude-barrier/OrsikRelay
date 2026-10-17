package io.github.excludebarrier.orsikrelay.domain.chat

/**
 * Closed client-side model of one chat attachment the user picked (v1 attachment support).
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

/** Closed set of attachment kinds the v1 attachment feature supports. */
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
 * The single client-side gate for attachments (v1 attachment support). Client validation is a
 * UX gate, never a security boundary: the server validates the request body
 * itself (content-part allowlist, 10 MB body cap, MIME/type checks).
 *
 * Scope of the v1 attachment feature is deliberately narrow, derived from the confirmed server
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
        val builder = StringBuilder(rawName.length)
        // Iterate by CODE POINT, never per Char: a supplementary-plane FORMAT
        // (Cf) character such as U+E0001 LANGUAGE TAG is a surrogate pair whose
        // two units both report type SURROGATE, so a per-Char filter would let
        // it through. The same walk drops unpaired surrogates, which would
        // otherwise render as replacement glyphs.
        var index = 0
        while (index < rawName.length) {
            val codePoint = rawName.codePointAt(index)
            index += Character.charCount(codePoint)
            when {
                Character.getType(codePoint) == Character.FORMAT.toInt() -> Unit
                Character.getType(codePoint) == Character.SURROGATE.toInt() -> Unit
                codePoint == '/'.code || codePoint == '\\'.code -> builder.append('_')
                Character.isISOControl(codePoint) -> builder.append(' ')
                else -> builder.appendCodePoint(codePoint)
            }
        }
        val cleaned = builder.toString().trim()
        val base = cleaned.ifBlank { defaultDisplayName(mimeType) }
        if (base.length <= MAX_DISPLAY_NAME_CHARS) return base
        // Never cut a surrogate pair in half: a truncated name must not end in
        // an unpaired surrogate that renders as a replacement glyph.
        val cut = if (Character.isHighSurrogate(base[MAX_DISPLAY_NAME_CHARS - 1])) {
            MAX_DISPLAY_NAME_CHARS - 1
        } else {
            MAX_DISPLAY_NAME_CHARS
        }
        return base.take(cut)
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
