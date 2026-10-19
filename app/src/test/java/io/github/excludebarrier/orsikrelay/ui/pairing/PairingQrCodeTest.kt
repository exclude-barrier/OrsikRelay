package io.github.excludebarrier.orsikrelay.ui.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import io.github.excludebarrier.orsikrelay.domain.device.PairingDeepLink
import java.net.URLEncoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The QR scan payload contract.
 *
 * A scanned text is accepted only through the existing deep-link parser, so
 * this proves both the real ZXing decode path (encode with QRCodeWriter, then
 * decode the luminance image exactly as the camera analyzer does) and the
 * rejection of foreign URLs, plain http, and malformed codes.
 */
class PairingQrCodeTest {

    @Test
    fun aRealQrCodeRoundTripDecodesToAValidPairingBootstrap() {
        val link = pairingLink("https://server.example")
        val matrix = QRCodeWriter().encode(
            link,
            BarcodeFormat.QR_CODE,
            IMAGE_SIZE,
            IMAGE_SIZE,
            mapOf(EncodeHintType.MARGIN to 1)
        )
        val luminance = ByteArray(matrix.width * matrix.height)
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                luminance[y * matrix.width + x] =
                    (if (matrix.get(x, y)) 0 else 255).toByte()
            }
        }

        val decoded = QrLuminanceDecoder.decode(luminance, matrix.width, matrix.height)
        assertEquals(link, decoded)

        val bootstrap = PairingDeepLink.parse(decoded!!)
        assertNotNull(bootstrap)
        assertEquals("https://server.example", bootstrap!!.baseUrl)
        assertEquals(CODE, bootstrap.pairingCode)
    }

    @Test
    fun aForeignQrPayloadIsRejected() {
        assertNull(PairingDeepLink.parse("https://example.invalid/not-orsik"))
    }

    @Test
    fun aPlainHttpPairingLinkIsRejected() {
        assertNull(
            PairingDeepLink.parse(
                "orsik://pair?server=" + enc("http://server.example") + "&code=" + CODE
            )
        )
    }

    @Test
    fun aMalformedPairingCodeIsRejected() {
        assertNull(
            PairingDeepLink.parse(
                "orsik://pair?server=" + enc("https://server.example") + "&code=ABC"
            )
        )
    }

    private fun pairingLink(server: String): String =
        "orsik://pair?server=" + enc(server) + "&code=" + CODE

    private fun enc(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())

    private companion object {
        const val CODE = "ABCD2345EFGH6789JKMN2345"
        const val IMAGE_SIZE = 300
    }
}
