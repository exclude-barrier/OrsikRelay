package io.github.excludebarrier.orsikrelay.domain.chat

import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentDraftHolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM unit tests for the attachment gate: picker-result validation,
 * display-name sanitization, outbound turn bodies with inline image data
 * URLs, the optional-text validator, and the process-scoped draft holder.
 *
 * All values are synthetic fixtures; no Android APIs, no I/O.
 */
class ChatAttachmentTest {

    private companion object {
        const val LOCAL_ID = "local-1"
        const val CONTENT_URI = "content://media/internal/images/123"
        const val DISPLAY_NAME = "photo.png"
        const val PNG_URL = "data:image/png;base64,AA=="
        const val JPEG_URL = "data:image/jpeg;base64,AA=="
        const val SANE_SIZE_BYTES = 1024L
    }

    private fun rejectedError(result: Result<ChatAttachmentDraft>): ChatAttachmentError? =
        (result.exceptionOrNull() as? ChatAttachmentRules.AttachmentRejected)?.error

    private fun validValidate(
        localId: String = LOCAL_ID,
        contentUri: String = CONTENT_URI,
        mimeType: String = "image/png",
        sizeBytes: Long = SANE_SIZE_BYTES
    ) = ChatAttachmentRules.validate(
        localId = localId,
        contentUri = contentUri,
        displayName = DISPLAY_NAME,
        mimeType = mimeType,
        sizeBytes = sizeBytes
    )

    // ---------------------------------------------------------------------
    // ChatAttachmentRules.validate
    // ---------------------------------------------------------------------

    @Test
    fun `supported mime types at a sane size are accepted as images`() {
        for (mime in listOf("image/jpeg", "image/png", "image/webp")) {
            val result = validValidate(mimeType = mime)
            assertTrue("$mime must be accepted", result.isSuccess)
            val draft = result.getOrThrow()
            assertEquals(ChatAttachmentKind.IMAGE, draft.kind)
            assertEquals(mime, draft.mimeType)
            assertEquals(SANE_SIZE_BYTES, draft.sizeBytes)
        }
    }

    @Test
    fun `unsupported mime types are rejected with unsupported type`() {
        for (mime in listOf("application/pdf", "text/plain", "application/zip", "")) {
            val result = validValidate(mimeType = mime)
            assertTrue("$mime must be rejected", result.isFailure)
            assertEquals(ChatAttachmentError.UnsupportedType, rejectedError(result))
        }
    }

    @Test
    fun `a known oversized size is rejected while an unknown size is not`() {
        // A non-positive size means "the provider did not report one"
        // (common for cloud-backed picker items), not "too large": the real
        // bound is enforced while reading the bytes at send time.
        assertTrue(validValidate(sizeBytes = 0L).isSuccess)
        assertEquals(
            ChatAttachmentError.TooLarge,
            rejectedError(validValidate(sizeBytes = ChatAttachmentRules.MAX_IMAGE_BYTES + 1L))
        )
    }

    @Test
    fun `boundary sizes are accepted`() {
        assertTrue(validValidate(sizeBytes = 1L).isSuccess)
        assertTrue(validValidate(sizeBytes = ChatAttachmentRules.MAX_IMAGE_BYTES).isSuccess)
    }

    @Test
    fun `blank local id or content uri is rejected with unreadable`() {
        assertEquals(
            ChatAttachmentError.Unreadable,
            rejectedError(validValidate(localId = ""))
        )
        assertEquals(
            ChatAttachmentError.Unreadable,
            rejectedError(validValidate(localId = "   "))
        )
        assertEquals(
            ChatAttachmentError.Unreadable,
            rejectedError(validValidate(contentUri = ""))
        )
        assertEquals(
            ChatAttachmentError.Unreadable,
            rejectedError(validValidate(contentUri = "  "))
        )
    }

    @Test
    fun `mime type is normalized to trimmed lower case`() {
        val draft = validValidate(mimeType = " IMAGE/PNG ").getOrThrow()
        assertEquals("image/png", draft.mimeType)

        val webp = validValidate(mimeType = "Image/Webp").getOrThrow()
        assertEquals("image/webp", webp.mimeType)
    }

