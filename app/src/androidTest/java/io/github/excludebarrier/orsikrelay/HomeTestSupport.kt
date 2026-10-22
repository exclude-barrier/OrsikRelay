package io.github.excludebarrier.orsikrelay

import io.github.excludebarrier.orsikrelay.ui.onboarding.WELCOME_ENTER_CODE_TAG
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import io.github.excludebarrier.orsikrelay.navigation.AppScreen
import io.github.excludebarrier.orsikrelay.navigation.bottomTabTag
import io.github.excludebarrier.orsikrelay.ui.home.HomeTags
import io.github.excludebarrier.orsikrelay.ui.sessions.SESSIONS_HEADER_TAG

/** Waits until the app shows Home (the start page after a resolved server). */
fun ComposeTestRule.waitForHome(timeoutMillis: Long = 5_000) {
    waitUntil(timeoutMillis) { onAllNodesWithTag(HomeTags.SCREEN).fetchSemanticsNodes().isNotEmpty() }
    waitForIdle()
}

/**
 * Without a saved server the app starts on Welcome; this opens the
 * onboarding form through "Pairing-Code oder API-Key eingeben".
 */
fun ComposeTestRule.openOnboardingFromWelcome(timeoutMillis: Long = 5_000) {
    waitUntil(timeoutMillis) { onAllNodesWithTag(WELCOME_ENTER_CODE_TAG).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag(WELCOME_ENTER_CODE_TAG).performClick()
    waitForIdle()
}

/** Opens the session list through the bottom bar's "Agents" tab. */
fun ComposeTestRule.openAgentsTab(timeoutMillis: Long = 5_000) {
    val tag = bottomTabTag(AppScreen.Sessions)
    waitUntil(timeoutMillis) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
    onNodeWithTag(tag).performClick()
    waitForIdle()
}

/**
 * Waits until the Agents screen (the session list) is shown. Its title text
 * also labels the bottom-bar tab, so the screen is matched by its header tag.
 */
fun ComposeTestRule.waitForAgents(timeoutMillis: Long = 5_000) {
    waitUntil(timeoutMillis) { onAllNodesWithTag(SESSIONS_HEADER_TAG).fetchSemanticsNodes().isNotEmpty() }
    waitForIdle()
}

/**
 * Triggers the Agents list's refresh the way a screen reader does: through
 * its accessibility action (the visible control is pull-to-refresh).
 */
fun ComposeTestRule.refreshAgentsList() {
    val label = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        .getString(R.string.sessions_refresh)
    val action = onNodeWithTag("sessionsPullToRefresh").fetchSemanticsNode()
        .config[androidx.compose.ui.semantics.SemanticsActions.CustomActions]
        .first { it.label == label }
    runOnIdle { action.action() }
    waitForIdle()
}

/**
 * Opens the pushed server management (with a back arrow) the way Home does:
 * through the server name ("Server wechseln").
 */
fun ComposeTestRule.openServerManagementFromHome(timeoutMillis: Long = 5_000) {
    onNodeWithTag(bottomTabTag(AppScreen.Home)).performClick()
    waitUntil(timeoutMillis) {
        onAllNodesWithTag(io.github.excludebarrier.orsikrelay.ui.home.HomeTags.SERVER_NAME).fetchSemanticsNodes().isNotEmpty()
    }
    onNodeWithTag(io.github.excludebarrier.orsikrelay.ui.home.HomeTags.SERVER_NAME).performClick()
    waitForIdle()
}
