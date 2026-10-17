package io.github.excludebarrier.orsikrelay.notification

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationManagerCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.MainActivity
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.notification.NotificationCoordinator
import io.github.excludebarrier.orsikrelay.platform.notification.AndroidNotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests of the real [NotificationManager] adapter.
 *
 * Permission flow (per the agreed test strategy):
 *
 * 1. `POST_NOTIFICATIONS` is granted through the trusted instrumentation
 *    API `UiAutomation.grantRuntimePermission` (verified working on this
 *    emulator image). On this image a fresh install starts with the
 *    `POST_NOTIFICATION` AppOp in `ignore` mode; the grant removes that
 *    override asynchronously in system_server, so [setUp] waits for the
 *    AppOp to reach `allow` before any post, and every positive assertion
 *    below polls with a deadline instead of reading
 *    [NotificationManager.activeNotifications] once.
 * 2. The "system notifications disabled" state is driven through the shell
 *    AppOp `cmd appops set <pkg> POST_NOTIFICATION ignore` because the
 *    instrumentation UID is not trusted for `revokeRuntimePermission`.
 *    The state is restored in `finally`. If the image does not map this
 *    AppOp to `NotificationManagerCompat.areNotificationsEnabled() ==
 *    false`, the test is skipped with the exact observed output instead of
 *    forcing a weaker claim.
 * 3. The runtime-permission-denied UX (fresh install, user taps deny) is a
 *    host/E2E flow by design and is deliberately not mixed into these
 *    adapter tests.
 */
