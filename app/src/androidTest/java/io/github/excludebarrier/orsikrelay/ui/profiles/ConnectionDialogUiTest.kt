package io.github.excludebarrier.orsikrelay.ui.profiles

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class ConnectionDialogUiTest {

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

    private fun show(profile: ServerProfile) {
        composeRule.setContent {
            HermesTheme {
                ConnectionDialog(profile = profile, admin = admin, onDismiss = { closed = true }, onSaved = { closed = true })
            }
        }
    }

    private val paired = ServerProfile.create("p1", "Server", "https://server.tailnet.example.invalid:8443")

    @Test
    fun anInvalidOrDuplicatePublicAddressIsRefused() {
        show(paired)
        composeRule.onNodeWithTag("connectionPublicUrl").performTextInput("http://relay.example.invalid")
        composeRule.onNodeWithTag("connectionSave").performClick()
        composeRule.onNodeWithTag("connectionError").assertIsDisplayed()
        composeRule.onNodeWithTag("connectionPublicUrl").performTextReplacement(paired.baseUrl)
        composeRule.onNodeWithTag("connectionSave").performClick()
        composeRule.onNodeWithTag("connectionError").assertIsDisplayed()
        assertTrue(saved.isEmpty())
    }

    @Test
    fun aPublicAddressIsSavedWithTheModeAndKeepsThePinnedIdentity() {
        val profile = paired.withConnection(
            ConnectionSettings.create(ConnectionMode.PRIVATE_ONLY, null, null, pinned)
        )
        show(profile)
        composeRule.onNodeWithTag("connectionPublicUrl").performTextInput("https://relay.example.invalid")
        composeRule.onNodeWithTag("connectionMode:PUBLIC_ONLY").performClick()
        composeRule.onNodeWithTag("connectionSave").performClick()
        composeRule.waitUntil(5_000) { saved.isNotEmpty() && closed }
        val (id, settings) = saved.single()
        assertEquals("p1", id)
        assertEquals(ConnectionMode.PUBLIC_ONLY, settings.mode)
        assertEquals("https://relay.example.invalid", settings.publicBaseUrl)
        assertEquals(pinned, settings.serverId)
    }

    @Test
    fun anEmptyAddressFallsBackToVpnOnly() {
        show(paired)
        composeRule.onNodeWithTag("connectionMode:AUTOMATIC").performClick()
        composeRule.onNodeWithTag("connectionSave").performClick()
        composeRule.waitUntil(5_000) { saved.isNotEmpty() }
        assertEquals(ConnectionSettings.PRIVATE_ONLY, saved.single().second)
    }
}
