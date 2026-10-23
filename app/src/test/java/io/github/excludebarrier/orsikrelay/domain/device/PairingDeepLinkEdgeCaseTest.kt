package io.github.excludebarrier.orsikrelay.domain.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Adversarial `orsik://` pairing links: parameter ambiguity, percent-encoding
 * tricks, exact code limits and code normalization, and the closed set of
 * acceptable server URLs.
 */
class PairingDeepLinkEdgeCaseTest {

    @Test
    fun unrelatedExtraParametersAreIgnored() {
        val parsed = PairingDeepLink.parse(
            link(SERVER) + "&utm_source=campaign&profile=Other"
        )

        assertNotNull(parsed)
        assertEquals(SERVER, parsed!!.baseUrl)
        assertEquals(CODE, parsed.pairingCode)
    }

    @Test
    fun percentEncodedParameterNamesAreDecoded() {
        val raw = "orsik://pair?%73erver=" + enc(SERVER) + "&%63ode=" + CODE

        val parsed = PairingDeepLink.parse(raw)

        assertNotNull(parsed)
        assertEquals(SERVER, parsed!!.baseUrl)
        assertEquals(CODE, parsed.pairingCode)
    }

    @Test
    fun aDuplicateServerParameterIsRejectedAsAmbiguous() {
        // A link carrying two different servers is malformed. Accepting one
        // of them silently means two parsers (or a later refactor) can
        // disagree about which server the user actually paired with.
        // SUSPECTED BUG: parseQuery stores parameters in a LinkedHashMap, so
        // the last duplicate wins instead of failing closed.
        val raw = "orsik://pair?server=" + enc("https://first.example") +
            "&server=" + enc("https://second.example") +
            "&code=" + CODE

        assertNull(PairingDeepLink.parse(raw))
    }

    @Test
    fun aDoubleEncodedServerValueIsRejected() {
        val raw = "orsik://pair?server=" + enc(enc(SERVER)) + "&code=" + CODE

        assertNull(PairingDeepLink.parse(raw))
    }

    @Test
    fun anEncodedAmpersandInsideTheServerValueDoesNotSplitTheQuery() {
        val server = "https://host.example/a&b"
        val parsed = PairingDeepLink.parse(link(server))

        assertNotNull(parsed)
        assertEquals(server, parsed!!.baseUrl)
    }

    @Test
    fun malformedPercentEncodingIsRejectedNotThrown() {
        listOf("%ZZ", "%", "%2", "%G0").forEach { bad ->
            assertNull(
                "server escape $bad",
                PairingDeepLink.parse("orsik://pair?server=$bad&code=$CODE")
            )
            assertNull(
                "code escape $bad",
                PairingDeepLink.parse("orsik://pair?server=" + enc(SERVER) + "&code=$bad")
            )
        }
    }

    @Test
    fun validCodeFormsAreAcceptedAndTheCodeIsReturnedVerbatim() {
        val displayed = "ABCD-EFGH-JKMN-PQRS-TVWX-YZ12"
        val lowercase = CODE.lowercase()
        val whitespaceSeparated = "ABCD EFGH_JKMN-PQRS TVWX YZ12"

        listOf(CODE, displayed, whitespaceSeparated).forEach { code ->
            val parsed = PairingDeepLink.parse(link(SERVER, code))
            assertNotNull("code $code", parsed)
            assertEquals(code, parsed!!.pairingCode)
        }

        val lowered = PairingDeepLink.parse(link(SERVER, lowercase))
        assertNotNull(lowered)
        assertEquals(lowercase, lowered!!.pairingCode)
    }

    @Test
    fun codeLengthBoundariesAreExactAtTheDisplayGuard() {
        assertNull(PairingDeepLink.parse(link(SERVER, CODE.dropLast(1))))
        assertNull(PairingDeepLink.parse(link(SERVER, CODE + "A")))

        // 24 canonical characters plus 40 separators is exactly the 64
        // character display guard and still normalizes to the code.
        val atGuard = CODE + "-".repeat(40)
        assertNotNull(PairingDeepLink.parse(link(SERVER, atGuard)))

        // One more separator exceeds the guard before normalization.
        assertNull(PairingDeepLink.parse(link(SERVER, CODE + "-".repeat(41))))
    }

    @Test
    fun charactersOutsideTheClosedAlphabetAreRejected() {
        listOf("I", "L", "O", "U", "0", "i", "l", "o", "u").forEach { bad ->
            assertNull(
                "code ending in $bad",
                PairingDeepLink.parse(link(SERVER, CODE.dropLast(1) + bad))
            )
        }
    }

    @Test
    fun wrongSchemeAndHostVariantsAreRejected() {
        val query = "server=" + enc(SERVER) + "&code=" + CODE
        listOf(
            "orsik:/pair?$query",
            "orsik://pair.example?$query",
            "orsik://pair:443?$query",
            "orsik://pair/path?$query",
            "orsik://user@pair?$query",
            "orsik//pair?$query",
            " orsik://pair?$query",
            "https://pair?$query"
        ).forEach { raw ->
            assertNull(raw, PairingDeepLink.parse(raw))
        }
    }

    @Test
    fun schemeAndHostComparisonIsCaseInsensitive() {
        val parsed = PairingDeepLink.parse(
            "OrSiK://PaIr?server=" + enc(SERVER) + "&code=" + CODE
        )

        assertNotNull(parsed)
        assertEquals(SERVER, parsed!!.baseUrl)
    }

    @Test
    fun serverUrlMustBeAbsoluteHttpsWithoutUserInfo() {
        listOf(
            "https://host.example",
            "https://host.example:8443/p/android",
            "HTTPS://host.example"
        ).forEach { server ->
            val parsed = PairingDeepLink.parse(link(server))
            assertNotNull("server $server", parsed)
            assertEquals(server, parsed!!.baseUrl)
        }

        listOf(
            "http://host.example",
            "ftp://host.example",
            "host.example",
            "//host.example",
            "https://",
            "https:///host.example",
            "https://user@host.example",
            "https://user:pass@host.example",
            " https://host.example",
            "https://host.example ",
            "https://host example"
        ).forEach { server ->
            assertNull("server $server", PairingDeepLink.parse(link(server)))
        }
    }

    @Test
    fun parameterNamesAreCaseSensitive() {
        val raw = "orsik://pair?Server=" + enc(SERVER) + "&Code=" + CODE

        assertNull(PairingDeepLink.parse(raw))
    }

    @Test
    fun emptyOrWhitespaceOnlyValuesAreRejected() {
        assertNull(PairingDeepLink.parse("orsik://pair?server=&code=$CODE"))
        assertNull(PairingDeepLink.parse(link(SERVER, "")))
        assertNull(PairingDeepLink.parse(link(SERVER, "   ")))
        assertNull(PairingDeepLink.parse(link("", CODE)))
        assertNull(PairingDeepLink.parse(link("   ", CODE)))
        assertNull(PairingDeepLink.parse(link("https://", CODE)))
    }

    @Test
    fun aQueryPairWithoutAnEqualsSignIsRejectedNotSkipped() {
        val raw = "orsik://pair?server=" + enc(SERVER) + "&code=$CODE&malformed"

        assertNull(PairingDeepLink.parse(raw))
    }

    private fun link(server: String, code: String = CODE): String =
        "orsik://pair?server=" + enc(server) + "&code=" + enc(code)

    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private companion object {
        const val SERVER = "https://server.example"
        // 22 letters (A-Z without I,L,O,U) + two digits, length 24.
        const val CODE = "ABCDEFGHJKMNPQRSTVWXYZ12"
    }
}
