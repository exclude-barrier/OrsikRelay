package io.github.excludebarrier.orsikrelay.network

import io.github.excludebarrier.orsikrelay.domain.chat.SlashCommand
import io.github.excludebarrier.orsikrelay.domain.chat.SlashCommandResult
import io.github.excludebarrier.orsikrelay.domain.chat.SessionModelOption
import io.github.excludebarrier.orsikrelay.domain.chat.ModelOptionsResult
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/** Bounded REST equivalents. No redirects, retries, or upstream diagnostics. */
class HermesSlashClient(okHttpClient: OkHttpClient = HermesHttp.defaultClient()) {
    private val client = okHttpClient.newBuilder().followRedirects(false)
        .followSslRedirects(false).retryOnConnectionFailure(false).build()

    fun available(baseUrl: String, key: SecretValue): Set<SlashCommand> {
        return try {
            val root = read(baseUrl, key, listOf("v1", "capabilities")) ?: return emptySet()
            val endpoints = root.optJSONObject("endpoints") ?: return emptySet()
            SlashCommand.entries.filter { command ->
                val endpoint = endpoints.optJSONObject(command.endpoint)
                endpoint?.opt("method") == command.method && endpoint.opt("path") == command.path &&
                    // Older bridges accept only archived on PATCH, never title.
                    (command != SlashCommand.TITLE || root.optJSONObject("features")?.opt("session_title") == true) &&
                    (command != SlashCommand.MODEL || endpoints.optJSONObject("model_options")?.let {
                        it.opt("method") == "GET" && it.opt("path") == "/api/model/options"
                    } == true)
            }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
    }

    fun fork(baseUrl: String, key: SecretValue, sessionId: String): SlashCommandResult {
        if (!isSafeOpaqueId(sessionId)) return SlashCommandResult.Failed
        return try {
            val root = exchange(baseUrl, key, listOf("api", "sessions", sessionId, "fork"), "POST", JSONObject(), 201)
                ?: return SlashCommandResult.Failed
            val session = root.optJSONObject("session") ?: return SlashCommandResult.Failed
            val id = session.opt("id") as? String ?: return SlashCommandResult.Failed
            if (root.opt("object") != "hermes.session" || !isSafeOpaqueId(id) || id == sessionId ||
                session.opt("parent_session_id") != sessionId) return SlashCommandResult.Failed
            SlashCommandResult.OpenSession(id)
        } catch (_: Exception) { SlashCommandResult.Failed }
    }

    fun models(baseUrl: String, key: SecretValue): ModelOptionsResult {
        return try {
            val root = read(baseUrl, key, listOf("api", "model", "options")) ?: return ModelOptionsResult.Failed
            val providers = root.optJSONArray("providers") ?: return ModelOptionsResult.Failed
            val options = mutableListOf<SessionModelOption>()
            for (i in 0 until providers.length()) {
                val row = providers.optJSONObject(i) ?: return ModelOptionsResult.Failed
                val provider = row.opt("slug") as? String ?: return ModelOptionsResult.Failed
                val models = row.optJSONArray("models") ?: return ModelOptionsResult.Failed
                for (j in 0 until models.length()) {
                    val model = models.opt(j) as? String ?: return ModelOptionsResult.Failed
                    val option = SessionModelOption(provider, model)
                    if (!option.isValid() || options.size >= 4096) return ModelOptionsResult.Failed
                    options += option
                }
            }
            ModelOptionsResult.Loaded(options.distinct())
        } catch (_: Exception) { ModelOptionsResult.Failed }
    }

    fun lockModel(baseUrl: String, key: SecretValue, sessionId: String, option: SessionModelOption): SlashCommandResult {
        if (!isSafeOpaqueId(sessionId) || !option.isValid()) return SlashCommandResult.Failed
        return try {
            val body = JSONObject().put("provider", option.provider).put("model", option.model)
            val root = exchange(baseUrl, key, listOf("api", "sessions", sessionId, "model"), "POST", body)
                ?: return SlashCommandResult.Failed
            val runtime = root.optJSONObject("runtime") ?: return SlashCommandResult.Failed
            val requested = runtime.optJSONObject("requested") ?: return SlashCommandResult.Failed
            if (root.opt("object") != "hermes.session.model_lock" || root.opt("session_id") != sessionId ||
                runtime.opt("model_lock") != "accepted" || requested.opt("provider") != option.provider ||
                requested.opt("model") != option.model) return SlashCommandResult.Failed
            SlashCommandResult.Done
        } catch (_: Exception) { SlashCommandResult.Failed }
    }

    private fun read(baseUrl: String, key: SecretValue, segments: List<String>): JSONObject? =
        exchange(baseUrl, key, segments)

    private fun exchange(baseUrl: String, key: SecretValue, segments: List<String>, method: String = "GET",
                         body: JSONObject? = null, status: Int = 200): JSONObject? {
        if (!isSafeApiKey(key)) return null
        val base = ProfileBaseUrlValidator.validate(baseUrl).getOrNull()?.toHttpUrl() ?: return null
        val url = base.newBuilder().apply { segments.forEach { addPathSegment(it) } }.build()
        val request = key.useRaw { Request.Builder().url(url).header("Authorization", "Bearer $it")
            .method(method, body?.toString()?.toRequestBody("application/json".toMediaType())).build() }
        return client.newCall(request).execute().use { response ->
            if (response.code != status) return null
            requireJsonContentType(response) { return null }
            JSONObject(readBoundedUtf8(response.body, 512 * 1024) { return null })
        }
    }
}
