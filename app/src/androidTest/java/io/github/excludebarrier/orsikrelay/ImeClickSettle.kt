package io.github.excludebarrier.orsikrelay

import android.content.Context
import android.os.Build
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
 * land beside the target and never invoke the action — the residual flake.
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
    private fun hideRealImeAndWait(rule: ComposeTestRule, activity: ComponentActivity) {
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
    private fun dispatchMeasuredInsets(rule: ComposeTestRule, activity: ComponentActivity) {
        val rootInsets = ViewCompat.getRootWindowInsets(activity.window.decorView)
        val statusTop = rootInsets?.getInsets(WindowInsetsCompat.Type.statusBars())?.top ?: 0
        val navBottom = rootInsets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val imeBottom = rootInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        dispatchImeInsets(rule, activity, statusTop, navBottom, imeBottom)
    }

    /** Injects the given insets once; status/nav/IME are set explicitly. */
    private fun dispatchImeInsets(
        rule: ComposeTestRule,
        activity: ComponentActivity,
        statusTop: Int,
        navBottom: Int,
        imeBottom: Int
    ) {
        val insets = injectedInsets(
            measuredInsets(activity),
            statusTop,
            navBottom,
            imeBottom
        )
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

    /**
     * Forces a fixed IME inset ([imeBottom], `0` closes it) on the Compose tree
     * for the rest of the test and applies it immediately.
     *
     * Unlike [dispatchImeInsets] — a single synchronous re-dispatch — this
     * installs an `OnApplyWindowInsetsListener` on the activity content view
     * that re-applies the value on EVERY window-insets dispatch.
     * `performTextInput` focuses the field and the platform opens the real IME
     * asynchronously; its later re-dispatch carries the real (closed or
     * keyboard-sized) inset and would silently overwrite a one-shot injection,
     * leaving the layout at the wrong geometry depending on test order. Re-
     * applying on every dispatch makes the injected geometry independent of
     * those asynchronous re-dispatches.
     *
     * Reading the real inset from the decor view (e.g.
     * [hideRealImeAndWait]) is unaffected: only the content subtree sees the
     * injected value.
     */
    fun forceImeInset(rule: ComposeTestRule, imeBottom: Int) {
        val activity = activityOf(rule)
        val content = activity.findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(content) { _, incoming ->
            val statusTop = incoming.getInsets(WindowInsetsCompat.Type.statusBars()).top
            val navBottom = incoming.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            injectedInsets(incoming, statusTop, navBottom, imeBottom)
        }
        activity.runOnUiThread {
            ViewCompat.dispatchApplyWindowInsets(content, measuredInsets(activity))
        }
        rule.waitForIdle()
    }

    /**
     * Builds window insets carrying the given status/nav/IME values in a shape
     * the Compose tree observes on every API level.
     *
     * On API 30+ the IME is a first-class platform inset type. Below API 30 the
     * platform `android.view.WindowInsets` has no IME type: `WindowInsetsCompat`
     * derives its `Type.ime()` inset from the system-window bottom inset (minus
     * the stable bottom inset), and `WindowInsetsCompat.Builder` folds only the
     * status/nav types into those system-window insets. Setting only
     * `Type.ime()` is therefore silently dropped below API 30 — the injected
     * IME never reaches the tree — and the IME has to be folded into the
     * navigation-bar system-window inset instead (the navigation-bar inset the
     * tree observes stays correct: AndroidX clamps it to the real stable inset
     * of the root window).
     *
     * Below API 30 the builder is seeded with [base], the currently measured
     * insets, and NOT left at its default. The default bases the platform
     * insets on `WindowInsets.CONSUMED`; the resulting instance reports
     * `isConsumed() == true`, and `ViewGroup.dispatchApplyWindowInsets` then
     * stops before descending into its children, so the Compose view never
     * receives the injected insets at all (measured on API 26). Seeding with
     * the real insets keeps the dispatched instance non-consumed, so the normal
     * top-down dispatch reaches the Compose view.
     */
    fun injectedInsets(
        base: WindowInsetsCompat,
        statusTop: Int,
        navBottom: Int,
        imeBottom: Int
    ): WindowInsetsCompat {
        if (Build.VERSION.SDK_INT >= 30) {
            return WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusTop, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, navBottom))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottom))
                .build()
        }
        return WindowInsetsCompat.Builder(base)
            .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, statusTop, 0, 0))
            .setInsets(
                WindowInsetsCompat.Type.navigationBars(),
                Insets.of(0, 0, 0, maxOf(navBottom, imeBottom))
            )
            .build()
    }

    /** The insets the window currently reports, or a zero instance if none. */
    private fun measuredInsets(activity: ComponentActivity): WindowInsetsCompat =
        ViewCompat.getRootWindowInsets(activity.window.decorView)
            ?: WindowInsetsCompat.CONSUMED

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