    @Test
    fun `validate sanitizes the display name of the accepted draft`() {
        val result = ChatAttachmentRules.validate(
            localId = LOCAL_ID,
            contentUri = CONTENT_URI,
            displayName = "DCIM\\Shot\u000101.png",
            mimeType = "image/png",
            sizeBytes = SANE_SIZE_BYTES
        )
        assertEquals("DCIM_Shot 01.png", result.getOrThrow().displayName)
    }

    // ---------------------------------------------------------------------
    // ChatAttachmentRules.sanitizeDisplayName
    // ---------------------------------------------------------------------

    @Test
    fun `sanitize display name strips path separators`() {
        assertEquals("DCIM_Cam_shot.png", ChatAttachmentRules.sanitizeDisplayName("DCIM/Cam\\shot.png", "image/png"))
    }

    @Test
    fun `sanitize display name replaces iso control characters`() {
        assertEquals("ab c d", ChatAttachmentRules.sanitizeDisplayName("ab\u0001c\u0002d", "image/png"))
        assertEquals("plain", ChatAttachmentRules.sanitizeDisplayName("\u0000plain", "image/png"))
    }

    @Test
    fun `sanitize display name strips unicode format characters`() {
        // A provider-controlled name must not be able to reorder or hide its
        // own label with bidi overrides or zero-width marks (Cf category).
        val sanitized = ChatAttachmentRules.sanitizeDisplayName(
            "\u202Egnp.exe\u202C\u200B.png",
            "image/png"
        )
        assertEquals("gnp.exe.png", sanitized)
    }

    @Test
    fun `sanitize display name strips supplementary plane format characters`() {
        // U+E0001 LANGUAGE TAG and U+1D173 MUSICAL SYMBOL BEGIN BEAM are Cf
        // code points OUTSIDE the BMP: a per-Char filter would keep their two
        // surrogate units, so the filter must walk code points.
        val sanitized = ChatAttachmentRules.sanitizeDisplayName(
            "ta\uDB40\uDC01g\uD834\uDD73.png",
            "image/png"
        )
        assertEquals("tag.png", sanitized)
        assertTrue(
            "no format character survives",
            sanitized.none { Character.getType(it) == Character.SURROGATE.toInt() }
        )
    }

    @Test
    fun `sanitize display name drops unpaired surrogates`() {
        assertEquals("name.png", ChatAttachmentRules.sanitizeDisplayName("na\uD83Dme.png", "image/png"))
    }

    @Test
    fun `sanitize display name never truncates inside a surrogate pair`() {
        // 119 ASCII chars + an emoji (2 UTF-16 units) crosses the 120 bound in
        // the middle of the pair.
        val name = "x".repeat(119) + "\uD83D\uDE00" + ".png"
        val sanitized = ChatAttachmentRules.sanitizeDisplayName(name, "image/png")
        assertEquals(119, sanitized.length)
        assertTrue("no trailing high surrogate", !Character.isHighSurrogate(sanitized.last()))
    }

    @Test
    fun `sanitize display name keeps a normal name untouched`() {
        assertEquals("photo 2026-09-27.png", ChatAttachmentRules.sanitizeDisplayName("photo 2026-09-27.png", "image/png"))
    }

    @Test
    fun `sanitize display name trims surrounding whitespace`() {
        assertEquals("photo.png", ChatAttachmentRules.sanitizeDisplayName("  photo.png  ", "image/png"))
    }

    @Test
    fun `sanitize display name truncates at 120 chars`() {
        val longName = "x".repeat(150)
        val sanitized = ChatAttachmentRules.sanitizeDisplayName(longName, "image/png")
        assertEquals(ChatAttachmentRules.MAX_DISPLAY_NAME_CHARS, sanitized.length)
        assertEquals("x".repeat(120), sanitized)
    }

