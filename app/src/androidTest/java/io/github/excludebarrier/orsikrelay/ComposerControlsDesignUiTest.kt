package io.github.excludebarrier.orsikrelay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import io.github.excludebarrier.orsikrelay.ui.chat.ComposerBar
import io.github.excludebarrier.orsikrelay.ui.chat.RunComposerControls
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_COMPOSER_SEND
import io.github.excludebarrier.orsikrelay.ui.chat.TAG_RUN_STOP
import io.github.excludebarrier.orsikrelay.ui.components.ExpressiveShape
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * Design v2 finding 6: one send symbol and one button shape. The steer
 * action shows the same arrow as send (not the old paper plane), and stop
 * and send are cookie-shaped like the dock's send button.
 */
class ComposerControlsDesignUiTest {
    @get:Rule
    val composeRule = createComposeRule()

    private var referenceIcon by mutableStateOf<ImageVector?>(null)
    private var referenceOffset by mutableStateOf(IntOffset.Zero)

    private fun setBar(compact: Boolean, running: Boolean) {
        composeRule.setContent {
            HermesTheme(darkTheme = true) {
                Box(Modifier.fillMaxSize()) {
                    val icon = referenceIcon
                    if (icon != null) {
                        Reference(icon, REF_ICON, Modifier.offset { referenceOffset })
                    } else ComposerBar(
                        draft = "",
                        onDraftChange = {},
                        sendEnabled = true,
                        onSend = {},
                        turnActive = running,
                        attachment = null,
                        attachmentThumbnail = null,
                        attachmentBusy = false,
                        attachmentErrorLabel = null,
                        attachmentAddLabel = "add",
                        attachmentRemoveLabel = "remove",
                        attachmentBusyLabel = "busy",
                        onPickAttachment = {},
                        onRemoveAttachment = {},
                        compact = compact,
                        runControls = if (running) {
                            RunComposerControls(
                                steerDraft = "",
                                onSteerDraftChange = {},
                                steerInFlight = false,
                                onSteer = {},
                                stopInFlight = false,
                                onStop = {}
                            )
                        } else {
                            null
                        }
                    )
                }
            }
        }
    }

    @Composable
    private fun Reference(icon: ImageVector, tag: String, modifier: Modifier) {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceContainer)) {
            IconButton(onClick = {}, modifier = Modifier.testTag(tag)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    private fun referenceAt(icon: ImageVector, bounds: Rect): ImageBitmap {
        composeRule.runOnIdle {
            referenceOffset = IntOffset.Zero
            referenceIcon = icon
        }
        val reference = composeRule.onNodeWithTag(REF_ICON)
        val initial = reference.fetchSemanticsNode().boundsInRoot
        composeRule.runOnIdle {
            referenceOffset = IntOffset(
                (bounds.left - initial.left).roundToInt(),
                (bounds.top - initial.top).roundToInt()
            )
        }
        // Stroke antialiasing depends on the final canvas position. Render
        // each reference at the product's position, with identical bounds;
        // keep the strict pixel comparison and its original threshold.
        assertEquals(bounds, reference.fetchSemanticsNode().boundsInRoot)
        return reference.captureToImage()
    }

    private fun shapeOf(tag: String) =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.Shape) { null }

    private fun ImageBitmap.matchShare(other: ImageBitmap): Float {
        val a = toPixelMap()
        val b = other.toPixelMap()
        assertEquals(a.width, b.width)
        assertEquals(a.height, b.height)
        var same = 0
        for (x in 0 until a.width) for (y in 0 until a.height) if (a[x, y] == b[x, y]) same++
        return same.toFloat() / (a.width * a.height)
    }

    @Test
    fun stopIsACookieAndSteerUsesTheSendArrow() {
        setBar(compact = false, running = true)

        assertEquals(ExpressiveShape.Cookie.shape, shapeOf(TAG_RUN_STOP))
        val steerNode = composeRule.onNodeWithTag("chatSteerSend")
        val bounds = steerNode.fetchSemanticsNode().boundsInRoot
        val steer = steerNode.captureToImage()
        val arrow = referenceAt(OrsikIcons.ArrowUp, bounds)
        val plane = referenceAt(OrsikIcons.Send, bounds)
        val arrowMatch = steer.matchShare(arrow)
        val planeMatch = steer.matchShare(plane)
        assertTrue("steer icon must be the send arrow (match $arrowMatch)", arrowMatch > 0.99f)
        assertTrue("steer icon must not be the paper plane (match $planeMatch)", planeMatch < arrowMatch)
    }

    @Test
    fun compactSendIsACookie() {
        setBar(compact = true, running = false)

        assertEquals(ExpressiveShape.Cookie.shape, shapeOf(TAG_COMPOSER_SEND))
    }

    private companion object {
        const val REF_ICON = "reference_icon"
    }
}
