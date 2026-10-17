package io.github.excludebarrier.orsikrelay.domain.chat

/**
 * One outbound chat turn body: the user text plus zero or more inline
 * image data URLs.
 *
 * Wire shape, derived from the confirmed server contract
 * (`_normalize_multimodal_content` in the Hermes API server):
 * - text-only stays a plain JSON string (`{"message": "<text>"}`) so every
 *   existing text-only turn is byte-identical to before attachment support;
 * - with attachments the message becomes a content-part list
 *   `[{"type":"text","text":...},{"type":"image_url","image_url":{"url":"data:image/...;base64,..."}}]`.
 *
 * Only values built by [create] exist, so an invalid shape cannot reach the
 * transport. Nothing here is persisted, logged or rendered.
 */
data class ChatOutboundMessage(
    val text: String,
    /** Inline `data:image/...` URLs; exactly zero (text only) or more images. */
    val imageDataUrls: List<String> = emptyList()
) {
    /** `true` when the body carries at least one inline image. */
    val hasImages: Boolean
        get() = imageDataUrls.isNotEmpty()

    companion object {
        /** At most one attachment per turn (body budget and UI determinism). */
        const val MAX_IMAGES = 1

        private const val DATA_IMAGE_PREFIX = "data:image/"

        /**
         * Builds one validated turn body, or `null` when it cannot be sent:
         * blank text without an image, oversized text, more images than
         * [MAX_IMAGES], a malformed data URL, or an encoded body above
         * [ChatAttachmentRules.MAX_ENCODED_BODY_BYTES].
         */
        fun create(text: String, imageDataUrls: List<String> = emptyList()): ChatOutboundMessage? {
            if (imageDataUrls.size > MAX_IMAGES) return null
            if (imageDataUrls.any { !isInlineImageDataUrl(it) }) return null
            val trimmed = text.trim()
            val validated = if (imageDataUrls.isEmpty()) {
                // Text-only keeps the existing contract: the shared message
                // validator decides, and a blank message is rejected.
                ChatMessageValidator.validate(trimmed).getOrNull() ?: return null
            } else {
                ChatMessageValidator.validateOptional(trimmed).getOrNull() ?: return null
            }
            val encodedSize = validated.length + imageDataUrls.sumOf { it.length }
            if (encodedSize > ChatAttachmentRules.MAX_ENCODED_BODY_BYTES) return null
            return ChatOutboundMessage(text = validated, imageDataUrls = imageDataUrls)
        }

        /**
         * `true` for a well-formed inline image data URL: the server accepts
         * only `data:image/...` values with a comma separating the header from
         * the payload, so anything else must never be sent.
         */
        fun isInlineImageDataUrl(value: String): Boolean {
            if (!value.startsWith(DATA_IMAGE_PREFIX)) return false
            val comma = value.indexOf(',')
            if (comma <= DATA_IMAGE_PREFIX.length) return false
            val mime = value.substring("data:".length, comma).substringBefore(';').lowercase()
            if (mime !in ChatAttachmentRules.SUPPORTED_MIME_TYPES) return false
            return value.length > comma + 1
        }

        /** Convenience: the text-only body used before attachment support. */
        fun textOnly(text: String): ChatOutboundMessage? = create(text, emptyList())
    }
}
