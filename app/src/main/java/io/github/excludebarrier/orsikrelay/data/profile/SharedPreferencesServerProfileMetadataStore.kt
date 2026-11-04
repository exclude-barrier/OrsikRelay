package io.github.excludebarrier.orsikrelay.data.profile

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionMode
import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionSettings
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores only non-secret profile metadata in one versioned JSON envelope.
 *
 * Version 2 adds each profile's [ConnectionSettings] (public endpoint, mode,
 * KeyChain alias, pinned server identity); version 1 envelopes are still
 * read and get the private-only default.
 *
 * The atomic `commit()` alone only makes the write atomic; the surrounding
 * read-modify-write in [save]/[delete] is additionally serialized by [lock],
 * so two concurrent updates can never lose one another.
 */
@SuppressLint("UseKtx")
class SharedPreferencesServerProfileMetadataStore(
    context: Context,
    preferencesName: String = DEFAULT_PREFERENCES_NAME
) : ServerProfileMetadataStore {

    private val lock = Any()
    private val preferences: SharedPreferences

    init {
        if (preferencesName.isBlank()) throw ProfileMetadataStoreException()
        preferences = try {
            context.applicationContext.getSharedPreferences(
                preferencesName,
                Context.MODE_PRIVATE
            )
        } catch (_: Exception) {
            throw ProfileMetadataStoreException()
        }
    }

    override fun save(profile: ServerProfile) {
        metadataCall {
            synchronized(lock) {
                val profiles = readProfiles().associateByTo(linkedMapOf()) { it.id }
                profiles[profile.id] = profile
                writeProfiles(profiles.values.sortedBy { it.id })
            }
        }
    }

    override fun get(profileId: String): ServerProfile? {
        validateProfileId(profileId)
        return metadataCall {
            readProfiles().firstOrNull { it.id == profileId }
        }
    }

    override fun list(): List<ServerProfile> = metadataCall {
        readProfiles().sortedBy { it.id }
    }

    override fun delete(profileId: String) {
        validateProfileId(profileId)
        metadataCall {
            synchronized(lock) {
                val current = readProfiles()
                if (current.any { it.id == profileId }) {
                    writeProfiles(current.filterNot { it.id == profileId })
                }
            }
        }
    }

    private fun readProfiles(): List<ServerProfile> {
        val encoded = preferences.getString(PAYLOAD_KEY, null) ?: return emptyList()
        val envelope = JSONObject(encoded)
        val rawVersion = envelope.opt(VERSION_FIELD)
        if (
            envelope.length() != ENVELOPE_FIELD_COUNT ||
            rawVersion !is Number ||
            rawVersion.toInt() !in LEGACY_FORMAT_VERSION..FORMAT_VERSION ||
            envelope.opt(PROFILES_FIELD) !is JSONArray
        ) {
            throw ProfileMetadataStoreException()
        }
        val array = envelope.optJSONArray(PROFILES_FIELD)
            ?: throw ProfileMetadataStoreException()
        val profiles = ArrayList<ServerProfile>(array.length())
        val ids = hashSetOf<String>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: throw ProfileMetadataStoreException()
            val rawId = item.opt(ID_FIELD)
            val rawName = item.opt(NAME_FIELD)
            val rawBaseUrl = item.opt(BASE_URL_FIELD)
            val legacy = rawVersion.toInt() == LEGACY_FORMAT_VERSION
            if (
                item.length() != (if (legacy) LEGACY_PROFILE_FIELD_COUNT else PROFILE_FIELD_COUNT) ||
                rawId !is String ||
                rawName !is String ||
                rawBaseUrl !is String
            ) {
                throw ProfileMetadataStoreException()
            }
            val connection = if (legacy) {
                ConnectionSettings.PRIVATE_ONLY
            } else {
                readConnection(item.opt(CONNECTION_FIELD) as? JSONObject)
            }
            val profile = ServerProfile.create(
                id = rawId,
                profileName = rawName,
                baseUrl = rawBaseUrl,
                connection = connection
            )
            if (!ids.add(profile.id)) throw ProfileMetadataStoreException()
            profiles += profile
        }
        return profiles.sortedBy { it.id }
    }

    private fun readConnection(json: JSONObject?): ConnectionSettings {
        if (json == null || json.length() != CONNECTION_FIELD_COUNT) {
            throw ProfileMetadataStoreException()
        }
        val mode = (json.opt(MODE_FIELD) as? String)
            ?.let { name -> ConnectionMode.entries.firstOrNull { it.name == name } }
            ?: throw ProfileMetadataStoreException()
        return ConnectionSettings.create(
            mode = mode,
            publicBaseUrl = optionalString(json, PUBLIC_BASE_URL_FIELD),
            clientCertAlias = optionalString(json, CLIENT_CERT_ALIAS_FIELD),
            serverId = optionalString(json, SERVER_ID_FIELD)
        )
    }

    /** A present key holding a string or JSON null; anything else is corrupt. */
    private fun optionalString(json: JSONObject, key: String): String? {
        if (!json.has(key)) throw ProfileMetadataStoreException()
        return when (val value = json.opt(key)) {
            JSONObject.NULL -> null
            is String -> value
            else -> throw ProfileMetadataStoreException()
        }
    }

    private fun writeProfiles(profiles: List<ServerProfile>) {
        val array = JSONArray()
        profiles.sortedBy { it.id }.forEach { profile ->
            array.put(
                JSONObject()
                    .put(ID_FIELD, profile.id)
                    .put(NAME_FIELD, profile.profileName)
                    .put(BASE_URL_FIELD, profile.baseUrl)
                    .put(
                        CONNECTION_FIELD,
                        JSONObject()
                            .put(MODE_FIELD, profile.connection.mode.name)
                            .put(PUBLIC_BASE_URL_FIELD, profile.connection.publicBaseUrl ?: JSONObject.NULL)
                            .put(CLIENT_CERT_ALIAS_FIELD, profile.connection.clientCertAlias ?: JSONObject.NULL)
                            .put(SERVER_ID_FIELD, profile.connection.serverId ?: JSONObject.NULL)
                    )
            )
        }
        val encoded = JSONObject()
            .put(VERSION_FIELD, FORMAT_VERSION)
            .put(PROFILES_FIELD, array)
            .toString()
        if (!preferences.edit().putString(PAYLOAD_KEY, encoded).commit()) {
            throw ProfileMetadataStoreException()
        }
    }

    private fun validateProfileId(profileId: String) {
        if (profileId.isBlank()) throw ProfileMetadataStoreException()
    }

    private fun <T> metadataCall(block: () -> T): T = try {
        block()
    } catch (error: ProfileMetadataStoreException) {
        throw error
    } catch (_: Exception) {
        throw ProfileMetadataStoreException()
    }

    companion object {
        internal const val DEFAULT_PREFERENCES_NAME = "hermes_server_profiles"
        internal const val PAYLOAD_KEY = "server_profiles"
        private const val LEGACY_FORMAT_VERSION = 1
        private const val FORMAT_VERSION = 2
        private const val VERSION_FIELD = "version"
        private const val PROFILES_FIELD = "profiles"
        private const val ID_FIELD = "id"
        private const val NAME_FIELD = "profile_name"
        private const val BASE_URL_FIELD = "base_url"
        private const val CONNECTION_FIELD = "connection"
        private const val MODE_FIELD = "mode"
        private const val PUBLIC_BASE_URL_FIELD = "public_base_url"
        private const val CLIENT_CERT_ALIAS_FIELD = "client_cert_alias"
        private const val SERVER_ID_FIELD = "server_id"
        private const val ENVELOPE_FIELD_COUNT = 2
        private const val LEGACY_PROFILE_FIELD_COUNT = 3
        private const val PROFILE_FIELD_COUNT = 4
        private const val CONNECTION_FIELD_COUNT = 4
    }
}
