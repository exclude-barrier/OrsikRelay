package io.github.excludebarrier.orsikrelay.data.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.security.credential.CredentialStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SharedPreferencesServerProfileMetadataStoreTest {

    private lateinit var context: Context
    private lateinit var preferencesName: String
    private lateinit var store: SharedPreferencesServerProfileMetadataStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferencesName = "hermes_profile_test_${UUID.randomUUID()}"
        store = SharedPreferencesServerProfileMetadataStore(context, preferencesName)
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(preferencesName)
    }

    @Test
    fun profileSurvivesARecreatedStoreInstance() {
        val expected = profile(FIRST_ID, "First")
        store.save(expected)

        val recreated = SharedPreferencesServerProfileMetadataStore(context, preferencesName)

        assertEquals(expected, recreated.get(FIRST_ID))
        assertEquals(listOf(expected), recreated.list())
    }

    @Test
    fun listOrderIsDeterministicById() {
        val second = profile("profile-b", "Second")
        val first = profile("profile-a", "First")
        store.save(second)
        store.save(first)

        assertEquals(listOf(first, second), store.list())
        assertEquals(listOf(first, second), store.list())
    }

    @Test
    fun savingExistingIdReplacesMetadataWithoutDuplicate() {
        store.save(profile(FIRST_ID, "First"))
        val replacement = profile(FIRST_ID, "Updated")

        store.save(replacement)

        assertEquals(listOf(replacement), store.list())
    }

    @Test
    fun deleteIsPersistentAndIdempotent() {
        store.save(profile(FIRST_ID, "First"))

        store.delete(FIRST_ID)
        store.delete(FIRST_ID)

        val recreated = SharedPreferencesServerProfileMetadataStore(context, preferencesName)
        assertNull(recreated.get(FIRST_ID))
        assertTrue(recreated.list().isEmpty())
    }

    @Test
    fun unknownProfileReturnsNull() {
        assertNull(store.get("unknown-profile"))
    }

    @Test
    fun verifiedRepositoryKeepsCredentialOutOfMetadataPreferences() {
        val credentialStore = RecordingCredentialStore()
        val repository = ServerProfileRepository(
            metadataStore = store,
            credentialStore = credentialStore,
            idFactory = { FIRST_ID }
        )
        val draft = ProfileDraft(
            profileName = "Fixture",
            baseUrl = BASE_URL,
            apiKey = SecretValue(SYNTHETIC_SECRET)
        )

        val saved = repository.saveVerified(draft)

        assertNotNull(store.get(saved.id))
        val persisted = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .getString(SharedPreferencesServerProfileMetadataStore.PAYLOAD_KEY, null)
        assertNotNull(persisted)
        assertFalse(persisted!!.contains(SYNTHETIC_SECRET))
        assertTrue(credentialStore.value!!.useRaw { it == SYNTHETIC_SECRET })
    }

    @Test
    fun unknownFormatVersionFailsGenerically() {
        val payload = "{\"version\":999,\"profiles\":[]}"
        writeRawPayload(payload)

        val error = assertMetadataFailure { store.list() }

        assertGeneric(error, listOf(payload, FIRST_ID, SYNTHETIC_SECRET))
    }

    @Test
    fun malformedPayloadFailsGenerically() {
        val payload = "not-json"
        writeRawPayload(payload)

        val error = assertMetadataFailure { store.list() }

        assertGeneric(error, listOf(payload, FIRST_ID, SYNTHETIC_SECRET))
    }

    @Test
    fun incompleteProfileFailsGenerically() {
        val payload = "{\"version\":1,\"profiles\":[{\"id\":\"fixture-id\"}]}"
        writeRawPayload(payload)

        val error = assertMetadataFailure { store.list() }

        assertGeneric(error, listOf(payload, FIRST_ID, SYNTHETIC_SECRET))
    }

    private fun profile(id: String, name: String): ServerProfile = ServerProfile.create(
        id = id,
        profileName = name,
        baseUrl = BASE_URL
    )

    private fun writeRawPayload(payload: String) {
        val committed = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(SharedPreferencesServerProfileMetadataStore.PAYLOAD_KEY, payload)
            .commit()
        assertTrue(committed)
    }

    private fun assertMetadataFailure(action: () -> Unit): ProfileMetadataStoreException {
        return try {
            action()
            fail("Expected ProfileMetadataStoreException")
            throw AssertionError("unreachable")
        } catch (error: ProfileMetadataStoreException) {
            error
        } catch (_: Throwable) {
            fail("Expected ProfileMetadataStoreException")
            throw AssertionError("unreachable")
        }
    }

    private fun assertGeneric(error: ProfileMetadataStoreException, forbidden: List<String>) {
        assertEquals(ProfileMetadataStoreException.MESSAGE, error.message)
        forbidden.forEach { value ->
            assertFalse(error.toString().contains(value))
        }
    }

    private class RecordingCredentialStore : CredentialStore {
        var value: SecretValue? = null

        override fun put(profileId: String, secret: SecretValue) {
            value = secret
        }

        override fun get(profileId: String): SecretValue? = value

        override fun delete(profileId: String) {
            value = null
        }
    }

    private companion object {
        const val FIRST_ID = "fixture-id"
        const val BASE_URL = "https://example.invalid/p/test"
        const val SYNTHETIC_SECRET = "fixture-credential"
    }
}
