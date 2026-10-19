package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.message.HermesChatMessage
import io.github.excludebarrier.orsikrelay.domain.message.MessageFetcher
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryError
import io.github.excludebarrier.orsikrelay.domain.message.MessageHistoryResult
import io.github.excludebarrier.orsikrelay.domain.message.MessagePage
import io.github.excludebarrier.orsikrelay.domain.message.MessageRole
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Performs the single authenticated `GET /api/sessions/{id}/messages`
 * read. This class is the transport only; profile lookup
 * and credential access happen in the repository layer, so the key never
 * reaches this class except as the scoped [SecretValue] argument.
 *
 * Security:
 * - HTTPS base URLs only (validated by [ProfileBaseUrlValidator]);
 * - the session ID is added as exactly one encoded path segment;
 * - the bearer key is only ever materialized inside
 *   [SecretValue.useRaw] and placed in the `Authorization` header;
 * - redirects and connection retries are disabled;
 * - the response body is bounded (8 MiB: real Telegram sessions with
 *   tool output exceed 512 KiB, so this is the transport ceiling, not a
 *   content allowance);
 * - every failure maps to a closed [MessageHistoryError] set; no
 *   response body, exception text, URL, key or message content ever
 *   appears in a result, error or log.
 *
 * Contract notes:
 * - `messages.timestamp` comes from a SQLite `REAL` column, so finite
 *   non-negative fractions are accepted and safely floored to whole
 *   seconds for the UI;
 * - pagination numbers (`limit`, `offset`, `returned`) must be exact
 *   non-negative integers and `order` must be the echoed `latest`;
 * - the read pages back from the newest messages (`order=latest`), so a
 *   long session yields its current state instead of its beginning.
 *   Hermes `get_messages(..., latest=True)` selects the newest rows and
 *   reverses them back into chronological insertion order, so the page
 *   is kept exactly as the server sends it;
 * - [offset] is the number of raw server rows to skip at the newest end,
 *   so `offset = 200` returns the page just before the newest 200 rows.
 *   It must be non-negative; `0` keeps the previous behaviour (the newest
 *   page). The offset is echoed in the query in both cases;
 * - `pagination.limit` and `.returned` are kept on the domain
 *   page. `returned` counts the raw server rows of the page (including
 *   hidden entries), which is what the next offset advances by;
 * - `display_kind == "hidden"` entries are dropped before the domain
 *   page is built; duplicate visible message IDs are rejected.
 */
