package io.github.excludebarrier.orsikrelay.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import javax.net.ssl.SSLHandshakeException

class TlsFailureTest {

    @Test
    fun directSslExceptionIsTls() {
        assertTrue(SSLHandshakeException("handshake").hasTlsFailure())
    }

    @Test
    fun sslExceptionAsCauseIsTls() {
        assertTrue(IOException("wrapped", SSLHandshakeException("handshake")).hasTlsFailure())
    }

    @Test
    fun suppressedSslExceptionFromFastFallbackIsTls() {
        // OkHttp 5 shape: every route failed, the TLS failure is suppressed.
        val connect = ConnectException("Failed to connect")
        connect.initCause(ConnectException("Connection refused"))
        connect.addSuppressed(SSLHandshakeException("PKIX path building failed"))
        assertTrue(connect.hasTlsFailure())
    }

    @Test
    fun plainNetworkFailureIsNotTls() {
        val connect = ConnectException("Failed to connect")
        connect.addSuppressed(ConnectException("Connection refused"))
        assertFalse(connect.hasTlsFailure())
        assertFalse(IOException("reset").hasTlsFailure())
    }

    @Test
    fun cyclicSuppressionTerminates() {
        val a = IOException("a")
        val b = IOException("b")
        a.addSuppressed(b)
        b.addSuppressed(a)
        assertFalse(a.hasTlsFailure())
    }
}
