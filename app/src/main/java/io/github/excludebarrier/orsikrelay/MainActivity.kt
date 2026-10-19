package io.github.excludebarrier.orsikrelay
import android.content.Context
import android.content.Intent
import android.os.Bundle
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
import androidx.fragment.app.FragmentActivity
import io.github.excludebarrier.orsikrelay.data.settings.AppLockSettingStore
import io.github.excludebarrier.orsikrelay.data.settings.SharedPreferencesMaterialYouSettingStore
import io.github.excludebarrier.orsikrelay.data.settings.SharedPreferencesScreenProtectionSettingStore
import io.github.excludebarrier.orsikrelay.data.theme.SharedPreferencesThemeSettingStore
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.navigation.HermesNavHost
import io.github.excludebarrier.orsikrelay.navigation.initialDestination
import io.github.excludebarrier.orsikrelay.security.applock.AndroidAppAuthenticator
import io.github.excludebarrier.orsikrelay.security.applock.AppAuthenticator
import io.github.excludebarrier.orsikrelay.security.applock.AppLockController
import io.github.excludebarrier.orsikrelay.security.applock.AppLockHolder
import io.github.excludebarrier.orsikrelay.ui.crash.CrashReportPrompt
import io.github.excludebarrier.orsikrelay.ui.lock.AppLockGate
import io.github.excludebarrier.orsikrelay.ui.settings.ApplyScreenProtection
import io.github.excludebarrier.orsikrelay.ui.settings.rememberMaterialYouController
import io.github.excludebarrier.orsikrelay.ui.settings.rememberScreenProtectionController
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import io.github.excludebarrier.orsikrelay.ui.theme.rememberThemeModeController
import io.github.excludebarrier.orsikrelay.domain.device.PairingBootstrap
import io.github.excludebarrier.orsikrelay.domain.device.PairingDeepLink
import io.github.excludebarrier.orsikrelay.platform.notification.AppForegroundTracker
import io.github.excludebarrier.orsikrelay.platform.notification.NotificationSessionIntent
import io.github.excludebarrier.orsikrelay.platform.locale.AppLocale

/**
 * Single entry point of the app. Hosts the Compose navigation shell;
 * all navigation logic lives in [HermesNavHost] and [io.github.excludebarrier.orsikrelay.navigation.AppNavigationState].
 *
 * A [FragmentActivity] because the app lock hosts [androidx.biometric.BiometricPrompt].
 */
class MainActivity : FragmentActivity() {
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

    /**
     * Process-scoped app-lock state. Created before the first composition so
     * the cold-start lock decision is in place, and reused across
     * configuration changes through [AppLockHolder].
     */
    private lateinit var appLockController: AppLockController

    /**
     * Applies the persisted app language before the first frame on API < 33.
     * On API 33+ the framework applies the app locale from LocaleManager
     * before the activity is created, so the base context stays untouched.
     */
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        appLockController = AppLockHolder.get(applicationContext, appLockStoreForTests)
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
            val screenProtectionStore = remember {
                SharedPreferencesScreenProtectionSettingStore(applicationContext)
            }
            val screenProtectionController =
                rememberScreenProtectionController(screenProtectionStore)
            ApplyScreenProtection(screenProtectionController)
            val materialYouStore = remember {
                SharedPreferencesMaterialYouSettingStore(applicationContext)
            }
            val materialYouController = rememberMaterialYouController(materialYouStore)
            val darkTheme = themeController.mode.resolve(isSystemInDarkTheme())
            SystemBarAppearance(darkTheme)
            val activity = LocalActivity.current
            // BiometricPrompt needs the hosting FragmentActivity; tests inject
            // an AppAuthenticator so the system prompt is never shown.
            val appAuthenticator = remember(activity) {
                appAuthenticatorForTests
                    ?: (activity as? FragmentActivity)?.let(::AndroidAppAuthenticator)
            }
            HermesTheme(
                darkTheme = darkTheme,
                dynamicColor = materialYouController.enabled
            ) {
                HermesAppSurface {
                    AppLockGate(
                        controller = appLockController,
                        appAuthenticator = appAuthenticator
                    ) {
                        HermesNavHost(
                            startDestination = initialDestination(),
                            sessionPageSource = sessionPageSourceForTests,
                            themeController = themeController,
                            screenProtectionController = screenProtectionController,
                            materialYouController = materialYouController,
                            appLockController = appLockController,
                            appAuthenticator = appAuthenticator,
                            pairingBootstrap = pairingBootstrap.value,
                            notificationBootstrap = notificationBootstrap.value
                        )
                        CrashReportPrompt()
                    }
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        AppForegroundTracker.activityStarted()
        appLockController.onActivityStarted()
    }

    override fun onStop() {
        super.onStop()
        appLockController.onActivityStopped()
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

        /**
         * Test-only seam for the app lock: a fake store and authenticator keep
         * the real preferences and the system prompt out of instrumentation
         * tests. `null` (always in production) uses the persisted store and
         * [AndroidAppAuthenticator].
         */
        @VisibleForTesting
        @Volatile
        internal var appLockStoreForTests: AppLockSettingStore? = null

        @VisibleForTesting
        @Volatile
        internal var appAuthenticatorForTests: AppAuthenticator? = null

        /** Clears every app-lock test seam and the process-scoped controller. */
        @VisibleForTesting
        internal fun resetAppLockForTests() {
            appLockStoreForTests = null
            appAuthenticatorForTests = null
            AppLockHolder.resetForTest()
        }
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