class HermesMessageClient(
    okHttpClient: OkHttpClient = defaultMessageHttpClient()
) : MessageFetcher {

    private val client = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override fun fetch(
        baseUrl: String,
        apiKey: SecretValue,
        sessionId: String,
        offset: Int
    ): MessageHistoryResult {
        if (!sessionId.trim().isNotEmpty()) {
            return MessageHistoryResult.Failure(MessageHistoryError.InvalidInput)
        }
        if (offset < 0) {
            return MessageHistoryResult.Failure(MessageHistoryError.InvalidInput)
        }
        val keyIsSafe = apiKey.useRaw { rawKey ->
            rawKey.length in 1..MAX_API_KEY_CHARACTERS &&
                rawKey.none { character ->
                    character.isWhitespace() || character.isISOControl()
                }
        }
        if (!keyIsSafe) {
            return MessageHistoryResult.Failure(MessageHistoryError.InvalidInput)
        }
        val base = ProfileBaseUrlValidator.validate(baseUrl).getOrNull()
            ?: return MessageHistoryResult.Failure(MessageHistoryError.InvalidInput)

        val url = base.toHttpUrl().newBuilder()
            .addPathSegment(API_SEGMENT)
            .addPathSegment(SESSIONS_SEGMENT)
            .addPathSegment(sessionId)
            .addPathSegment(MESSAGES_SEGMENT)
            .addQueryParameter(LIMIT_FIELD, DEFAULT_LIMIT.toString())
            .addQueryParameter(OFFSET_FIELD, offset.toString())
            .addQueryParameter(ORDER_FIELD, ORDER_LATEST)
            .build()
        val request = apiKey.useRaw { rawKey ->
            Request.Builder()
                .url(url)
                .get()
                .header(AUTHORIZATION_HEADER, "$BEARER_PREFIX$rawKey")
                .build()
        }

        return try {
            val page = client.newCall(request).execute().use { response ->
                requireSuccessful(response)
                parsePage(readBoundedUtf8(response.body))
            }
            MessageHistoryResult.Success(page)
        } catch (error: PageFailure) {
            MessageHistoryResult.Failure(error.reason)
        } catch (_: SocketTimeoutException) {
            MessageHistoryResult.Failure(MessageHistoryError.Timeout)
        } catch (_: SSLException) {
            MessageHistoryResult.Failure(MessageHistoryError.Tls)
        } catch (_: InterruptedIOException) {
            MessageHistoryResult.Failure(MessageHistoryError.Timeout)
        } catch (error: IOException) {
            MessageHistoryResult.Failure(if (error.hasTlsFailure()) MessageHistoryError.Tls else MessageHistoryError.Network)
        } catch (_: Exception) {
            MessageHistoryResult.Failure(MessageHistoryError.IncompatibleApi)
        }
    }

    private fun requireSuccessful(response: Response) {
        when (response.code) {
            in 200..299 -> return
            401, 403 -> throw PageFailure(MessageHistoryError.InvalidCredentials)
            404 -> throw PageFailure(MessageHistoryError.SessionNotFound)
            429 -> throw PageFailure(MessageHistoryError.RateLimited)
            else -> throw PageFailure(MessageHistoryError.Http)
        }
    }

    private fun readBoundedUtf8(body: ResponseBody?): String {
        if (body == null || body.contentLength() > MAX_RESPONSE_BYTES) {
            throw PageFailure(MessageHistoryError.IncompatibleApi)
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        body.byteStream().use { input ->
            var total = 0
            while (true) {
                val remaining = MAX_RESPONSE_BYTES + 1 - total
                if (remaining <= 0) {
                    throw PageFailure(MessageHistoryError.IncompatibleApi)
                }
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read == -1) break
                output.write(buffer, 0, read)
                total += read
                if (total > MAX_RESPONSE_BYTES) {
                    throw PageFailure(MessageHistoryError.IncompatibleApi)
                }
            }
        }
        val bytes = output.toByteArray()
        val text = try {
            decodeUtf8Strict(bytes)
        } finally {
            bytes.fill(0)
            buffer.fill(0)
        }
        return text ?: throw PageFailure(MessageHistoryError.IncompatibleApi)
    }

    /**
     * Strict contract check of `GET /api/sessions/{id}/messages`:
     * `object == "list"`, non-empty string `session_id`, `data` is a JSON
     * array, pagination `limit`/`offset`/`returned` are non-negative
     * integers and `order` is the echoed `latest`. Every message entry has
     * a positive integral `id`, a known role, a string `content`
     * and optional typed fields. Server order is preserved (Hermes already
     * returns a `latest` page chronologically).
     */
    private fun parsePage(text: String): MessagePage {
        val root: JSONObject = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw PageFailure(MessageHistoryError.IncompatibleApi)
        }
        if (strictString(root, OBJECT_FIELD) != LIST_OBJECT) {
            throw PageFailure(MessageHistoryError.IncompatibleApi)
        }
        strictNonBlankString(root, SESSION_ID_FIELD)
        val data = root.opt(DATA_FIELD) as? JSONArray
            ?: throw PageFailure(MessageHistoryError.IncompatibleApi)
        val pagination = parsePagination(root, data.length())

        val messages = ArrayList<HermesChatMessage>(data.length())
        val seenVisibleIds = HashSet<Long>(data.length())
        for (index in 0 until data.length()) {
            val item = data.opt(index) as? JSONObject
                ?: throw PageFailure(MessageHistoryError.IncompatibleApi)
            val displayKind = optionalString(item, DISPLAY_KIND_FIELD)
            if (displayKind == HIDDEN_DISPLAY_KIND) {
                continue
            }
            val id = strictPositiveInt(item, ID_FIELD)
            if (!seenVisibleIds.add(id)) {
                throw PageFailure(MessageHistoryError.IncompatibleApi)
            }
            messages += HermesChatMessage(
                id = id,
                role = strictRole(item, ROLE_FIELD),
                content = strictString(item, CONTENT_FIELD),
                timestampSeconds = optionalRealSeconds(item, TIMESTAMP_FIELD),
                toolName = optionalNonBlankString(item, TOOL_NAME_FIELD)
            )
        }
        return MessagePage(
            messages = messages,
            limit = pagination.limit,
            returned = pagination.returned
        )
    }

    private fun parsePagination(root: JSONObject, dataLength: Int): PaginationMeta {
        val pagination = root.opt(PAGINATION_FIELD) as? JSONObject
            ?: throw PageFailure(MessageHistoryError.IncompatibleApi)
        val limit = strictNonNegativeInt(pagination, LIMIT_FIELD)
        strictNonNegativeInt(pagination, OFFSET_FIELD)
        if (strictString(pagination, ORDER_FIELD) != ORDER_LATEST) {
            throw PageFailure(MessageHistoryError.IncompatibleApi)
        }
        val returned = strictNonNegativeInt(pagination, RETURNED_FIELD)
        if (returned != dataLength.toLong()) {
            throw PageFailure(MessageHistoryError.IncompatibleApi)
        }
        return PaginationMeta(
            limit = limit.toBoundedInt(),
            returned = returned.toBoundedInt()
        )
    }

    private fun Long.toBoundedInt(): Int =
        takeIf { it <= Int.MAX_VALUE }?.toInt()
            ?: throw PageFailure(MessageHistoryError.IncompatibleApi)

    private data class PaginationMeta(
        val limit: Int,
        val returned: Int
    )

    private fun strictString(json: JSONObject, key: String): String =
        json.opt(key) as? String
            ?: throw PageFailure(MessageHistoryError.IncompatibleApi)

    private fun strictNonBlankString(json: JSONObject, key: String): String =
        strictString(json, key).takeIf { it.isNotEmpty() && isSafeServerString(it) }
            ?: throw PageFailure(MessageHistoryError.IncompatibleApi)

    private fun strictRole(json: JSONObject, key: String): MessageRole {
        val role = strictString(json, key)
        return when (role) {
            ROLE_USER -> MessageRole.USER
            ROLE_ASSISTANT -> MessageRole.ASSISTANT
            ROLE_SYSTEM -> MessageRole.SYSTEM
            ROLE_TOOL -> MessageRole.TOOL
            else -> throw PageFailure(MessageHistoryError.IncompatibleApi)
        }
    }

    /**
     * Pagination counter: the value must be an exact non-negative
     * integer; fractions are rejected (unlike `REAL` timestamps).
     */
    private fun strictNonNegativeInt(json: JSONObject, key: String): Long {
        val value = json.opt(key) as? Number
            ?: throw PageFailure(MessageHistoryError.IncompatibleApi)
        val decimal = try {
            BigDecimal(value.toString())
        } catch (_: NumberFormatException) {
            throw PageFailure(MessageHistoryError.IncompatibleApi)
        }
        if (decimal.signum() < 0 || decimal.stripTrailingZeros().scale() > 0) {
            throw PageFailure(MessageHistoryError.IncompatibleApi)
        }
        return try {
            decimal.longValueExact()
        } catch (_: ArithmeticException) {
            throw PageFailure(MessageHistoryError.IncompatibleApi)
        }
    }

    private fun strictPositiveInt(json: JSONObject, key: String): Long =
        strictNonNegativeInt(json, key).takeIf { it > 0 }
            ?: throw PageFailure(MessageHistoryError.IncompatibleApi)

    /**
     * SQLite `REAL` epoch seconds: a finite, non-negative number is
     * accepted and safely floored to whole seconds. Fractions, whole
     * numbers and `Long`-range boundaries are handled through
     * [BigDecimal] so no floating-point wraparound or rounding-up can
     * occur.
     */
    private fun optionalRealSeconds(json: JSONObject, key: String): Long? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.opt(key) as? Number
            ?: throw PageFailure(MessageHistoryError.IncompatibleApi)
        val decimal = try {
            BigDecimal(value.toString())
        } catch (_: NumberFormatException) {
            throw PageFailure(MessageHistoryError.IncompatibleApi)
        }
        if (decimal.signum() < 0 || decimal > LONG_MAX_DECIMAL) {
            throw PageFailure(MessageHistoryError.IncompatibleApi)
        }
        return try {
            decimal.setScale(0, RoundingMode.FLOOR).longValueExact()
        } catch (_: ArithmeticException) {
            throw PageFailure(MessageHistoryError.IncompatibleApi)
        }
    }

    private fun optionalNonBlankString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return strictNonBlankString(json, key)
    }

    private fun optionalString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        return json.opt(key) as? String
            ?: throw PageFailure(MessageHistoryError.IncompatibleApi)
    }

    private class PageFailure(val reason: MessageHistoryError) : Exception()

    private companion object {
        /**
         * Telegram sessions with tool output exceed 512 KiB (a real one
         * delivered 776 233 bytes); 8 MiB keeps the read bounded while no
         * longer discarding a legitimate history page.
         */
        const val MAX_RESPONSE_BYTES = 8 * 1024 * 1024
        const val MAX_API_KEY_CHARACTERS = 4096
        const val BUFFER_SIZE = 8 * 1024
        const val DEFAULT_LIMIT = 200
        const val API_SEGMENT = "api"
        const val SESSIONS_SEGMENT = "sessions"
        const val MESSAGES_SEGMENT = "messages"
        const val LIMIT_FIELD = "limit"
        const val OFFSET_FIELD = "offset"
        const val ORDER_FIELD = "order"
        const val ORDER_LATEST = "latest"
        const val RETURNED_FIELD = "returned"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        const val LIST_OBJECT = "list"
        const val OBJECT_FIELD = "object"
        const val SESSION_ID_FIELD = "session_id"
        const val DATA_FIELD = "data"
        const val PAGINATION_FIELD = "pagination"
        const val ID_FIELD = "id"
        const val ROLE_FIELD = "role"
        const val CONTENT_FIELD = "content"
        const val TIMESTAMP_FIELD = "timestamp"
        const val TOOL_NAME_FIELD = "tool_name"
        const val DISPLAY_KIND_FIELD = "display_kind"
        const val HIDDEN_DISPLAY_KIND = "hidden"
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val ROLE_SYSTEM = "system"
        const val ROLE_TOOL = "tool"
        val LONG_MAX_DECIMAL: BigDecimal = BigDecimal.valueOf(Long.MAX_VALUE)

        fun defaultMessageHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}
