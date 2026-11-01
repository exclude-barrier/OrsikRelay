package io.github.excludebarrier.orsikrelay.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatHeaderStatusTest {

    @Test
    fun aLostRunWinsOverEverything() {
        assertEquals(ChatHeaderStatus.Lost, chatHeaderStatus(turnActive = true, reconnecting = true, lost = true))
    }

    @Test
    fun reconnectingWinsOverWorking() {
        assertEquals(ChatHeaderStatus.Reconnecting, chatHeaderStatus(turnActive = true, reconnecting = true, lost = false))
    }

    @Test
    fun anActiveTurnIsWorking() {
        assertEquals(ChatHeaderStatus.Working, chatHeaderStatus(turnActive = true, reconnecting = false, lost = false))
    }

    @Test
    fun otherwiseTheServerLineShows() {
        assertEquals(ChatHeaderStatus.Idle, chatHeaderStatus(turnActive = false, reconnecting = false, lost = false))
    }
}
