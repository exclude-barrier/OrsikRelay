package io.github.excludebarrier.orsikrelay

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.data.device.SharedPreferencesDeviceCredentialMetadataStore
import io.github.excludebarrier.orsikrelay.di.AppDependenciesHolder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OR-07 real Android end-to-end: the WHOLE onboarding UI path against a live
 * Orsik Mobile Bridge (which fronts the unmodified official Hermes core) over
 * HTTPS, on the emulator, through the production [MainActivity], the real
 * Compose screens and the production `AppDependencies` graph.
 *
 * Unlike the transport-only [LiveBridgeE2ETest], this test never calls the
 * networking clients directly: it delivers the pairing deep link
 * (`orsik://pair?server=…&code=…`) to the shipped activity, lets the real
 * onboarding screen pre-fill and confirm the pairing, and then asserts the
 * device credential, the persisted capability result and the freshly
 * activated sessions view through the production stores.
 *
 * Requires instrumentation arguments:
 *   liveBaseUrl      e.g. https://<bridge-host>.<tailnet>.ts.net:8443
 *   livePairingCode  a fresh 24-character single-use code
 *
 * The synthetic-free arguments are supplied only at run time and are never
 * written to any file, log or assertion message (the code is a single-use
 * secret and the device key is never read in cleartext).
 *
 * Run (explicitly, against the live stack):
 *   ./gradlew :app:connectedDebugAndroidTest \
 *     -Pandroid.testInstrumentationRunnerArguments.class=io.github.excludebarrier.orsikrelay.LiveBridgeOnboardingE2ETest \
 *     -Pandroid.testInstrumentationRunnerArguments.liveBaseUrl=... \
 *     -Pandroid.testInstrumentationRunnerArguments.livePairingCode=...
 */
@LiveBridge
@RunWith(AndroidJUnit4::class)
class LiveBridgeOnboardingE2ETest {

    // createEmptyComposeRule (not createAndroidComposeRule): the activity must
    // be launched by the test itself so the pairing deep link can be delivered
    // as an ACTION_VIEW intent.
    @get:Rule
    val composeRule = createEmptyComposeRule()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private var scenario: ActivityScenario<MainActivity>? = null
    private var createdProfileId: String? = null

    @Before
    fun setUp() {
        // Same process-scoped graph isolation as AppDependenciesHolderTest: the
        // production graph is rebuilt from the persistent stores, never leaked
        // across (or into) other tests in the same instrumentation process.
        AppDependenciesHolder.resetForTest()
    }

    @After
    fun tearDown() {
        // Clean up the profile this run created through the production delete
        // path (credential, metadata, device metadata and capability).
        createdProfileId?.let { profileId ->
            runCatching { productionDependencies().profileAdmin.deleteProfile(profileId) }
        }
        createdProfileId = null
        scenario?.close()
        scenario = null
        AppDependenciesHolder.resetForTest()
    }

    @Test
    fun deepLinkPairingOnboardsThroughTheRealUiAndNegotiatesCapabilities() {
        val baseUrl = arg("liveBaseUrl")
        val pairingCode = arg("livePairingCode")
        assumeTrue(
            "liveBaseUrl/livePairingCode not provided",
            baseUrl != null && pairingCode != null
        )
        val server = baseUrl!!
        val code = pairingCode!!

        // Deterministic start: no stored server profile (the established reset
        // path of the restart tests). If a profile survives the reset the
        // tenant is not clean, so the assumption fails closed with a clear
        // message instead of pairing onto a dirty state.
        resetStoredProfiles()
        val remaining = productionDependencies().profileAdmin.listProfiles()
        assumeTrue(
            "expected no stored server profiles before the live onboarding run, " +
                "found ${remaining.size}",
            remaining.isEmpty()
        )

        // Launch the shipped entry point with the pairing deep link.
        scenario = ActivityScenario.launch(deepLinkIntent(server, code))

        // 1. The real onboarding screen, pre-filled from the deep link. The
        // bootstrap writes the server URL and the code into the form fields,
        // so the pairing form is submittable without any typing.
        waitForTag(TAG_SUBMIT)
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            editableTexts().any { it == server }
        }
        // The pairing code from the deep link lands in the password-protected
        // field (never rendered as plain text).
        val codeField = composeRule
            .onNodeWithContentDescription(pairingCodeLabel(), useUnmergedTree = true)
            .fetchSemanticsNode()
        assertTrue(
            "the pairing code field has password semantics",
            codeField.config.contains(SemanticsProperties.Password)
        )