    @Test
    fun `sanitize display name falls back to mime default for blank name`() {
        assertEquals("image.jpg", ChatAttachmentRules.sanitizeDisplayName("", "image/jpeg"))
        assertEquals("image.png", ChatAttachmentRules.sanitizeDisplayName("   ", "image/png"))
        assertEquals("image.webp", ChatAttachmentRules.sanitizeDisplayName("  ", "image/webp"))
        assertEquals("attachment", ChatAttachmentRules.sanitizeDisplayName("", "application/pdf"))
    }

    // ---------------------------------------------------------------------
    // ChatOutboundMessage.create
    // ---------------------------------------------------------------------

    @Test
    fun `text only message is created without images`() {
        val message = ChatOutboundMessage.create("hello world")
        assertNotNull(message)
        assertEquals("hello world", message!!.text)
        assertFalse(message.hasImages)
        assertTrue(message.imageDataUrls.isEmpty())
    }

    @Test
    fun `blank text with one image is created`() {
        val message = ChatOutboundMessage.create("   ", listOf(PNG_URL))
        assertNotNull(message)
        assertEquals("", message!!.text)
        assertTrue(message.hasImages)
        assertEquals(listOf(PNG_URL), message.imageDataUrls)
    }

    @Test
    fun `blank text without an image is rejected`() {
        assertNull(ChatOutboundMessage.create("   "))
        assertNull(ChatOutboundMessage.create(""))
    }

    @Test
    fun `more than one image is rejected`() {
        assertEquals(ChatOutboundMessage.MAX_IMAGES, 1)
        assertNull(ChatOutboundMessage.create("hi", listOf(PNG_URL, JPEG_URL)))
    }

    @Test
    fun `non image data url is rejected`() {
        assertNull(ChatOutboundMessage.create("hi", listOf("data:text/plain;base64,AA==")))
    }

    @Test
    fun `data url without comma is rejected`() {
        assertNull(ChatOutboundMessage.create("hi", listOf("data:image/png;base64")))
    }

    @Test
    fun `plain local path is rejected`() {
        assertNull(ChatOutboundMessage.create("hi", listOf("/storage/emulated/0/DCIM/a.jpg")))
    }

    @Test
    fun `oversized text with an image is rejected`() {
        val oversized = "x".repeat(ChatMessageValidator.MAX_MESSAGE_LENGTH + 1)
        assertNull(ChatOutboundMessage.create(oversized, listOf(PNG_URL)))
    }

    @Test
    fun `image plus text trims the text`() {
        val message = ChatOutboundMessage.create("  padded text  ", listOf(PNG_URL))
        assertNotNull(message)
        assertEquals("padded text", message!!.text)
        assertTrue(message.hasImages)
        assertEquals(listOf(PNG_URL), message.imageDataUrls)
    }

    // ---------------------------------------------------------------------
    // ChatOutboundMessage.isInlineImageDataUrl
    // ---------------------------------------------------------------------
    @Test
    fun `inline image data url accepts supported image types`() {
        assertTrue(ChatOutboundMessage.isInlineImageDataUrl("data:image/png;base64,AA=="))
        assertTrue(ChatOutboundMessage.isInlineImageDataUrl("data:image/jpeg;base64,AA=="))
        assertTrue(ChatOutboundMessage.isInlineImageDataUrl("data:image/webp;base64,AA=="))
    }

    @Test
    fun `inline image data url rejects unsupported and malformed values`() {
        assertFalse(ChatOutboundMessage.isInlineImageDataUrl("data:image/gif;base64,AA=="))
        assertFalse(ChatOutboundMessage.isInlineImageDataUrl("data:image/png;base64,"))
        assertFalse(ChatOutboundMessage.isInlineImageDataUrl("image/png;base64,AA=="))
        assertFalse(ChatOutboundMessage.isInlineImageDataUrl("data:text/plain;base64,AA=="))
        assertFalse(ChatOutboundMessage.isInlineImageDataUrl(""))
    }

    // ---------------------------------------------------------------------
    // ChatMessageValidator.validateOptional
    // ---------------------------------------------------------------------

