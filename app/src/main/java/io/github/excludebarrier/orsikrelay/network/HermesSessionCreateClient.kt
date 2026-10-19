package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateError
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateFetcher
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateResult
import java.io.IOException
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLException
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject

/**
 * Performs the single authenticated `POST /api/sessions` write.
 * This class is the transport only; profile lookup and
 * credential access happen in the repository layer, so the key never
 * reaches this class except as the scoped [SecretValue] argument.
 *
 * Security:
 * - HTTPS base URLs only (validated by [ProfileBaseUrlValidator]); an
 *   existing path prefix of the base URL is preserved;
 * - the bearer key is only ever materialized inside
 *   [SecretValue.useRaw] and placed in the `Authorization` header;
 * - the body is either exactly `{}` (no title) or a single `title`
 *   field, nothing else;
 * - redirects and connection retries are disabled; the POST is never
 *   repeated automatically;
 * - the response body is bounded;
 * - every failure maps to a closed [SessionCreateError] set; no
 *   response body, exception text, URL, key, title or session ID ever
 *   appears in a result, error or log.
 *
 * Strict success contract (HTTP 201 only):
 * - the root is a JSON object;
 * - `object` is exactly `hermes.session`;
 * - `session` is a JSON object;
 * - `session.id` is a non-empty string of at most 256 characters
 *   without ISO control characters;
 * - `session.title`, when present and not `null`, must be a string.
 * Any other client-safe server field is ignored; preview, content or
 * prompt data are never read, logged or propagated.
 */
