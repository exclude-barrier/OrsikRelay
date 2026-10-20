package io.github.excludebarrier.orsikrelay.platform.attachment

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The thumbnail bound is one constant, used both as the documented limit and
 * as the default of [ChatAttachmentThumbnails.decode].
 */
class ChatAttachmentThumbnailsTest {

    @Test
    fun thumbnailBoundIs256Pixels() {
        assertEquals(256, ChatAttachmentThumbnails.THUMBNAIL_MAX_PIXELS)
    }

    @Test
    fun decodeSamplingHalvesTheLongerEdgeUntilItFitsTheBound() {
        val max = ChatAttachmentThumbnails.THUMBNAIL_MAX_PIXELS

        assertEquals(1, ChatAttachmentThumbnails.sampleSizeFor(256, 200, max))
        assertEquals(1, ChatAttachmentThumbnails.sampleSizeFor(100, 100, max))
        assertEquals(2, ChatAttachmentThumbnails.sampleSizeFor(512, 256, max))
        assertEquals(4, ChatAttachmentThumbnails.sampleSizeFor(1024, 512, max))
        assertEquals(16, ChatAttachmentThumbnails.sampleSizeFor(4096, 1024, max))
    }
}
