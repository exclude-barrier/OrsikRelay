package io.github.excludebarrier.orsikrelay.domain.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * Edge cases of the guarded [ServerProfile.create] factory: the persisted
 * metadata must already be in its canonical form, and extreme lengths must be
 * rejected without side effects.
 */
class ServerProfileEdgeCaseTest {

    @Test
    fun `blank identifiers are rejected`() {
        listOf("", " ", "\t", "\n").forEach { id ->
            assertCreateFails(id = id)
        }
    }

    @Test
    fun `profile names are rejected when blank untrimmed or too long`() {
        listOf(
            "",
            "   ",
            "\t",
            " Fixture",
            "Fixture ",
            "a".repeat(ProfileFormValidator.PROFILE_NAME_MAX_LENGTH + 1),
            "a".repeat(100_000)
        ).forEach { name ->
            assertCreateFails(profileName = name)
        }
    }

    @Test
    fun `exactly maximum length name is accepted`() {
        val name = "a".repeat(ProfileFormValidator.PROFILE_NAME_MAX_LENGTH)

        val profile = ServerProfile.create(FIXED_ID, name, NORMALIZED_URL)

        assertEquals(name, profile.profileName)
    }

    @Test
    fun `non-canonical base urls are rejected`() {
        listOf(
            "https://example.org/",
            "https://example.org/p/android/",
            "HTTPS://example.org",
            "http://example.org",
            "https://user@example.org",
            "https://example.org?x=1",
            "https://example.org#f",
            "https://example.org/p/a b"
        ).forEach { url ->
            assertCreateFails(baseUrl = url)
        }
    }

    @Test
    fun `canonical base urls with IPv4 IPv6 port and path are accepted`() {
        listOf(
            "https://127.0.0.1",
            "https://[::1]",
            "https://[2001:db8::1]:8443/p/android",
            "https://example.org:8443/p/android"
        ).forEach { url ->
            val profile = ServerProfile.create(FIXED_ID, FIXTURE_NAME, url)

            assertEquals(url, profile.baseUrl)
        }
    }

    @Test
    fun `the identifier is preserved verbatim`() {
        val id = "fixture-id.with:reserved/characters"

        val profile = ServerProfile.create(id, FIXTURE_NAME, NORMALIZED_URL)

        assertEquals(id, profile.id)
    }

    @Test
    fun `creation is deterministic for the same values`() {
        val first = ServerProfile.create(FIXED_ID, FIXTURE_NAME, NORMALIZED_URL)
        val second = ServerProfile.create(FIXED_ID, FIXTURE_NAME, NORMALIZED_URL)

        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
    }

    private fun assertCreateFails(
        id: String = FIXED_ID,
        profileName: String = FIXTURE_NAME,
        baseUrl: String = NORMALIZED_URL
    ) {
        try {
            ServerProfile.create(id, profileName, baseUrl)
            fail("Expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // Expected: the factory exposes one fixed, input-free message.
        }
    }

    private companion object {
        const val FIXED_ID = "fixture-id"
        const val FIXTURE_NAME = "Fixture"
        const val NORMALIZED_URL = "https://example.org/p/android"
    }
}
