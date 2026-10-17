package io.github.excludebarrier.orsikrelay.data.profile

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.excludebarrier.orsikrelay.data.session.StartupProfile
import io.github.excludebarrier.orsikrelay.data.session.resolveStartupProfile
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.security.credential.AndroidKeystoreCredentialStore
import java.util.UUID
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The selection lifecycle against the REAL stores — real
 * [SharedPreferencesServerProfileMetadataStore], real
 * [AndroidKeystoreCredentialStore] and real
 * [SharedPreferencesActiveServerSelectionStore] (connected).
 *
 * Covers the delete ordering (the persisted selection is removed only
 * after credential, metadata AND draft cleanup succeeded), the preserved
 * selection on any earlier failure, and the real startup resolver's
 * deterministic fallback around the selection. All fixtures are synthetic;
 * no network request is made.
 */
@RunWith(AndroidJUnit4::class)
class ActiveServerSelectionRepositoryConnectedTest {

    private lateinit var context: Context
    private lateinit var metadataStore: SharedPreferencesServerProfileMetadataStore
    private lateinit var credentialStore: AndroidKeystoreCredentialStore
    private lateinit var selectionStore: SharedPreferencesActiveServerSelectionStore
    private lateinit var metaPrefsName: String
    private lateinit var credPrefsName: String
    private lateinit var selPrefsName: String
    private val seededCredentials = linkedSetOf<String>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val testId = UUID.randomUUID().toString()
        metaPrefsName = "hermes_meta_012_$testId"
        credPrefsName = "hermes_credential_012_$testId"
        selPrefsName = "hermes_selection_012_$testId"
        metadataStore = SharedPreferencesServerProfileMetadataStore(
            context,
            metaPrefsName
        )
        credentialStore = AndroidKeystoreCredentialStore(
            context,
            preferencesName = credPrefsName,
            aliasNamespace = "io.github.excludebarrier.orsikrelay.selection012.$testId"
        )
        selectionStore = SharedPreferencesActiveServerSelectionStore(
            context,
            selPrefsName
        )
    }

    @After
    fun tearDown() {
        seededCredentials.forEach { profileId ->
            runCatching { credentialStore.delete(profileId) }
        }
        context.deleteSharedPreferences(metaPrefsName)
        context.deleteSharedPreferences(credPrefsName)
        context.deleteSharedPreferences(selPrefsName)
    }

    @Test
    fun deletingTheSelectedServerClearsThePersistedSelection() {
        save(ALPHA_ID)
        save(BETA_ID)
        val repository = repository(draftClearer = ProfileDraftClearer { })
        repository.selectActiveServer(ALPHA_ID)
        assertEquals(ALPHA_ID, selectionStore.read())

        repository.delete(ALPHA_ID)

        assertNull(selectionStore.read())
        assertNull(metadataStore.get(ALPHA_ID))
    }

    @Test
    fun deletingAnotherServerKeepsTheSelectionIntact() {
        save(ALPHA_ID)
        save(BETA_ID)
        val repository = repository(draftClearer = ProfileDraftClearer { })
        repository.selectActiveServer(ALPHA_ID)

        repository.delete(BETA_ID)

        assertEquals(ALPHA_ID, selectionStore.read())
        assertNull(metadataStore.get(BETA_ID))
    }

    @Test
    fun aFailedDraftCleanupPreservesTheSelectionAndFallsBackDeterministically() {
        save(ALPHA_ID)
        save(BETA_ID)
        val failingClearer = ProfileDraftClearer {
            throw IllegalStateException("synthetic draft cleanup failure")
        }
        val repository = repository(draftClearer = failingClearer)
        repository.selectActiveServer(ALPHA_ID)

        // The deletion itself still succeeds...
        repository.delete(ALPHA_ID)
        assertNull(metadataStore.get(ALPHA_ID))
        // ...but the selection of the deleted profile is preserved.
        assertEquals(ALPHA_ID, selectionStore.read())

        // Startup deterministically falls back: the stored selection no
        // longer validates (no metadata) and Beta is the only complete
        // profile left.
        val startup = resolveStartupProfile(metadataStore, credentialStore, selectionStore)
        assertTrue(startup is StartupProfile.Ready)
        assertEquals(BETA_ID, (startup as StartupProfile.Ready).profileId)
    }

    @Test
    fun selectActiveServerOnlyForACompleteProfileAndFailsGenerically() {
        save(ALPHA_ID)
        val repository = repository(draftClearer = ProfileDraftClearer { })

        // Unknown profile: generic failure, selection untouched.
        val unknownError = assertRepositoryFailure { repository.selectActiveServer("ghost-id") }
        assertEquals(ServerProfileRepositoryException.MESSAGE, unknownError.message)
        assertFalse(unknownError.toString().contains("ghost-id"))
        assertNull(selectionStore.read())

        // Profile with metadata but without a credential: generic failure.
        metadataStore.save(ServerProfile.create(NOCRED_ID, "NoCred", ALPHA_URL))
        val noCredError = assertRepositoryFailure { repository.selectActiveServer(NOCRED_ID) }
        assertEquals(ServerProfileRepositoryException.MESSAGE, noCredError.message)
        assertNull(selectionStore.read())

        // The complete profile is accepted.
        repository.selectActiveServer(ALPHA_ID)
        assertEquals(ALPHA_ID, selectionStore.read())
    }

    @Test
    fun theRealStartupResolverPrefersTheSelectionAndFallsBackAfterDeletion() {
        save(ALPHA_ID)
        save(BETA_ID)
        val repository = repository(draftClearer = ProfileDraftClearer { })

        // Without a selection: the first complete profile by ID (Alpha).
        assertEquals(
            StartupProfile.Ready(ALPHA_ID),
            resolveStartupProfile(metadataStore, credentialStore, selectionStore)
        )

        // The persisted selection wins over the first-stored profile.
        repository.selectActiveServer(BETA_ID)
        assertEquals(
            StartupProfile.Ready(BETA_ID),
            resolveStartupProfile(metadataStore, credentialStore, selectionStore)
        )

        // Deleting the selected server clears the selection; the resolver
        // falls back to Alpha deterministically.
        repository.delete(BETA_ID)
        assertEquals(
            StartupProfile.Ready(ALPHA_ID),
            resolveStartupProfile(metadataStore, credentialStore, selectionStore)
        )
    }

    private fun save(profileId: String) {
        val name = if (profileId == ALPHA_ID) "Alpha" else if (profileId == BETA_ID) "Beta" else "Gamma"
        val baseUrl = if (profileId == BETA_ID) BETA_URL else ALPHA_URL
        val draft = ProfileDraft(
            profileName = name,
            baseUrl = baseUrl,
            apiKey = SecretValue(SYNTHETIC_SECRET)
        )
        ServerProfileRepository(
            metadataStore = metadataStore,
            credentialStore = credentialStore,
            idFactory = { profileId }
        ).saveVerified(draft)
        seededCredentials += profileId
    }

    private fun repository(
        draftClearer: ProfileDraftClearer
    ): ServerProfileRepository = ServerProfileRepository(
        metadataStore = metadataStore,
        credentialStore = credentialStore,
        idFactory = { UUID.randomUUID().toString() },
        profileDraftClearer = draftClearer,
        selectionStore = selectionStore
    )

    private fun assertRepositoryFailure(
        action: () -> Unit
    ): ServerProfileRepositoryException {
        return try {
            action()
            fail("Expected ServerProfileRepositoryException")
            throw AssertionError("unreachable")
        } catch (error: ServerProfileRepositoryException) {
            error
        } catch (_: Throwable) {
            fail("Expected ServerProfileRepositoryException")
            throw AssertionError("unreachable")
        }
    }

    private companion object {
        const val ALPHA_ID = "fixture-profile-a"
        const val BETA_ID = "fixture-profile-b"
        const val NOCRED_ID = "fixture-profile-nocred"
        const val ALPHA_URL = "https://alpha.local"
        const val BETA_URL = "https://beta.local"
        const val SYNTHETIC_SECRET = "fixture-credential-012"
    }
}
