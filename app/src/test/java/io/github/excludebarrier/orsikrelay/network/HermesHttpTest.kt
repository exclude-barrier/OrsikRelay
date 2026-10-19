package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HermesHttpTest {

    @Test
    fun derivedDefaultsShareDispatcherAndConnectionPool() {
        val oneShot = HermesHttp.defaultClient()
        val stream = HermesHttp.defaultClient(
            readTimeoutSeconds = 45,
            callTimeoutSeconds = 0
        )

        assertSame(oneShot.dispatcher, stream.dispatcher)
        assertSame(oneShot.connectionPool, stream.connectionPool)
        for (client in listOf(oneShot, stream)) {
            assertFalse(client.followRedirects)
            assertFalse(client.followSslRedirects)
            assertFalse(client.retryOnConnectionFailure)
        }
    }

    @Test
    fun oneShotDefaultKeepsEstablishedTimeouts() {
        val client = HermesHttp.defaultClient()

        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(10_000, client.readTimeoutMillis)
        assertEquals(10_000, client.writeTimeoutMillis)
        assertEquals(15_000, client.callTimeoutMillis)
    }

    @Test
    fun streamDefaultKeepsLongLivedTimeoutsOnTheSharedBase() {
        val defaults = HermesSessionStreamClient.defaultSessionStreamHttpClient()

        assertEquals(10_000, defaults.connectTimeoutMillis)
        assertEquals(
            (HermesSessionStreamClient.STREAM_READ_TIMEOUT_SECONDS * 1000).toInt(),
            defaults.readTimeoutMillis
        )
        assertEquals(10_000, defaults.writeTimeoutMillis)
        assertEquals(0, defaults.callTimeoutMillis)
        assertSame(HermesHttp.defaultClient().dispatcher, defaults.dispatcher)
        assertSame(HermesHttp.defaultClient().connectionPool, defaults.connectionPool)
    }

    @Test
    fun apiKeyMustBeNonEmptyAndFreeOfWhitespaceAndControls() {
        assertTrue(isSafeApiKey(SecretValue("abc")))
        assertFalse(isSafeApiKey(SecretValue("")))
        assertFalse(isSafeApiKey(SecretValue("a b")))
        assertFalse(isSafeApiKey(SecretValue("a\nb")))
        assertFalse(isSafeApiKey(SecretValue("a\u0000b")))
        assertFalse(isSafeApiKey(SecretValue("a".repeat(MAX_API_KEY_CHARACTERS + 1))))
        assertTrue(isSafeApiKey(SecretValue("a".repeat(MAX_API_KEY_CHARACTERS))))
    }

    @Test
    fun opaqueIdIsNonBlankBoundedAndControlFree() {
        assertTrue(isSafeOpaqueId("run-1"))
        assertFalse(isSafeOpaqueId(""))
        assertFalse(isSafeOpaqueId("   "))
        assertFalse(isSafeOpaqueId("a\nb"))
        assertTrue(isSafeOpaqueId("a".repeat(256)))
        assertFalse(isSafeOpaqueId("a".repeat(257)))
        assertTrue(isSafeOpaqueId("abc", maxLength = 3))
        assertFalse(isSafeOpaqueId("abcd", maxLength = 3))
    }

    @Test
    fun boundedReadAcceptsExactLimitAndEmptyBody() {
        val bytes = "hello".toByteArray()
        val exact = bytes.toResponseBody(JSON_MEDIA_TYPE)
        assertEquals("hello", readBoundedUtf8(exact, bytes.size) { invalid() })

        val empty = ByteArray(0).toResponseBody(JSON_MEDIA_TYPE)
        assertEquals("", readBoundedUtf8(empty, 1) { invalid() })
    }

    @Test
    fun boundedReadRejectsMissingOversizedAndMalformedBodies() {
        assertFails { readBoundedUtf8(null, 1) { invalid() } }

        val bytes = "hello".toByteArray()
        assertFails {
            readBoundedUtf8(bytes.toResponseBody(JSON_MEDIA_TYPE), bytes.size - 1) { invalid() }
        }

        val malformed = byteArrayOf(0xC3.toByte()).toResponseBody(JSON_MEDIA_TYPE)
        assertFails { readBoundedUtf8(malformed, 2) { invalid() } }
    }

    @Test
    fun boundedReadRejectsABodyThatAnnouncesTooManyBytes() {
        val body = object : ResponseBody() {
            override fun contentType() = JSON_MEDIA_TYPE
            override fun contentLength() = 2L
            override fun source() = okio.Buffer().write("hello".toByteArray())
        }

        assertFails { readBoundedUtf8(body, 1) { invalid() } }
    }

    @Test
    fun extractErrorCodeReadsOnlyTheClosedEnvelope() {
        assertEquals("pair_failed", extractErrorCode("""{"error":{"code":"pair_failed"}}"""))
        assertNull(extractErrorCode("""{"error":{"message":"x"}}"""))
        assertNull(extractErrorCode("""{"error":42}"""))
        assertNull(extractErrorCode("not json"))
    }

    private fun invalid(): Nothing = throw IOException("invalid")

    private fun assertFails(block: () -> Unit) {
        assertThrows(IOException::class.java) { block() }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}
