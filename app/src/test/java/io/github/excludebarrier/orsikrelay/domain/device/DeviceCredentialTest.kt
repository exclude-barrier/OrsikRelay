package io.github.excludebarrier.orsikrelay.domain.device

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Mission 017A: [DeviceCredential] secret hygiene. The raw device key
 * must never appear in `toString()`, and equality must never expose it.
 */
class DeviceCredentialTest {

    private val key = SecretValue(DEVICE_KEY)
    private val credential = DeviceCredential(
        deviceId = DEVICE_ID,
        deviceKey = key,
        profile = "fixture-profile",
        label = "fixture-label",
        permissions = listOf("approvals", "chat", "runs"),
        issuedAt = 1757000000.125
    )

    @Test
    fun toStringRedactsTheRawKey() {
        val rendered = credential.toString()
        assertFalse(rendered.contains(DEVICE_KEY))
        assertTrue(rendered.contains("[REDACTED]"))
        // The non-secret fields remain visible.
        assertTrue(rendered.contains(DEVICE_ID))
        assertTrue(rendered.contains("fixture-label"))
    }

    @Test
    fun toStringOfFailureCarriesNoKey() {
        val failure = DevicePairResult.Failure(DevicePairError.IncompatibleApi)
        assertFalse(failure.toString().contains(DEVICE_KEY))
    }

    @Test
    fun equalityIsStableAndDoesNotExposeTheKey() {
        val same = DeviceCredential(
            deviceId = DEVICE_ID,
            deviceKey = key,
            profile = "fixture-profile",
            label = "fixture-label",
            permissions = listOf("approvals", "chat", "runs"),
            issuedAt = 1757000000.125
        )
        val differentKeyInstance = DeviceCredential(
            deviceId = DEVICE_ID,
            deviceKey = SecretValue(DEVICE_KEY),
            profile = "fixture-profile",
            label = "fixture-label",
            permissions = listOf("approvals", "chat", "runs"),
            issuedAt = 1757000000.125
        )
        assertTrue(credential == same)
        assertEquals(credential.hashCode(), same.hashCode())
        // The key is a scoped SecretValue: identity-based comparison, so
        // a different instance with the same raw value is not equal —
        // the raw value itself is never compared in the public API.
        assertNotEquals(credential, differentKeyInstance)
        // Neither representation may carry the raw key.
        assertFalse(credential.toString().contains(DEVICE_KEY))
        assertFalse(same.toString().contains(DEVICE_KEY))
    }

    private companion object {
        const val DEVICE_ID = "3f9c2a1e-5b6d-4e7f-8a9b-0c1d2e3f4a5b"
        const val DEVICE_KEY = "hd_ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopq"
    }
}
