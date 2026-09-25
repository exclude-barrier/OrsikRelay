package io.github.excludebarrier.orsikrelay.domain.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Product Gate: parsing of the `orsik://` pairing deep link. The link is the
 * single input that lets a fresh app auto-discover the server (base URL) and
 * carry the pairing code — no manual base-URL or API-key entry. The device
 * key is never part of the link.
 */
class PairingDeepLinkTest {

    @Test
    fun validDeepLinkParsesServerAndCode() {
        val link = "orsik://pair?server=" + enc("https://server.example") + "&code=" + CODE
        val parsed = PairingDeepLink.parse(link)
        assertNotNull(parsed)
        assertEquals("https://server.example", parsed!!.baseUrl)
        assertEquals(CODE, parsed.pairingCode)
    }

    @Test
    fun plainUnencodedServerIsAlsoAccepted() {
        // Robust to an already-plain (unpercent-encoded) URL value.
        val link = "orsik://pair?server=https://server.example&code=" + CODE
        val parsed = PairingDeepLink.parse(link)
        assertNotNull(parsed)
        assertEquals("https://server.example", parsed!!.baseUrl)
    }

    @Test
    fun schemeWithoutDoubleSlashIsAccepted() {
        val link = "orsik:pair?server=" + enc("https://host.example") + "&code=" + CODE
        val parsed = PairingDeepLink.parse(link)
        assertNotNull(parsed)
        assertEquals("https://host.example", parsed!!.baseUrl)
    }

    @Test
    fun suggestedProfileNameIsFirstHostLabel() {
        assertEquals("server", PairingDeepLink.parse(validLink())!!.suggestedProfileName)
    }

    @Test
    fun suggestedNameStripsPortAndNonNameChars() {
        assertEquals("myhost", PairingDeepLink.suggestedName("https://myhost.example:8443/"))
        assertEquals("h", PairingDeepLink.suggestedName("https://h"))
    }

    @Test
    fun suggestedNameFailsClosedOnInvalidUrl() {
        assertEquals("Hermes", PairingDeepLink.suggestedName("not a url"))
        assertEquals("Hermes", PairingDeepLink.suggestedName(""))
    }

    @Test
    fun wrongSchemeIsRejected() {
        assertNull(PairingDeepLink.parse("https://pair?server=x&code=" + CODE))
        assertNull(PairingDeepLink.parse("other://pair?server=x&code=" + CODE))
    }

    @Test
    fun wrongHostIsRejected() {
        assertNull(PairingDeepLink.parse("orsik://setup?server=x&code=" + CODE))
    }

    @Test
    fun missingQueryIsRejected() {
        assertNull(PairingDeepLink.parse("orsik://pair"))
    }

    @Test
    fun missingServerIsRejected() {
        assertNull(PairingDeepLink.parse("orsik://pair?code=" + CODE))
    }

    @Test
    fun missingCodeIsRejected() {
        assertNull(PairingDeepLink.parse("orsik://pair?server=" + enc("https://h")))
    }

    @Test
    fun emptyServerOrCodeIsRejected() {
        assertNull(PairingDeepLink.parse("orsik://pair?server=&code=" + CODE))
        assertNull(PairingDeepLink.parse("orsik://pair?server=" + enc("https://h") + "&code="))
    }

    @Test
    fun blankInputIsRejected() {
        assertNull(PairingDeepLink.parse(""))
        assertNull(PairingDeepLink.parse("   "))
    }

    @Test
    fun malformedQueryPairIsRejected() {
        // A query segment without '=' is malformed.
        assertNull(PairingDeepLink.parse("orsik://pair?server&code=" + CODE))
    }

    @Test
    fun caseInsensitiveSchemeIsAccepted() {
        val link = "ORSIK://PAIR?server=" + enc("https://h") + "&code=" + CODE
        assertNotNull(PairingDeepLink.parse(link))
    }

    @Test
    fun plaintextHttpServerIsRejected() {
        assertNull(PairingDeepLink.parse("orsik://pair?server=" + enc("http://h") + "&code=" + CODE))
    }

    @Test
    fun nonHttpsServerIsRejected() {
        assertNull(PairingDeepLink.parse("orsik://pair?server=" + enc("ftp://h") + "&code=" + CODE))
    }

    @Test
    fun relativeSchemelessServerIsRejected() {
        assertNull(PairingDeepLink.parse("orsik://pair?server=h&code=" + CODE))
    }

    @Test
    fun serverWithUserInfoIsRejected() {
        assertNull(PairingDeepLink.parse("orsik://pair?server=" + enc("https://user:pass@h") + "&code=" + CODE))
    }

    @Test
    fun fragmentIsRejected() {
        val link = "orsik://pair?server=" + enc("https://h") + "&code=" + CODE + "#frag"
        assertNull(PairingDeepLink.parse(link))
    }

    @Test
    fun malformedPercentEncodingIsRejectedNotThrown() {
        // '%ZZ' is an illegal escape; parse must return null, not throw.
        assertNull(PairingDeepLink.parse("orsik://pair?server=%ZZ&code=" + CODE))
    }

    @Test
    fun codeFailingContractIsRejected() {
        // 'ABC' is not a 24-char closed-alphabet code -> DevicePairCode fails.
        assertNull(PairingDeepLink.parse("orsik://pair?server=" + enc("https://h") + "&code=ABC"))
    }

    private fun validLink() =
        "orsik://pair?server=" + enc("https://server.example") + "&code=" + CODE

    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        // 22 letters (A-Z without I,L,O,U) + two digits, length 24.
        const val CODE = "ABCDEFGHJKMNPQRSTVWXYZ12"
    }
}