        // 2. The confirmation step of the pairing form: its production tag and
        // its production label are present, then the user confirms.
        composeRule.onNodeWithTag(TAG_SUBMIT).assertIsDisplayed()
        composeRule.onNodeWithText(submitLabel()).assertIsDisplayed()
        composeRule.onNodeWithTag(TAG_SUBMIT).performClick()

        // 3. Wait (bounded, no sleeps) for the new profile's sessions view,
        // identified by its production title.
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            composeRule
                .onAllNodesWithText(sessionsTitle())
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.waitForIdle()

        // 4. Exactly one profile carries the paired base URL.
        val matched = productionDependencies().profileAdmin
            .listProfiles()
            .filter { it.baseUrl == server }
        assertEquals(
            "exactly one profile must exist for the paired base URL",
            1,
            matched.size
        )
        val profileId = matched.single().id
        createdProfileId = profileId

        // 5. The device credential metadata is persisted (non-secret; the
        // device key itself is never read, logged or asserted here).
        val deviceMetadata = SharedPreferencesDeviceCredentialMetadataStore(context)
            .get(profileId)
        assertNotNull(
            "the paired device credential metadata must be stored",
            deviceMetadata
        )
        assertTrue(
            "the issued device permissions must cover approvals/chat/runs",
            deviceMetadata!!.permissions.containsAll(listOf("approvals", "chat", "runs"))
        )

        // 6. The capability negotiation is positive: the stored, onboarding-
        // confirmed result must be true.
        assertTrue(
            "interactive-approvals capability must be negotiated and stored",
            productionDependencies().capabilityStore.read(profileId)
        )
    }

    // -----------------------------------------------------------------
    // Helpers.
    // -----------------------------------------------------------------

    private fun arg(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.takeIf { it.isNotBlank() }

    private fun productionDependencies() = AppDependenciesHolder.get(context)

    private fun resetStoredProfiles() {
        val admin = productionDependencies().profileAdmin
        admin.listProfiles().forEach { admin.deleteProfile(it.id) }
    }

    private fun deepLinkIntent(baseUrl: String, code: String): Intent {
        val uri = Uri.parse(
            "orsik://pair?server=" + Uri.encode(baseUrl) + "&code=" + Uri.encode(code)
        )
        return Intent(Intent.ACTION_VIEW, uri)
            .setClassName(context, MainActivity::class.java.name)
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.waitForIdle()
    }

    // The current value of every editable field text (incl. password fields),
    // read from the semantics tree instead of a specific tag so the assertion
    // does not depend on where a field's tag sits.
    private fun editableTexts(): List<String> = composeRule
        .onAllNodes(hasSetTextAction())
        .fetchSemanticsNodes()
        .mapNotNull { node ->
            if (node.config.contains(SemanticsProperties.EditableText)) {
                node.config[SemanticsProperties.EditableText].text
            } else {
                null
            }
        }

    // Production texts resolved from the app's own string resources.
    private fun submitLabel(): String = context.getString(R.string.onboarding_submit)
    private fun sessionsTitle(): String = context.getString(R.string.sessions_title)
    private fun pairingCodeLabel(): String =
        context.getString(R.string.onboarding_pairing_code_label)

    private companion object {
        const val TIMEOUT_MILLIS = 30_000L
        const val TAG_SUBMIT = "onboardingSubmitButton"
    }
}
