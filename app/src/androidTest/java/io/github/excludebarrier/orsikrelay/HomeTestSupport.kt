package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.excludebarrier.orsikrelay.navigation.AppScreen
import io.github.excludebarrier.orsikrelay.navigation.bottomTabTag
import io.github.excludebarrier.orsikrelay.ui.home.HomeTags

/** Waits until the app shows Home (the start page after a resolved server). */
fun ComposeTestRule.waitForHome(timeoutMillis: Long = 5_000) {
    waitUntil(timeoutMillis) { onAllNodesWithTag(HomeTags.SCREEN).fetchSemanticsNodes().isNotEmpty() }
    waitForIdle()
}

/** Opens the session list through the bottom bar's "Agents" tab. */
fun ComposeTestRule.openAgentsTab(timeoutMillis: Long = 5_000) {
    val tag = bottomTabTag(AppScreen.Sessions)
    waitUntil(timeoutMillis) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag(tag).performClick()
    waitForIdle()
}
