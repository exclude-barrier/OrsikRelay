package io.github.excludebarrier.orsikrelay

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentDraft
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentKind
import io.github.excludebarrier.orsikrelay.ui.chat.ComposerBar
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_ATTACHMENT_ADD
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_ATTACHMENT_REMOVE
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_ATTACHMENT_THUMBNAIL
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * P5.1: the composer attachment states the real [io.github.excludebarrier.orsikrelay.ui.chat.ChatScreen]
 * cannot be driven into from a test — the busy state only exists while a real
 * picker result is being read, and a decodable thumbnail needs a real image
 * provider. The test renders the production [ComposerBar] directly with the
 * exact values the view model produces, so the real composable is covered
 * instead of a copy.
 *
 * Send-enable logic is deliberately not asserted here: the enabled flag is an
 * input of this composable, so it is a tautology. It is covered against the
 * real state derivation in [ChatAttachmentChipUiTest].
 *
 * No network, no picker, no credentials: only labels and callbacks.
 */
class ChatComposerBarStateTest {

    @get:Rule
    val composeRule = createComposeRule()

    private var draft: String = ""

    private fun setBar(
        attachment: ChatAttachmentDraft? = null,
        thumbnail: ImageBitmap? = null,
        busy: Boolean = false,
        errorLabel: String? = null,
        turnActive: Boolean = false,
        dark: Boolean = false
    ) {
        composeRule.setContent {
            HermesTheme(darkTheme = dark) {
                ComposerBar(
                    draft = draft,
                    onDraftChange = { draft = it },
                    sendEnabled = true,
                    onSend = {},
                    turnActive = turnActive,
                    attachment = attachment,
                    attachmentThumbnail = thumbnail,
                    attachmentBusy = busy,
                    attachmentErrorLabel = errorLabel,
                    attachmentAddLabel = ADD_LABEL,
                    attachmentRemoveLabel = REMOVE_LABEL,
                    attachmentBusyLabel = BUSY_LABEL,
                    onPickAttachment = {},
                    onRemoveAttachment = {}
                )
            }
        }
    }

    @Test
    fun busyStateShowsProgressAndBlocksThePicker() {
        setBar(busy = true)

        composeRule.onNodeWithText(BUSY_LABEL).assertIsDisplayed()
        // The picker stays disabled while a pick is being read.
        composeRule.onNodeWithTag(TAG_ATTACHMENT_ADD).assertIsNotEnabled()
        // No chip is rendered for a still-unvalidated pick.
        composeRule.onNodeWithTag(TAG_ATTACHMENT_REMOVE).assertDoesNotExist()
        composeRule.onNodeWithTag(TAG_ATTACHMENT_THUMBNAIL).assertDoesNotExist()
    }

    @Test
    fun decodedThumbnailIsRenderedInsideTheChip() {
        setBar(attachment = attachment("holiday.png"), thumbnail = onePixelBitmap())

        composeRule.onNodeWithTag(TAG_ATTACHMENT_THUMBNAIL).assertIsDisplayed()
        composeRule.onNodeWithText("holiday.png").assertIsDisplayed()
    }

    @Test
    fun undecodableAttachmentFallsBackToNameAndSize() {
        // Production passes `null` when the thumbnail cannot be decoded; the
        // chip must still show name, size and remove.
        setBar(attachment = attachment("broken.png"), thumbnail = null)

        composeRule.onNodeWithTag(TAG_ATTACHMENT_THUMBNAIL).assertDoesNotExist()
        composeRule.onNodeWithText("broken.png").assertIsDisplayed()
        composeRule.onNodeWithText("1.4 MiB").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_ATTACHMENT_REMOVE).assertIsDisplayed()
    }

    @Test
    fun errorWithoutAttachmentRendersOnlyTheErrorRow() {
        setBar(errorLabel = UNREADABLE_LABEL)

        composeRule.onNodeWithText(UNREADABLE_LABEL).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_ATTACHMENT_REMOVE).assertDoesNotExist()
        composeRule.onNodeWithTag(TAG_ATTACHMENT_ADD).assertIsEnabled()
    }

    @Test
    fun removeIsVisibleButDisabledWhileATurnRuns() {
        setBar(attachment = attachment("holiday.png"), turnActive = true)

        composeRule.onNodeWithTag(TAG_ATTACHMENT_REMOVE).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_ATTACHMENT_REMOVE).assertIsNotEnabled()
    }

    @Test
    fun chipIsReadableInDarkModeToo() {
        setBar(attachment = attachment("holiday.png"), thumbnail = onePixelBitmap(), dark = true)

        composeRule.onNodeWithTag(TAG_ATTACHMENT_THUMBNAIL).assertIsDisplayed()
        composeRule.onNodeWithText("holiday.png").assertIsDisplayed()
        composeRule.onNodeWithText("1.4 MiB").assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_ATTACHMENT_REMOVE).assertIsDisplayed()
        // The chip stays inside the window in dark mode as well.
        val root = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
        val remove = composeRule.onNodeWithTag(TAG_ATTACHMENT_REMOVE).fetchSemanticsNode().boundsInRoot
        assertTrue(remove.left >= root.left && remove.right <= root.right)
    }

    private fun attachment(name: String) = ChatAttachmentDraft(
        localId = "local-1",
        contentUri = "content://media/external/images/media/42",
        displayName = name,
        mimeType = "image/png",
        sizeBytes = 1_500_000L,
        kind = ChatAttachmentKind.IMAGE
    )

    /** A 1x1 bitmap: enough for the chip to render a real `Image` node. */
    private fun onePixelBitmap(): ImageBitmap =
        android.graphics.Bitmap
            .createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
            .asImageBitmap()

    private companion object {
        const val ADD_LABEL = "Anhang"
        const val REMOVE_LABEL = "Entfernen"
        const val BUSY_LABEL = "Anhang wird gelesen …"
        const val UNREADABLE_LABEL = "Der Anhang konnte nicht gelesen werden."
    }
}
