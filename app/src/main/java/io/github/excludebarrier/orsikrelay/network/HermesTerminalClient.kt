package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalCloseReason
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalConnection
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalEvent
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalTransport
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.json.JSONObject

/**
 * The bridge's `GET /v1/terminal` WebSocket: binary frames carry the PTY
 * bytes both ways, a text frame `{"type":"resize",...}` the size. HTTPS
 * only (wss), no redirects, never retried; a dead link is found by pings.
 */
class HermesTerminalClient(
    okHttpClient: OkHttpClient = HermesHttp.defaultClient()
) : TerminalTransport {

    private val capabilitiesClient = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val socketClient = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        // A terminal is quiet for minutes; pings, not a read timeout, find a
        // dead link. The call timeout would end the open socket.
        .readTimeout(0, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.SECONDS)
        .pingInterval(PING_SECONDS, TimeUnit.SECONDS)
        .build()

    override fun supports(baseUrl: String, apiKey: SecretValue): Boolean {
        val url = base(baseUrl, apiKey)?.newBuilder()?.addPathSegment("v1")?.addPathSegment("capabilities")?.build()
            ?: return false
        return try {
            capabilitiesClient.newCall(request(url, apiKey)).execute().use { response ->
                if (response.code != 200) return false
                requireJsonContentType(response) { return false }
                val root = JSONObject(readBoundedUtf8(response.body, MAX_CAPABILITIES_BYTES) { return false })
                val endpoint = root.optJSONObject("endpoints")?.optJSONObject("terminal") ?: return false
                root.optJSONObject("features")?.opt("terminal") == true &&
                    endpoint.opt("method") == "GET" && endpoint.opt("path") == PATH
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun open(
        baseUrl: String,
        apiKey: SecretValue,
        onEvent: (TerminalEvent) -> Unit
    ): TerminalConnection? {
        val url = base(baseUrl, apiKey)?.newBuilder()?.addPathSegment("v1")?.addPathSegment("terminal")?.build()
            ?: return null
        val closed = AtomicBoolean(false)
        fun finish(reason: TerminalCloseReason) {
            if (closed.compareAndSet(false, true)) onEvent(TerminalEvent.Closed(reason))
        }
        val socket = try {
            socketClient.newWebSocket(request(url, apiKey), object : WebSocketListener() {
                override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                    if (!closed.get()) onEvent(TerminalEvent.Output(bytes.toByteArray()))
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    // The bridge sends output only as binary frames.
                    webSocket.close(PROTOCOL_ERROR, null)
                    finish(TerminalCloseReason.Disconnected)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(NORMAL, null)
                    finish(closeReason(code))
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    finish(failureReason(response?.code))
                }
            })
        } catch (_: Exception) {
            return null
        }
        return object : TerminalConnection {
            override fun send(bytes: ByteArray): Boolean =
                !closed.get() && bytes.size <= MAX_INPUT_BYTES && socket.send(bytes.toByteString())

            override fun resize(columns: Int, rows: Int): Boolean {
                if (closed.get() || columns !in 1..MAX_COLUMNS || rows !in 1..MAX_ROWS) return false
                return socket.send("""{"type":"resize","cols":$columns,"rows":$rows}""")
            }

            override fun close() {
                if (closed.compareAndSet(false, true)) socket.close(NORMAL, null)
            }
        }
    }

    private fun base(baseUrl: String, apiKey: SecretValue): HttpUrl? {
        if (!isSafeApiKey(apiKey)) return null
        return ProfileBaseUrlValidator.validate(baseUrl).getOrNull()?.toHttpUrl()
    }

    private fun request(url: HttpUrl, apiKey: SecretValue): Request =
        apiKey.useRaw { rawKey -> Request.Builder().url(url).get().header("Authorization", "Bearer $rawKey").build() }

    internal companion object {
        const val PATH = "/v1/terminal"
        const val MAX_CAPABILITIES_BYTES = 64 * 1024
        /** The bridge's bound of one client message. */
        const val MAX_INPUT_BYTES = 64 * 1024
        const val MAX_COLUMNS = 500
        const val MAX_ROWS = 300
        const val PING_SECONDS = 20L
        const val NORMAL = 1000
        const val PROTOCOL_ERROR = 1002

        fun closeReason(code: Int): TerminalCloseReason = when (code) {
            1000 -> TerminalCloseReason.Exited
            4401 -> TerminalCloseReason.Unauthorized
            else -> TerminalCloseReason.Disconnected
        }

        fun failureReason(httpCode: Int?): TerminalCloseReason = when (httpCode) {
            401 -> TerminalCloseReason.Unauthorized
            403 -> TerminalCloseReason.Disabled
            429 -> TerminalCloseReason.Busy
            else -> TerminalCloseReason.Disconnected
        }
    }
}
