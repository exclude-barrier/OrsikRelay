package io.github.excludebarrier.orsikrelay.ui.pairing

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * CameraX analyzer that decodes QR codes off the main thread.
 *
 * Only the Y (luminance) plane is copied; the resulting payload is handed to
 * [onPayload] exactly as scanned. The payload may contain a single-use pairing
 * code, so it is never logged and never persisted here.
 */
internal class QrImageAnalyzer(
    private val onPayload: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private val busy = AtomicBoolean(false)

    override fun analyze(image: ImageProxy) {
        if (!busy.compareAndSet(false, true)) {
            image.close()
            return
        }
        try {
            val payload = image.readQrPayload()
            if (payload != null) onPayload(payload)
        } finally {
            busy.set(false)
            image.close()
        }
    }

    private fun ImageProxy.readQrPayload(): String? {
        val plane = planes.firstOrNull() ?: return null
        val width = width
        val height = height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (width <= 0 || height <= 0 || pixelStride != 1 || rowStride < width) return null

        val buffer = plane.buffer.duplicate()
        buffer.rewind()
        val luminance = ByteArray(width * height)
        val row = ByteArray(rowStride)
        var offset = 0
        for (y in 0 until height) {
            val remaining = buffer.remaining()
            if (remaining < width) return null
            val toRead = min(rowStride, remaining)
            buffer.get(row, 0, toRead)
            for (x in 0 until width) {
                luminance[offset + x] = row[x]
            }
            offset += width
        }
        return QrLuminanceDecoder.decode(luminance, width, height)
    }
}
