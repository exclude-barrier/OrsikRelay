package io.github.excludebarrier.orsikrelay
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.VisibleForTesting
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.activity.compose.LocalActivity
import androidx.core.view.WindowCompat
import io.github.excludebarrier.orsikrelay.data.theme.SharedPreferencesThemeSettingStore
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.navigation.HermesNavHost
import io.github.excludebarrier.orsikrelay.navigation.initialDestination
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import io.github.excludebarrier.orsikrelay.ui.theme.rememberThemeModeController
import io.github.excludebarrier.orsikrelay.domain.device.PairingBootstrap
import io.github.excludebarrier.orsikrelay.domain.device.PairingDeepLink
import io.github.excludebarrier.orsikrelay.platform.notification.AppForegroundTracker
import io.github.excludebarrier.orsikrelay.platform.notification.NotificationSessionIntent

/**
 * Single entry point of the app. Hosts the Compose navigation shell;
 * all navigation logic lives in [HermesNavHost] and [io.github.excludebarrier.orsikrelay.navigation.AppNavigationState].
 */
class MainActivity : ComponentActivity() {
    // Product Gate: a pairing deep link (orsik://pair?server=...&code=...)
    // received via onCreate or onNewIntent is parsed once and handed to the
    // onboarding screen as a pre-fill, so the user never types a base URL or
    // API key. Snapshot state so a re-delivered link recomposes the route.
    private val pairingBootstrap = mutableStateOf<PairingBootstrap?>(null)

    // A notification tap (cold start or warm start) is parsed once into a
    // validated (profileId, sessionId) and handed to the navigation host,
    // which opens exactly that chat. Malformed intents yield null (fail-closed)
    // and leave the normal startup navigation untouched.
    private val notificationBootstrap = mutableStateOf<Pair<String, String>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Consume the launch intent exactly once. On a configuration-change
        // recreation the framework re-delivers the SAME intent, so re-parsing
        // it here would replay a notification tap (the user is thrown back
        // into the chat on every rotation) or overwrite edited onboarding
        // fields with the deep-link prefill. A cold start has a null
        // savedInstanceState and still consumes the intent.
        if (savedInstanceState == null) {
            pairingBootstrap.value = PairingDeepLink.parse(intent?.data?.toString().orEmpty())
            notificationBootstrap.value = NotificationSessionIntent.parse(intent)
        }
        setContent {
            val themeStore = remember {
                SharedPreferencesThemeSettingStore(applicationContext)
            }
            val themeController = rememberThemeModeController(themeStore)
            val darkTheme = themeController.mode.resolve(isSystemInDarkTheme())
            SystemBarAppearance(darkTheme)
            HermesTheme(darkTheme = darkTheme) {
                HermesAppSurface {
                    HermesNavHost(
                        startDestination = initialDestination(),
                        sessionPageSource = sessionPageSourceForTests,
                        themeController = themeController,
                        pairingBootstrap = pairingBootstrap.value,
                        notificationBootstrap = notificationBootstrap.value
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppForegroundTracker.activityStarted()
    }

    override fun onStop() {
        super.onStop()
        AppForegroundTracker.activityStopped()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Keep getIntent() in sync with the latest delivery so a later
        // recreation does not see a stale intent (singleTop delivery).
        setIntent(intent)
        pairingBootstrap.value = PairingDeepLink.parse(intent.data?.toString().orEmpty())
        notificationBootstrap.value = NotificationSessionIntent.parse(intent)
    }

    companion object {
        /**
         * Test-only seam for instrumented tests that drive the real
         * [MainActivity] without a network (e.g. an [androidx.test.core.app.ActivityScenario]
         * recreation test). When non-null, [HermesNavHost] serves the session
         * list from this synthetic source instead of the production
         * [io.github.excludebarrier.orsikrelay.data.session.SessionListRepository]; when
         * `null` (the default, always in production) the production
         * repository is used and behavior is unchanged.
         */
        @VisibleForTesting
        @Volatile
        internal var sessionPageSourceForTests: SessionPageSource? = null
    }
}

/**
 * Keeps app controls outside status bars, display cutouts and gesture
 * navigation while the activity continues to draw edge-to-edge.
 * WindowInsets.safeDrawing is calculated by Android for the current
 * device, orientation and system-bar configuration.
 */
@Composable
internal fun HermesAppSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding()
        ) {
            content()
        }
    }
}

/**
 * The icon-appearance contract for the system bars.
 *
 * `isAppearanceLight*Bars = true` means the bar is light and the icons
 * must be the DARK variant; `false` means the bar is dark and the icons
 * are LIGHT. For a resolved app theme the bars are light exactly when the
 * theme is light, so both flags are simply the negation of [darkTheme].
 *
 * [enableEdgeToEdge] alone would derive this from the SYSTEM setting,
 * which is wrong once the user forces Light on a dark system (or Dark on
 * a light one). Extracting the mapping as a pure function makes the
 * blocker — "bars must follow the RESOLVED theme" — directly unit-testable
 * without an Activity or a window.
 */
internal fun resolvedThemeBarAppearance(darkTheme: Boolean): Pair<Boolean, Boolean> {
    val light = !darkTheme
    return light to light
}

/**
 * Aligns the status- and navigation-bar icon appearance with the
 * RESOLVED app theme rather than the system setting. Re-applied whenever
 * [darkTheme] flips so a forced theme never leaves illegible icons.
 */
@Composable
internal fun SystemBarAppearance(darkTheme: Boolean) {
    // LocalActivity resolves the hosting ComponentActivity without a raw
    // context cast (which lint's ContextCastToActivity rejects).
    val activity = LocalActivity.current
    LaunchedEffect(darkTheme, activity) {
        if (activity == null || activity.isFinishing) return@LaunchedEffect
        val (statusLight, navLight) = resolvedThemeBarAppearance(darkTheme)
        val controller = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        controller.isAppearanceLightStatusBars = statusLight
        controller.isAppearanceLightNavigationBars = navLight
    }
}
