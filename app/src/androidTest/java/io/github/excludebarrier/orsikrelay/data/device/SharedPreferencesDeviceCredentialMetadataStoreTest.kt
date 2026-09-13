package io.github.excludebarrier.orsikrelay.data.device

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
class SharedPreferencesDeviceCredentialMetadataStoreTest {

    private lateinit var context: Context
    private lateinit var preferencesName: String
    private lateinit var store: SharedPreferencesDeviceCredentialMetadataStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        preferencesName = "hermes_device_metadata_test_${UUID.randomUUID()}"
        store = SharedPreferencesDeviceCredentialMetadataStore(context, preferencesName)
    }

    @After
    fun tearDown() {
        context.deleteSharedPreferences(preferencesName)
    }

    @Test
    fun metadataSurvivesARecreatedStoreAndProfilesStayIsolated() {
        val first = metadata(FIRST_DEVICE_ID, "first")
        val second = metadata(SECOND_DEVICE_ID, "second")
        store.put(FIRST_PROFILE_ID, first)
        store.put(SECOND_PROFILE_ID, second)

        val recreated = SharedPreferencesDeviceCredentialMetadataStore(context, preferencesName)

        assertEquals(first, recreated.get(FIRST_PROFILE_ID))
        assertEquals(second, recreated.get(SECOND_PROFILE_ID))
        assertNull(recreated.get("unknown-profile"))
        assertFalse(store.preferenceKeyFor(FIRST_PROFILE_ID).contains(FIRST_PROFILE_ID))
        assertFalse(store.preferenceKeyFor(SECOND_PROFILE_ID).contains(SECOND_PROFILE_ID))
    }

    @Test
    fun persistedPayloadContainsOnlyNonSecretMetadata() {
        store.put(FIRST_PROFILE_ID, metadata(FIRST_DEVICE_ID, SYNTHETIC_LABEL))

        val raw = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).all
        assertEquals(1, raw.size)
        val payload = raw.values.single().toString()
        assertTrue(payload.contains(SYNTHETIC_LABEL))
        assertFalse(payload.contains(SYNTHETIC_DEVICE_KEY))
        assertFalse(payload.contains(FIRST_PROFILE_ID))
    }

    @Test
    fun overwriteAndDeleteArePersistentAndDeleteIsIdempotent() {
        store.put(FIRST_PROFILE_ID, metadata(FIRST_DEVICE_ID, "first"))
        val replacement = metadata(SECOND_DEVICE_ID, "replacement")
        store.put(FIRST_PROFILE_ID, replacement)
        assertEquals(replacement, store.get(FIRST_PROFILE_ID))

        store.delete(FIRST_PROFILE_ID)
        store.delete(FIRST_PROFILE_ID)

        val recreated = SharedPreferencesDeviceCredentialMetadataStore(context, preferencesName)
        assertNull(recreated.get(FIRST_PROFILE_ID))
    }

    @Test
    fun malformedUnknownAndFractionalVersionsFailGenerically() {
        listOf(
            "not-json",
            validPayload().replace("\"version\":1", "\"version\":999"),
            validPayload().replace("\"version\":1", "\"version\":1.5"),
            validPayload().replace("}", ",\"extra\":true}")
        ).forEach { payload ->
            writeRaw(FIRST_PROFILE_ID, payload)
            assertClosedFailure(payload) { store.get(FIRST_PROFILE_ID) }
        }
    }

    @Test
    fun corruptMetadataFieldsFailClosed() {
        listOf(
            validPayload().replace(FIRST_DEVICE_ID, "not-a-uuid"),
            validPayload().replace("\"profile\":\"default\"", "\"profile\":\"\""),
            validPayload().replace("\"runs\"", "\"admin\""),
            validPayload().replace("\"issued_at\":1757000000.125", "\"issued_at\":-1")
        ).forEach { payload ->
            writeRaw(FIRST_PROFILE_ID, payload)
            assertClosedFailure(payload) { store.get(FIRST_PROFILE_ID) }
        }
    }

    @Test
    fun invalidMetadataIsRejectedBeforePersistence() {
        val invalid = metadata("not-a-uuid", "invalid")
        assertClosedFailure("not-a-uuid") { store.put(FIRST_PROFILE_ID, invalid) }
        assertTrue(
            context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).all.isEmpty()
        )
    }

    private fun metadata(deviceId: String, label: String) = DeviceCredentialMetadata(
        deviceId = deviceId,
        profile = "default",
        label = label,
        permissions = listOf("approvals", "chat", "runs"),
        issuedAt = 1757000000.125
    )

    private fun validPayload(): String =
        "{\"version\":1,\"device_id\":\"$FIRST_DEVICE_ID\",\"profile\":\"default\"," +
            "\"label\":\"fixture\",\"permissions\":[\"approvals\",\"chat\",\"runs\"]," +
            "\"issued_at\":1757000000.125}"

    private fun writeRaw(profileId: String, payload: String) {
        val committed = context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(store.preferenceKeyFor(profileId), payload)
            .commit()
        assertTrue(committed)
    }

    private fun assertClosedFailure(forbidden: String, action: () -> Unit) {
        val error = try {
            action()
            fail("Expected DeviceCredentialStoreException")
            throw AssertionError("unreachable")
        } catch (error: DeviceCredentialStoreException) {
            error
        }
        assertNotNull(error)
        assertEquals(DeviceCredentialStoreException.MESSAGE, error.message)
        assertFalse(error.toString().contains(forbidden))
    }

    private companion object {
        const val FIRST_PROFILE_ID = "fixture-profile-alpha"
        const val SECOND_PROFILE_ID = "fixture-profile-beta"
        const val FIRST_DEVICE_ID = "3f9c2a1e-5b6d-4e7f-8a9b-0c1d2e3f4a5b"
        const val SECOND_DEVICE_ID = "4a0d3b2f-6c7e-4f80-9bac-1d2e3f4a5b6c"
        const val SYNTHETIC_LABEL = "fixture-device-label"
        const val SYNTHETIC_DEVICE_KEY = "hd_fixture-secret-must-not-appear"
    }
}
