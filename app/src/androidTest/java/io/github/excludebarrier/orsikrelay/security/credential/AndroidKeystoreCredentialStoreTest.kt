package io.github.excludebarrier.orsikrelay.security.credential

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import java.security.KeyStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidKeystoreCredentialStoreTest {

    private lateinit var context: Context
    private lateinit var preferencesName: String
    private lateinit var aliasNamespace: String
    private lateinit var store: AndroidKeystoreCredentialStore
    private val touchedProfiles = linkedSetOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val testId = UUID.randomUUID().toString()
        preferencesName = "hermes_credential_test_$testId"
        aliasNamespace = "io.github.excludebarrier.orsikrelay.test.$testId"
        store = AndroidKeystoreCredentialStore(
            context = context,
            preferencesName = preferencesName,
            aliasNamespace = aliasNamespace
        )
    }

    @After
    fun tearDown() {
        touchedProfiles.forEach { profileId ->
            runCatching { store.delete(profileId) }
        }
        context.deleteSharedPreferences(preferencesName)
    }

    @Test
    fun saveAndLoadReturnsTheSyntheticCredential() {
        val profileId = profile("load")

        store.put(profileId, SecretValue(SYNTHETIC_ALPHA))

        val loaded = store.get(profileId)
        assertNotNull(loaded)
        assertTrue(loaded!!.useRaw { it == SYNTHETIC_ALPHA })
        assertEquals("[REDACTED]", loaded.toString())
    }

    @Test
    fun preferencesNeverContainThePlaintextCredential() {
        val profileId = profile("ciphertext")

        store.put(profileId, SecretValue(SYNTHETIC_ALPHA))

        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        assertTrue(preferences.contains(store.preferenceKeyFor(profileId)))
        assertFalse(
            preferences.all.values.any { persisted ->
                persisted.toString().contains(SYNTHETIC_ALPHA)
            }
        )
    }

    @Test
    fun savingAgainReplacesThePreviousCredential() {
        val profileId = profile("replace")
        store.put(profileId, SecretValue(SYNTHETIC_ALPHA))

        store.put(profileId, SecretValue(SYNTHETIC_BETA))

        val loaded = store.get(profileId)
        assertNotNull(loaded)
        assertTrue(loaded!!.useRaw { it == SYNTHETIC_BETA })
        assertFalse(loaded.useRaw { it == SYNTHETIC_ALPHA })
    }

    @Test
    fun deleteRemovesCiphertextAndKeystoreAliasAndIsIdempotent() {
        val profileId = profile("delete")
        val alias = store.aliasFor(profileId)
        val preferenceKey = store.preferenceKeyFor(profileId)
        val keyStore = androidKeyStore()
        store.put(profileId, SecretValue(SYNTHETIC_ALPHA))
        assertTrue(keyStore.containsAlias(alias))

        store.delete(profileId)
        store.delete(profileId)

        val preferences = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
        assertFalse(preferences.contains(preferenceKey))
        assertFalse(androidKeyStore().containsAlias(alias))
        assertNull(store.get(profileId))
    }

    @Test
    fun unknownProfileReturnsNull() {
        val profileId = profile("unknown")

        assertNull(store.get(profileId))
    }

    @Test
    fun unknownPayloadVersionFailsGenerically() {
        val profileId = profile("version")
        val payload = "v999:AA:AA"
        writePayload(profileId, payload)

        assertCredentialFailure(
            forbidden = listOf(profileId, payload, SYNTHETIC_ALPHA)
        ) {
            store.get(profileId)
        }
    }

    @Test
    fun malformedBase64FailsGenerically() {
        val profileId = profile("base64")
        val payload = "v1:%%%:%%%"
        writePayload(profileId, payload)

        assertCredentialFailure(
            forbidden = listOf(profileId, payload, SYNTHETIC_ALPHA)
        ) {
            store.get(profileId)
        }
    }

    @Test
    fun blankProfileIdIsRejectedGenericallyForEveryOperation() {
        val blankProfileId = "   "

        assertCredentialFailure(forbidden = listOf(blankProfileId, SYNTHETIC_ALPHA)) {
            store.put(blankProfileId, SecretValue(SYNTHETIC_ALPHA))
        }
        assertCredentialFailure(forbidden = listOf(blankProfileId)) {
            store.get(blankProfileId)
        }
        assertCredentialFailure(forbidden = listOf(blankProfileId)) {
            store.delete(blankProfileId)
        }
    }

    @Test
    fun derivedKeysAreDeterministicDistinctAndDoNotExposeTheProfileId() {
        val profileId = profile("derivation")

        val firstAlias = store.aliasFor(profileId)
        val secondAlias = store.aliasFor(profileId)
        val preferenceKey = store.preferenceKeyFor(profileId)

        assertEquals(firstAlias, secondAlias)
        assertNotEquals(firstAlias, preferenceKey)
        assertFalse(firstAlias.contains(profileId))
        assertFalse(preferenceKey.contains(profileId))
    }

    private fun profile(suffix: String): String = "fixture-profile-$suffix".also {
        touchedProfiles += it
    }

    private fun writePayload(profileId: String, payload: String) {
        val committed = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(store.preferenceKeyFor(profileId), payload)
            .commit()
        assertTrue(committed)
    }

    private fun assertCredentialFailure(
        forbidden: List<String>,
        action: () -> Unit
    ) {
        val thrown = try {
            action()
            null
        } catch (error: CredentialStoreException) {
            error
        } catch (_: Throwable) {
            fail("Expected CredentialStoreException")
            null
        }

        assertNotNull(thrown)
        assertEquals(CredentialStoreException.MESSAGE, thrown!!.message)
        forbidden.filter { it.isNotEmpty() }.forEach { value ->
            assertFalse(thrown.toString().contains(value))
        }
    }

    private fun androidKeyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply {
        load(null)
    }

    private companion object {
        const val SYNTHETIC_ALPHA = "fixture-credential-alpha"
        const val SYNTHETIC_BETA = "fixture-credential-beta"
    }
}
