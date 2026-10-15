package io.github.excludebarrier.orsikrelay

import android.content.Context
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Deterministic IME settling for tests that input text and then click.
 *
 * `performTextInput` focuses the field, which makes the platform open the real
 * soft keyboard asynchronously. The window then redispatches its own (larger)
 * IME inset and the layout moves. `performClick` injects a real touch event at
 * the node's centre, so a click issued while that move is still in flight can
 * land beside the target and never invoke the action — the residual
 * P10-group-B flake.
 *
 * [settleAfterTyping] removes the asynchronous input: it closes the real IME,
 * waits — on an observable condition, never a wall-clock sleep — until the
 * platform reports the real IME inset as gone, and then re-dispatches the
 * measured window insets (`ViewCompat.dispatchApplyWindowInsets` is
 * synchronous) so the Compose tree settles on a stable geometry before the
 * click is injected.
 *
 * This mirrors `ChatLandscapeComposerUiTest.hideRealImeAndWait()` /
 * `settleAfterTyping()` / `dispatchImeInsets()` and is shared by
 * `ChatScreenTurnUiTest` and `ChatTurnNavigationUiTest` (the two classes that
 * still typed and clicked without settling the real IME).
 */
object ImeClickSettle {

    /** Settles the real IME after a text input, before the following click. */
    fun settleAfterTyping(rule: ComposeTestRule) {
        val activity = activityOf(rule)
        hideRealImeAndWait(rule, activity)
        dispatchMeasuredInsets(rule, activity)
    }

    /** Hides the real soft keyboard and waits until its inset is gone. */
    fun hideRealImeAndWait(rule: ComposeTestRule, activity: ComponentActivity) {
        val imm = activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        activity.runOnUiThread {
            imm.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        // Deterministic sync point: wait until the platform reports the real
        // IME inset as gone, instead of a fixed wall-clock sleep. The condition
        // is re-evaluated with a Compose idle round-trip each iteration.
        rule.waitUntil(timeoutMillis = 5_000) { realImeBottom(activity) == 0 }
        rule.waitForIdle()
    }

    /**
     * Re-dispatches the window insets as they are currently measured, so the
     * Compose tree re-applies a stable inset state synchronously.
     */
    fun dispatchMeasuredInsets(rule: ComposeTestRule, activity: ComponentActivity) {
        val rootInsets = ViewCompat.getRootWindowInsets(activity.window.decorView)
        val statusTop = rootInsets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        val navBottom = rootInsets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val imeBottom = rootInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        dispatchImeInsets(rule, activity, statusTop, navBottom, imeBottom)
    }

    /** Injects the given insets; status/nav/IME are set explicitly. */
    fun dispatchImeInsets(
        rule: ComposeTestRule,
        activity: ComponentActivity,
        statusTop: Int,
        navBottom: Int,
        imeBottom: Int
    ) {
        val insets = WindowInsetsCompat.Builder()
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusTop, 0, 0))
            .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBottom))
            .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottom))
            .build()
        activity.runOnUiThread {
            val content = activity.findViewById<View>(android.R.id.content)
            ViewCompat.dispatchApplyWindowInsets(content, insets)
        }
        // `dispatchApplyWindowInsets` is synchronous: it applies the inset to
        // the view tree and drives the Compose relayout read from
        // `WindowInsets.ime`. `waitForIdle` therefore settles on the injected
        // geometry deterministically.
        rule.waitForIdle()
    }

    private fun realImeBottom(activity: ComponentActivity): Int =
        ViewCompat.getRootWindowInsets(activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0

    private fun activityOf(rule: ComposeTestRule): ComponentActivity {
        val androidRule = rule as? AndroidComposeTestRule<*, *>
            ?: error(
                "ImeClickSettle requires the Android host rule " +
                    "(createComposeRule / createAndroidComposeRule)"
            )
        return androidRule.activity
    }
}
