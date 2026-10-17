package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.session.SessionListError
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionPage
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageFetcher
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
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
 * Performs the single authenticated `GET /api/sessions` read.
 * This class is the transport only; profile lookup and
 * credential access happen in the repository layer, so the key never
 * reaches this class except as the scoped [SecretValue] argument.
 *
 * Security:
 * - HTTPS base URLs only (validated by [ProfileBaseUrlValidator]);
 * - the bearer key is only ever materialized inside
 *   [SecretValue.useRaw] and placed in the `Authorization` header;
 * - redirects and connection retries are disabled;
 * - the response body is bounded;
 * - every failure maps to a closed [SessionListError] set; no response
 *   body, exception text, URL, key or session data ever appears in a
 *   result, error or log.
 */
class HermesSessionClient(
    okHttpClient: OkHttpClient = defaultSessionHttpClient()
) : SessionPageFetcher {

    private val client = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override fun fetchPage(
        baseUrl: String,
        apiKey: SecretValue,
        limit: Int,
        offset: Int
    ): SessionListResult {
        if (limit !in LIMIT_RANGE || offset < 0) {
            return SessionListResult.Failure(SessionListError.InvalidInput)
        }
        val keyIsSafe = apiKey.useRaw { rawKey ->
            rawKey.length in 1..MAX_API_KEY_CHARACTERS &&
                rawKey.none { character ->
                    character.isWhitespace() || character.isISOControl()
                }
        }
        if (!keyIsSafe) {
            return SessionListResult.Failure(SessionListError.InvalidInput)
        }
        val base = ProfileBaseUrlValidator.validate(baseUrl).getOrNull()
            ?: return SessionListResult.Failure(SessionListError.InvalidInput)

        val url = base.toHttpUrl().newBuilder()
            .addPathSegment(API_SEGMENT)
            .addPathSegment(SESSIONS_SEGMENT)
            .addQueryParameter(LIMIT_FIELD, limit.toString())
            .addQueryParameter(OFFSET_FIELD, offset.toString())
            .addQueryParameter(INCLUDE_CHILDREN_FIELD, "false")
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
            SessionListResult.Success(page)
        } catch (error: PageFailure) {
            SessionListResult.Failure(error.reason)
        } catch (_: SocketTimeoutException) {
            SessionListResult.Failure(SessionListError.Timeout)
        } catch (_: SSLException) {
            SessionListResult.Failure(SessionListError.Tls)
        } catch (_: InterruptedIOException) {
            SessionListResult.Failure(SessionListError.Timeout)
        } catch (error: IOException) {
            SessionListResult.Failure(if (error.hasTlsFailure()) SessionListError.Tls else SessionListError.Network)
        } catch (_: Exception) {
            SessionListResult.Failure(SessionListError.IncompatibleApi)
        }
    }

    private fun requireSuccessful(response: Response) {
        when (response.code) {
            in 200..299 -> return
            401, 403 -> throw PageFailure(SessionListError.InvalidCredentials)
            429 -> throw PageFailure(SessionListError.RateLimited)
            else -> throw PageFailure(SessionListError.Http)
        }
    }

    private fun readBoundedUtf8(body: ResponseBody?): String {
        if (body == null || body.contentLength() > MAX_RESPONSE_BYTES) {
            throw PageFailure(SessionListError.IncompatibleApi)
        }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(BUFFER_SIZE)
        body.byteStream().use { input ->
            var total = 0
            while (true) {
                val remaining = MAX_RESPONSE_BYTES + 1 - total
                if (remaining <= 0) {
                    throw PageFailure(SessionListError.IncompatibleApi)
                }
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (read == -1) break
                output.write(buffer, 0, read)
                total += read
                if (total > MAX_RESPONSE_BYTES) {
                    throw PageFailure(SessionListError.IncompatibleApi)
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
        return text ?: throw PageFailure(SessionListError.IncompatibleApi)
    }

    /**
     * Strict contract check of `GET /api/sessions`:
     * `object == "list"`, `data` is a JSON array, `limit`/`offset` are
     * non-negative integers, `has_more` is a boolean, and every session
     * entry has a non-empty string `id` plus the optional typed fields.
     * Server order is preserved.
     */
    private fun parsePage(text: String): SessionPage {
        val root: JSONObject = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw PageFailure(SessionListError.IncompatibleApi)
        }
        if (strictString(root, OBJECT_FIELD) != LIST_OBJECT) {
            throw PageFailure(SessionListError.IncompatibleApi)
        }
        strictNonNegativeInt(root, LIMIT_FIELD)
        strictNonNegativeInt(root, OFFSET_FIELD)
        val hasMore = strictBoolean(root, HAS_MORE_FIELD)
        val data = root.opt(DATA_FIELD) as? JSONArray
            ?: throw PageFailure(SessionListError.IncompatibleApi)
        val sessions = ArrayList<HermesSession>(data.length())
        val seenIds = HashSet<String>(data.length())
        for (index in 0 until data.length()) {
            val item = data.opt(index) as? JSONObject
                ?: throw PageFailure(SessionListError.IncompatibleApi)
            val id = strictNonBlankString(item, ID_FIELD)
            if (!seenIds.add(id)) {
                throw PageFailure(SessionListError.IncompatibleApi)
            }
            sessions += HermesSession(
                id = id,
                title = optionalString(item, TITLE_FIELD),
                source = optionalString(item, SOURCE_FIELD),
                lastActiveEpochSeconds = optionalRealSeconds(item, LAST_ACTIVE_FIELD),
                messageCount = optionalNonNegativeInt(item, MESSAGE_COUNT_FIELD),
                pinned = optionalBoolean(item, PINNED_FIELD)
            )
        }
        return SessionPage(sessions = sessions, hasMore = hasMore)
    }

    private fun strictString(json: JSONObject, key: String): String =
        json.opt(key) as? String
            ?: throw PageFailure(SessionListError.IncompatibleApi)

    private fun strictNonBlankString(json: JSONObject, key: String): String =
        strictString(json, key).takeIf { it.isNotEmpty() && isSafeServerString(it) }
            ?: throw PageFailure(SessionListError.IncompatibleApi)

    private fun strictBoolean(json: JSONObject, key: String): Boolean =
        json.opt(key) as? Boolean
            ?: throw PageFailure(SessionListError.IncompatibleApi)

    private fun strictNonNegativeInt(json: JSONObject, key: String): Long {
        val value = json.opt(key) as? Number
            ?: throw PageFailure(SessionListError.IncompatibleApi)
        return nonNegativeLong(value)
    }

    private fun nonNegativeLong(value: Number): Long {
        val decimal = try {
            BigDecimal(value.toString())
        } catch (_: NumberFormatException) {
            throw PageFailure(SessionListError.IncompatibleApi)
        }
        if (decimal.signum() < 0 || decimal.stripTrailingZeros().scale() > 0) {
            throw PageFailure(SessionListError.IncompatibleApi)
        }
        return try {
            decimal.longValueExact()
        } catch (_: ArithmeticException) {
            throw PageFailure(SessionListError.IncompatibleApi)
        }
    }

    private fun optionalString(json: JSONObject, key: String): String? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.opt(key) as? String
            ?: throw PageFailure(SessionListError.IncompatibleApi)
        // An unsafe optional display string is dropped, never rendered.
        return value.takeIf { isSafeServerString(it) }
    }

    private fun optionalBoolean(json: JSONObject, key: String): Boolean {
        if (!json.has(key) || json.isNull(key)) return false
        return json.opt(key) as? Boolean
            ?: throw PageFailure(SessionListError.IncompatibleApi)
    }

    private fun optionalNonNegativeInt(json: JSONObject, key: String): Long? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.opt(key) as? Number
            ?: throw PageFailure(SessionListError.IncompatibleApi)
        return nonNegativeLong(value)
    }

    /**
     * SQLite `REAL` epoch seconds (reviewer-confirmed for
     * `sessions.last_active`): a finite, non-negative number is accepted
     * and safely floored to whole seconds. Handled through [BigDecimal]
     * so no floating-point wraparound or rounding-up can occur.
     */
    private fun optionalRealSeconds(json: JSONObject, key: String): Long? {
        if (!json.has(key) || json.isNull(key)) return null
        val value = json.opt(key) as? Number
            ?: throw PageFailure(SessionListError.IncompatibleApi)
        val decimal = try {
            BigDecimal(value.toString())
        } catch (_: NumberFormatException) {
            throw PageFailure(SessionListError.IncompatibleApi)
        }
        if (decimal.signum() < 0 || decimal > LONG_MAX_DECIMAL) {
            throw PageFailure(SessionListError.IncompatibleApi)
        }
        return try {
            decimal.setScale(0, RoundingMode.FLOOR).longValueExact()
        } catch (_: ArithmeticException) {
            throw PageFailure(SessionListError.IncompatibleApi)
        }
    }

    private class PageFailure(val reason: SessionListError) : Exception()

    private companion object {
        val LIMIT_RANGE = 1..50
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val MAX_API_KEY_CHARACTERS = 4096
        const val BUFFER_SIZE = 8 * 1024
        const val API_SEGMENT = "api"
        const val SESSIONS_SEGMENT = "sessions"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        const val LIST_OBJECT = "list"
        const val OBJECT_FIELD = "object"
        const val DATA_FIELD = "data"
        const val LIMIT_FIELD = "limit"
        const val OFFSET_FIELD = "offset"
        const val HAS_MORE_FIELD = "has_more"
        const val INCLUDE_CHILDREN_FIELD = "include_children"
        const val ID_FIELD = "id"
        const val TITLE_FIELD = "title"
        const val SOURCE_FIELD = "source"
        const val LAST_ACTIVE_FIELD = "last_active"
        const val MESSAGE_COUNT_FIELD = "message_count"
        const val PINNED_FIELD = "pinned"
        val LONG_MAX_DECIMAL: BigDecimal = BigDecimal.valueOf(Long.MAX_VALUE)

        fun defaultSessionHttpClient(): OkHttpClient = OkHttpClient.Builder()
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
