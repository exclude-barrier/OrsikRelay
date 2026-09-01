package io.github.excludebarrier.orsikrelay.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatRouteCodecTest {

    @Test
    fun sessionIdRoundTripsAsOneRouteSegment() {
        val sessionId = "fixture/a?b#c d&ü"

        val route = routeOf(AppScreen.Chat(sessionId))
        val encoded = route.removePrefix("chat/")

        assertFalse(encoded.contains('/'))
        assertFalse(encoded.contains('?'))
        assertFalse(encoded.contains('#'))
        assertEquals(sessionId, decodeRouteArgument(encoded))
    }

    @Test
    fun malformedArgumentDecodesToInvalidEmptyId() {
        assertEquals("", decodeRouteArgument("%%%"))
    }
}
