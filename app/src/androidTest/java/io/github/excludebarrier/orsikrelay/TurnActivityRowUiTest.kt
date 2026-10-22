package io.github.excludebarrier.orsikrelay

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.ui.chat.TurnActivity
import io.github.excludebarrier.orsikrelay.ui.chat.TurnActivityRow
import io.github.excludebarrier.orsikrelay.ui.components.TAG_WORKING_GLYPH
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test

/**
 * alpha.12 working animation: Orsik's glyph with rotating verbs while
 * Hermes works, a fixed "thinking" status, the running tool's name, and
 * everything at rest when the system animations are off.
 */
class TurnActivityRowUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val verbs = context.resources.getStringArray(R.array.chat_turn_working_verbs)

    private fun show(activity: TurnActivity, toolName: String? = null) {
        val startedAt = SystemClock.elapsedRealtime()
        composeRule.setContent {
            HermesTheme { TurnActivityRow(activity = activity, startedAt = startedAt, toolName = toolName) }
        }
    }

    private fun setAnimatorScale(value: String) {
        // Reading the output to its end waits until the command has run.
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation
            .executeShellCommand("settings put global animator_duration_scale $value")
        ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes() }
    }

    @Test
    fun theFirstVerbIsThePlainWorkingStatusAndTheGlyphShows() {
        // The verb list starts with the established status text.
        org.junit.Assert.assertEquals(context.getString(R.string.chat_turn_working), verbs.first())
        show(TurnActivity.Working)

        composeRule.onNodeWithText(verbs.first()).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_WORKING_GLYPH).assertIsDisplayed()
    }

    @Test
    fun withAnimationsOffTheVerbStays() {
        setAnimatorScale("0")
        show(TurnActivity.Working)
        composeRule.mainClock.advanceTimeBy(10_000)

        composeRule.onNodeWithText(verbs.first()).assertIsDisplayed()
    }

    @Test
    fun withAnimationsOnTheVerbsRotate() {
        setAnimatorScale("1")
        try {
            composeRule.mainClock.autoAdvance = false
            show(TurnActivity.Working)
            composeRule.mainClock.advanceTimeBy(3_000)

            composeRule.onNodeWithText(verbs[1]).assertIsDisplayed()
        } finally {
            setAnimatorScale("0")
        }
    }

    @Test
    fun thinkingStaysAFixedStatus() {
        show(TurnActivity.Thinking)
        composeRule.mainClock.advanceTimeBy(10_000)

        composeRule.onNodeWithText(context.getString(R.string.chat_turn_thinking)).assertIsDisplayed()
    }

    @Test
    fun aRunningToolIsNamedAndAnUnnamedOneStaysGeneric() {
        show(TurnActivity.Tool, toolName = "terminal")
        composeRule.onNodeWithText(context.getString(R.string.chat_turn_tool_named, "terminal")).assertIsDisplayed()
    }

    @Test
    fun anUnnamedToolStaysGeneric() {
        show(TurnActivity.Tool, toolName = null)
        composeRule.onNodeWithText(context.getString(R.string.chat_turn_tool)).assertIsDisplayed()
    }
}