    @Test
    fun `validate optional returns empty string for blank input`() {
        assertEquals("", ChatMessageValidator.validateOptional("").getOrThrow())
        assertEquals("", ChatMessageValidator.validateOptional("   \t\n ").getOrThrow())
    }

    @Test
    fun `validate optional still rejects control characters`() {
        assertTrue(ChatMessageValidator.validateOptional("a\u0007b").isFailure)
        assertTrue(ChatMessageValidator.validateOptional("a\u0000b").isFailure)
    }

    @Test
    fun `validate optional trims valid text and keeps line breaks`() {
        assertEquals("hi\nthere", ChatMessageValidator.validateOptional(" hi\nthere ").getOrThrow())
    }

    // ---------------------------------------------------------------------
    // ChatAttachmentDraftHolder
    // ---------------------------------------------------------------------

    private val holderKeys = mutableListOf<Pair<String, String>>()

    private fun track(profileId: String, sessionId: String) {
        holderKeys.add(profileId to sessionId)
    }

    private fun draft(name: String) = ChatAttachmentDraft(
        localId = "local-$name",
        contentUri = "content://media/internal/images/$name",
        displayName = "$name.png",
        mimeType = "image/png",
        sizeBytes = 1024L,
        kind = ChatAttachmentKind.IMAGE
    )

    @After
    fun tearDownHolder() {
        holderKeys.forEach { (profileId, sessionId) ->
            ChatAttachmentDraftHolder.clear(profileId, sessionId)
        }
        holderKeys.clear()
    }

    @Test
    fun `draft holder put get clear round trip`() {
        val profile = "profile-roundtrip"
        val session = "session-roundtrip"
        track(profile, session)

        assertNull(ChatAttachmentDraftHolder.get(profile, session))

        val stored = draft("roundtrip")
        ChatAttachmentDraftHolder.put(profile, session, stored)
        assertEquals(stored, ChatAttachmentDraftHolder.get(profile, session))

        ChatAttachmentDraftHolder.clear(profile, session)
        assertNull(ChatAttachmentDraftHolder.get(profile, session))
    }

    @Test
    fun `draft holder isolates sessions of the same profile`() {
        val profile = "profile-same"
        val sessionA = "session-a"
        val sessionB = "session-b"
        track(profile, sessionA)
        track(profile, sessionB)

        ChatAttachmentDraftHolder.put(profile, sessionA, draft("a"))
        assertNull(ChatAttachmentDraftHolder.get(profile, sessionB))
        assertEquals(draft("a"), ChatAttachmentDraftHolder.get(profile, sessionA))
    }

    @Test
    fun `draft holder isolates profiles with the same session`() {
        val session = "session-shared"
        val profileA = "profile-a"
        val profileB = "profile-b"
        track(profileA, session)
        track(profileB, session)

        ChatAttachmentDraftHolder.put(profileA, session, draft("a"))
        assertNull(ChatAttachmentDraftHolder.get(profileB, session))
        assertEquals(draft("a"), ChatAttachmentDraftHolder.get(profileA, session))
    }

    @Test
    fun `draft holder clear removes only the exact key`() {
        val profile = "profile-clear"
        val sessionA = "session-a"
        val sessionB = "session-b"
        track(profile, sessionA)
        track(profile, sessionB)

        ChatAttachmentDraftHolder.put(profile, sessionA, draft("a"))
        ChatAttachmentDraftHolder.put(profile, sessionB, draft("b"))

        ChatAttachmentDraftHolder.clear(profile, sessionA)
        assertNull(ChatAttachmentDraftHolder.get(profile, sessionA))
        assertEquals(draft("b"), ChatAttachmentDraftHolder.get(profile, sessionB))
    }

    @Test
    fun `draft holder put replaces an existing draft`() {
        val profile = "profile-replace"
        val session = "session-replace"
        track(profile, session)

        ChatAttachmentDraftHolder.put(profile, session, draft("first"))
        val replacement = draft("second")
        ChatAttachmentDraftHolder.put(profile, session, replacement)
        assertEquals(replacement, ChatAttachmentDraftHolder.get(profile, session))
    }
}
