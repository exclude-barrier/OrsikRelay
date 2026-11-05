package io.github.excludebarrier.orsikrelay.ui.profiles

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionAdmin
import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionMode
import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionRoute
import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionSettings
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.ui.theme.HermesTheme
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionScreenUiTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private val pinned = "0123456789abcdef0123456789abcdef"
    private val saved = CopyOnWriteArrayList<Pair<String, ConnectionSettings>>()
    private var closed = false

    private val admin = object : ConnectionAdmin {
        override val routes: StateFlow<Map<String, ConnectionRoute>> = MutableStateFlow(emptyMap())
        override fun save(profileId: String, settings: ConnectionSettings) {
            saved += profileId to settings
        }
    }

    private val paired = ServerProfile.create("p1", "Server", "https://server.tailnet.example.invalid:8443")

    private fun show(profile: ServerProfile) {
        composeRule.setContent {
            HermesTheme {
                ConnectionScreen(profile = profile, admin = admin, onDismiss = { closed = true }, onSaved = { closed = true })
            }
        }
    }

    private fun text(id: Int) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id)

    private fun node(tag: String) = composeRule.onNodeWithTag(tag, useUnmergedTree = true)

    @Test
    fun withoutAPublicAddressTheModesAreInactiveAndNothingIsToSave() {
        show(paired)
        node("connectionPrivatePill").assertTextEquals(text(R.string.connection_pill_active))
        node("connectionMode:AUTOMATIC").performScrollTo().performClick()
        node("connectionSave").assertIsNotEnabled()
        assertTrue(saved.isEmpty())
    }

    @Test
    fun anInvalidOrDuplicatePublicAddressIsRefused() {
        show(paired)
        node("connectionAddPublic").performClick()
        node("connectionPublicUrl").performTextInput("http://relay.example.invalid")
        node("connectionSave").assertIsEnabled().performClick()
        node("connectionError").performScrollTo().assertIsDisplayed()
        node("connectionPublicUrl").performTextReplacement(paired.baseUrl)
        node("connectionSave").performClick()
        node("connectionError").performScrollTo().assertIsDisplayed()
        assertTrue(saved.isEmpty())
    }

    @Test
    fun aPublicAddressIsSavedWithTheModeAndKeepsThePinnedIdentity() {
        show(paired.withConnection(ConnectionSettings.create(ConnectionMode.PRIVATE_ONLY, null, null, pinned)))
        node("connectionAddPublic").performClick()
        node("connectionPublicUrl").performTextInput("https://relay.example.invalid")
        node("connectionMode:PUBLIC_ONLY").performScrollTo().performClick()
        node("connectionPrivatePill").performScrollTo().assertTextEquals(text(R.string.connection_pill_off))
        node("connectionCertWarning").performScrollTo().assertIsDisplayed()
        node("connectionSave").performClick()
        composeRule.waitUntil(5_000) { saved.isNotEmpty() && closed }
        val (id, settings) = saved.single()
        assertEquals("p1", id)
        assertEquals(ConnectionMode.PUBLIC_ONLY, settings.mode)
        assertEquals("https://relay.example.invalid", settings.publicBaseUrl)
        assertEquals(pinned, settings.serverId)
    }

    @Test
    fun removingThePublicAddressFallsBackToVpnOnly() {
        show(
            paired.withConnection(
                ConnectionSettings.create(ConnectionMode.AUTOMATIC, "https://relay.example.invalid", "Orsik Relay", pinned)
            )
        )
        node("connectionPrivatePill").assertTextEquals(text(R.string.connection_pill_preferred))
        node("connectionPublicPill").assertTextEquals(text(R.string.connection_pill_fallback))
        node("connectionRemovePublic").performClick()
        node("connectionAddPublic").assertIsDisplayed()
        node("connectionSave").performClick()
        composeRule.waitUntil(5_000) { saved.isNotEmpty() }
        val settings = saved.single().second
        assertEquals(ConnectionMode.PRIVATE_ONLY, settings.mode)
        assertEquals(null, settings.publicBaseUrl)
        assertEquals(pinned, settings.serverId)
    }
}