class HermesSessionCreateClient(
    okHttpClient: OkHttpClient = HermesHttp.defaultClient()
) : SessionCreateFetcher {

    private val client = okHttpClient.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    override fun createSession(
        baseUrl: String,
        apiKey: SecretValue,
        title: String?
    ): SessionCreateResult {
        val base = ProfileBaseUrlValidator.validate(baseUrl).getOrNull()
            ?: return SessionCreateResult.Failure(SessionCreateError.InvalidInput)
        if (!isSafeApiKey(apiKey)) {
            return SessionCreateResult.Failure(SessionCreateError.InvalidInput)
        }
        val trimmedTitle = title?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedTitle != null &&
            (trimmedTitle.length > MAX_TITLE_LENGTH || trimmedTitle.any { it.isISOControl() })
        ) {
            return SessionCreateResult.Failure(SessionCreateError.InvalidInput)
        }
        val bodyJson = if (trimmedTitle == null) {
            EMPTY_BODY_JSON
        } else {
            try {
                JSONObject().put(TITLE_FIELD, trimmedTitle).toString()
            } catch (_: Exception) {
                return SessionCreateResult.Failure(SessionCreateError.InvalidInput)
            }
        }

        val url = base.toHttpUrl().newBuilder()
            .addPathSegment(API_SEGMENT)
            .addPathSegment(SESSIONS_SEGMENT)
            .build()
        val request = apiKey.useRaw { rawKey ->
            Request.Builder()
                .url(url)
                .post(bodyJson.toByteArray().toRequestBody(CONTENT_TYPE))
                .header(AUTHORIZATION_HEADER, "$BEARER_PREFIX$rawKey")
                .header(CONTENT_TYPE_HEADER, CONTENT_TYPE_HEADER_VALUE)
                .build()
        }

        return try {
            val created = client.newCall(request).execute().use { response ->
                requireCreated(response)
                parseCreated(
                    readBoundedUtf8(response.body, MAX_RESPONSE_BYTES) {
                        throw CreateFailure(SessionCreateError.IncompatibleApi)
                    }
                )
            }
            SessionCreateResult.Success(created.first, created.second)
        } catch (error: CreateFailure) {
            SessionCreateResult.Failure(error.reason)
        } catch (_: SocketTimeoutException) {
            SessionCreateResult.Failure(SessionCreateError.Timeout)
        } catch (_: SSLException) {
            SessionCreateResult.Failure(SessionCreateError.Tls)
        } catch (_: InterruptedIOException) {
            SessionCreateResult.Failure(SessionCreateError.Timeout)
        } catch (error: IOException) {
            SessionCreateResult.Failure(if (error.hasTlsFailure()) SessionCreateError.Tls else SessionCreateError.Network)
        } catch (_: Exception) {
            SessionCreateResult.Failure(SessionCreateError.IncompatibleApi)
        }
    }

    /** Only HTTP 201 is a success; every other code is a closed reason. */
    private fun requireCreated(response: Response) {
        when (response.code) {
            CREATED_CODE -> return
            BAD_REQUEST_CODE -> throw CreateFailure(SessionCreateError.InvalidTitle)
            401, 403 -> throw CreateFailure(SessionCreateError.InvalidCredentials)
            CONFLICT_CODE -> throw CreateFailure(SessionCreateError.Conflict)
            RATE_LIMITED_CODE -> throw CreateFailure(SessionCreateError.RateLimited)
            else -> throw CreateFailure(SessionCreateError.Http)
        }
    }

    /**
     * Strict contract check of the `POST /api/sessions` success answer:
     * object root, `object == "hermes.session"`, object `session`,
     * non-empty string `session.id` (max 256 chars, no ISO control
     * characters) and `session.title` absent, `null` or string. Returns
     * only the created session ID and the optional server-echoed title;
     * no other server field is propagated.
     */
    private fun parseCreated(text: String): Pair<String, String?> {
        val root = try {
            JSONObject(text)
        } catch (_: Exception) {
            throw CreateFailure(SessionCreateError.IncompatibleApi)
        }
        if (root.opt(OBJECT_FIELD) != SESSION_OBJECT) {
            throw CreateFailure(SessionCreateError.IncompatibleApi)
        }
        val session = root.opt(SESSION_FIELD) as? JSONObject
            ?: throw CreateFailure(SessionCreateError.IncompatibleApi)
        val id = session.opt(ID_FIELD) as? String
            ?: throw CreateFailure(SessionCreateError.IncompatibleApi)
        if (id.isEmpty() || id.length > MAX_SESSION_ID_LENGTH) {
            throw CreateFailure(SessionCreateError.IncompatibleApi)
        }
        if (id.any { it.isISOControl() }) {
            throw CreateFailure(SessionCreateError.IncompatibleApi)
        }
        val title = if (session.has(TITLE_FIELD) && !session.isNull(TITLE_FIELD)) {
            session.opt(TITLE_FIELD) as? String
                ?: throw CreateFailure(SessionCreateError.IncompatibleApi)
        } else {
            null
        }
        if (title != null &&
            (title.length > MAX_TITLE_LENGTH || title.any { it.isISOControl() })
        ) {
            throw CreateFailure(SessionCreateError.IncompatibleApi)
        }
        return id to title
    }

    private class CreateFailure(val reason: SessionCreateError) : Exception()

    private companion object {
        const val MAX_RESPONSE_BYTES = 512 * 1024
        const val API_SEGMENT = "api"
        const val SESSIONS_SEGMENT = "sessions"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        const val CONTENT_TYPE_HEADER = "Content-Type"
        const val CONTENT_TYPE_HEADER_VALUE = "application/json"
        val CONTENT_TYPE = "application/json".toMediaType()
        const val SESSION_OBJECT = "hermes.session"
        const val OBJECT_FIELD = "object"
        const val SESSION_FIELD = "session"
        const val ID_FIELD = "id"
        const val TITLE_FIELD = "title"
        const val MAX_SESSION_ID_LENGTH = 256
        const val MAX_TITLE_LENGTH = 512
        const val EMPTY_BODY_JSON = "{}"
        const val CREATED_CODE = 201
        const val BAD_REQUEST_CODE = 400
        const val CONFLICT_CODE = 409
        const val RATE_LIMITED_CODE = 429
    }
}