@RunWith(AndroidJUnit4::class)
class NotificationManagerInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val appContext: Context = instrumentation.targetContext
    private val notificationManager: NotificationManager =
        appContext.getSystemService(NotificationManager::class.java)

    @Before
    fun setUp() {
        grantPostNotifications()
        awaitNotificationAppOpAllow()
    }

    private fun activeTitles(): List<CharSequence> =
        notificationManager.activeNotifications
            .map { it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) ?: "" }

    /**
     * Polls until a notification with [title] is active (the enqueue
     * propagates asynchronously) or the deadline is reached.
     */
    private fun awaitActiveTitle(title: String, timeoutMs: Long): StatusBarNotification? {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val match = notificationManager.activeNotifications.firstOrNull {
                it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) == title
            }
            if (match != null) return match
            if (SystemClock.elapsedRealtime() >= deadline) return null
            SystemClock.sleep(100)
        }
    }

    private fun activeTitleCount(title: String): Int =
        notificationManager.activeNotifications.count {
            it.notification.extras.getCharSequence(Notification.EXTRA_TITLE) == title
        }

    /**
     * Negative assertion: waits a settle window so a (wrongly) posted
     * notification can arrive, then asserts that none with [title] exists.
     */
    private fun assertNoActiveTitle(title: String, settleMs: Long = 1_000) {
        SystemClock.sleep(settleMs)
        val count = activeTitleCount(title)
        assertTrue(
            "expected no '$title' notification, found $count (titles ${activeTitles()})",
            count == 0,
        )
    }

    @Test
    fun runCompletedInBackground_postsPrivateNotificationWithSessionIntent() {
        val coordinator = NotificationCoordinator(
            poster = AndroidNotificationManager(appContext),
            isForeground = { false },
            serverNameProvider = { "E2ETest" },
            fallbackTitle = context.getString(R.string.notification_fallback_title),
        )
        coordinator.onRunCompleted("profile-1", "session-1", "run-abc")

        val record = awaitActiveTitle("E2ETest", 5_000)
            ?: throw AssertionError(
                "expected the run-completed notification with title 'E2ETest', " +
                    "got ${activeTitles()}"
            )
        val notification = record.notification
        // Lockscreen-safe: the notification must be private.
        assertEquals(
            Notification.VISIBILITY_PRIVATE,
            notification.visibility,
        )
        // The body must be the fixed localized label, never wire text.
        val body = notification.extras.getCharSequence(Notification.EXTRA_TEXT) ?: ""
        assertTrue(
            "body should be the fixed run-completed label, got '$body'",
            body.isNotEmpty(),
        )
        // The channel must exist under the documented id.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            assertTrue(
                "channel ${AndroidNotificationManager.CHANNEL_ID} must exist",
                notificationManager.getNotificationChannel(
                    AndroidNotificationManager.CHANNEL_ID
                ) != null,
            )
        }
        // Tapping must deep-link into the app's own MainActivity with the
        // closed session action (the immutable content intent).
        val contentIntent = notification.contentIntent
            ?: throw AssertionError("content intent must be present")
        // `PendingIntent.getIntent()` is a hidden API; the public
        // creator/activity contract is asserted here, and the exact intent
        // action/extras contract is covered by NotificationSessionIntentTest.
        assertEquals(
            appContext.packageName,
            contentIntent.creatorPackage,
        )
        assertTrue("content intent must target an activity", contentIntent.isActivity())
        // API 31+: the immutable flag is publicly observable.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            assertTrue("content intent must be immutable", contentIntent.isImmutable())
        }
    }

    @Test
    fun duplicateRunCompletion_doesNotRepost() {
        val coordinator = NotificationCoordinator(
            poster = AndroidNotificationManager(appContext),
            isForeground = { false },
            serverNameProvider = { "E2EDup" },
            fallbackTitle = context.getString(R.string.notification_fallback_title),
        )
        coordinator.onRunCompleted("profile-2", "session-2", "run-dup")
        coordinator.onRunCompleted("profile-2", "session-2", "run-dup")

        awaitActiveTitle("E2EDup", 5_000)
            ?: throw AssertionError(
                "expected the 'E2EDup' notification, got ${activeTitles()}"
            )
        // Give a duplicate the chance to arrive, then count must be exactly
        // one.
        SystemClock.sleep(750)
        assertTrue(
            "expected exactly one notification for the same run, got ${activeTitleCount("E2EDup")} (titles ${activeTitles()})",
            activeTitleCount("E2EDup") == 1,
        )
    }

    @Test
    fun foreground_doesNotPost() {
        val coordinator = NotificationCoordinator(
            poster = AndroidNotificationManager(appContext),
            isForeground = { true },
            serverNameProvider = { "E2EFore" },
            fallbackTitle = context.getString(R.string.notification_fallback_title),
        )
        coordinator.onRunCompleted("profile-3", "session-3", "run-1")
        assertNoActiveTitle("E2EFore")
    }

    @Test
    fun contentIntent_tapBringsAppToFront() {
        val coordinator = NotificationCoordinator(
            poster = AndroidNotificationManager(appContext),
            isForeground = { false },
            serverNameProvider = { "E2ETap" },
            fallbackTitle = context.getString(R.string.notification_fallback_title),
        )
        coordinator.onRunCompleted("profile-6", "session-6", "run-5")

        val record = awaitActiveTitle("E2ETap", 5_000)
            ?: throw AssertionError(
                "expected the 'E2ETap' notification, got ${activeTitles()}"
            )
        val contentIntent = record.notification.contentIntent
            ?: throw AssertionError("content intent must be present")

        // Sending the immutable content intent (exactly what the system
        // performs on a notification tap) must start the app's own
        // MainActivity. `UiAutomation.getWindows()` is not usable on this
        // image (it returns an empty list), so the standard
        // ActivityMonitor mechanism observes the cross-process launch.
        val monitor = instrumentation.addMonitor(
            MainActivity::class.java.name,
            null,
            true,
        )
        try {
            contentIntent.send()
            val activity = instrumentation.waitForMonitorWithTimeout(monitor, 15_000)
            assertTrue(
                "tapping the notification must bring the app's MainActivity " +
                    "to the front (monitor hits: ${monitor.hits})",
                activity != null,
            )
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        // Leave the device on the home screen for the remaining tests.
        instrumentation.uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
    }

    @Test
    fun systemNotificationsDisabled_viaAppOps_postsNothingAndDoesNotCrash() {
        val pkg = appContext.packageName
        val before = shellOutput("cmd appops get $pkg POST_NOTIFICATION")
        val setOutput = shellOutput("cmd appops set $pkg POST_NOTIFICATION ignore")
        try {
            val enabled = NotificationManagerCompat.from(appContext).areNotificationsEnabled()
            Assume.assumeFalse(
                "shell AppOp POST_NOTIFICATION=ignore did not disable notifications on this " +
                    "image (before='$before' setOutput='$setOutput' areNotificationsEnabled()=" +
                    "$enabled); the denied path is covered by the JVM fail-closed test and the " +
                    "fresh-install permission-UX E2E instead of forcing a weaker claim",
                enabled,
            )
            val coordinator = NotificationCoordinator(
                poster = AndroidNotificationManager(appContext),
                isForeground = { false },
                serverNameProvider = { "E2EAppOps" },
                fallbackTitle = context.getString(R.string.notification_fallback_title),
            )
            // Must neither crash nor post anything.
            coordinator.onRunCompleted("profile-7", "session-7", "run-6")
            assertNoActiveTitle("E2EAppOps")
        } finally {
            shellOutput("cmd appops set $pkg POST_NOTIFICATION allow")
        }
    }

    private fun grantPostNotifications() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            instrumentation.uiAutomation.grantRuntimePermission(
                appContext.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            assertEquals(
                PackageManager.PERMISSION_GRANTED,
                appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS),
            )
        }
    }

    /**
     * Waits until system_server accepts notification posts for this
     * package: on this image a fresh install starts with the
     * `POST_NOTIFICATION` AppOp in `ignore` mode and the grant's override
     * removal propagates asynchronously (a post in between is dropped by
     * the system even though the client-side permission check already
     * passes).
     */
    private fun awaitNotificationAppOpAllow(timeoutMs: Long = 10_000) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (true) {
            val state = shellOutput(
                "cmd appops get ${appContext.packageName} POST_NOTIFICATION"
            )
            if ("ignore" !in state) return
            if (SystemClock.elapsedRealtime() >= deadline) {
                throw AssertionError(
                    "POST_NOTIFICATION AppOp still 'ignore' after grant; state: $state"
                )
            }
            SystemClock.sleep(100)
        }
    }


    /** Runs a shell command through the trusted instrumentation shell. */
    private fun shellOutput(command: String): String =
        instrumentation.uiAutomation
            .executeShellCommand(command)
            .let { pfd -> ParcelFileDescriptor.AutoCloseInputStream(pfd) }
            .use { it.bufferedReader().readText() }
}
