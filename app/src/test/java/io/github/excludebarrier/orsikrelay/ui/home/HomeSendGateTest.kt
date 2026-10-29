package io.github.excludebarrier.orsikrelay.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HomeSendGateTest {
    @Test fun aCommandNeverBecomesATaskOnHome() {
        assertEquals(HomeNotice.CommandInChat, homeSendRefusal("/new", runActive = false))
        assertEquals(HomeNotice.CommandInChat, homeSendRefusal("/model one::two", runActive = false))
        assertEquals(HomeNotice.CommandInChat, homeSendRefusal("/compress", runActive = false))
    }

    @Test fun pathsAndOrdinaryTextAreTasks() {
        assertNull(homeSendRefusal("/var/log/syslog, what is in there?", runActive = false))
        assertNull(homeSendRefusal("Check disk space", runActive = false))
    }

    @Test fun aRunningTaskStillBlocksANewOne() {
        assertEquals(HomeNotice.Busy, homeSendRefusal("Check disk space", runActive = true))
    }
}
