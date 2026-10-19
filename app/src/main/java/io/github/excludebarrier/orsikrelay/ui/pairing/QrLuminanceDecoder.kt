package io.github.excludebarrier.orsikrelay.ui.pairing

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Pure ZXing decoding of a camera luminance image.
 *
 * Kept free of Android/CameraX types so the exact decoding path used by the
 * scanner is unit-testable (including a QRCodeWriter round trip). Returns the
 * raw payload text, or null when the image contains no readable QR code.
 */
internal object QrLuminanceDecoder {

    private val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true
    )

    fun decode(luminance: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || luminance.size < width * height) return null
        return try {
            val source = PlanarYUVLuminanceSource(
                luminance,
                width,
                height,
                0,
                0,
                width,
                height,
                false
            )
            val reader = MultiFormatReader()
            reader.setHints(hints)
            reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))).text
        } catch (_: Exception) {
            // NotFound/Checksum/Format exceptions are the normal "no QR here"
            // case; nothing is logged because the image may contain a secret.
            null
        }
    }
}
