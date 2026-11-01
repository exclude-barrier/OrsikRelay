package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasProgressBarRangeInfo
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.ui.common.NoticeAction
import io.github.excludebarrier.orsikrelay.ui.common.NoticeCard
import io.github.excludebarrier.orsikrelay.ui.common.NoticeTone
import io.github.excludebarrier.orsikrelay.ui.components.TAG_WORKING_GLYPH
import io.github.excludebarrier.orsikrelay.ui.components.WorkingGlyph
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import org.junit.Rule
import org.junit.Test

/** The relay glyph as the app's only loading indicator. */
class WorkingGlyphUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun theGlyphReportsAnIndeterminateProgressWithItsLabel() {
        composeRule.setContent { HermesTheme { WorkingGlyph(contentDescription = "Lädt") } }

        composeRule.onNode(
            hasTestTag(TAG_WORKING_GLYPH) and
                hasProgressBarRangeInfo(ProgressBarRangeInfo.Indeterminate) and
                hasContentDescription("Lädt")
        ).assertExists()
    }

    @Test
    fun aTooSmallGlyphIsRaisedToTheMinimumSize() {
        composeRule.setContent { HermesTheme { WorkingGlyph(size = 8.dp) } }

        composeRule.onNodeWithTag(TAG_WORKING_GLYPH)
            .assertWidthIsEqualTo(16.dp)
            .assertHeightIsEqualTo(16.dp)
    }

    @Test
    fun aLoadingNoticeActionCarriesTheGlyph() {
        showAction(loading = true)

        composeRule.onNode(
            hasTestTag(TAG_WORKING_GLYPH) and hasAnyAncestor(hasTestTag(ACTION)),
            useUnmergedTree = true
        ).assertExists()
    }

    @Test
    fun anIdleNoticeActionHasNoGlyph() {
        showAction(loading = false)

        composeRule.onNodeWithTag(TAG_WORKING_GLYPH, useUnmergedTree = true).assertDoesNotExist()
    }

    private fun showAction(loading: Boolean) {
        composeRule.setContent {
            HermesTheme {
                NoticeCard(
                    tone = NoticeTone.Error,
                    title = "Lauf verloren",
                    actions = listOf(NoticeAction(label = "Abschließen", onClick = {}, enabled = !loading, loading = loading, testTag = ACTION))
                )
            }
        }
    }

    private companion object {
        const val ACTION = "notice_action"
    }
}
