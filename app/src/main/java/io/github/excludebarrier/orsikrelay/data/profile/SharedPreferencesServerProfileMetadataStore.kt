package io.github.excludebarrier.orsikrelay.data.profile

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import org.json.JSONArray
import org.json.JSONObject

/**
 * Stores only non-secret profile metadata in one versioned JSON envelope.
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
            rawVersion.toInt() != FORMAT_VERSION ||
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
            if (
                item.length() != PROFILE_FIELD_COUNT ||
                rawId !is String ||
                rawName !is String ||
                rawBaseUrl !is String
            ) {
                throw ProfileMetadataStoreException()
            }
            val profile = ServerProfile.create(
                id = rawId,
                profileName = rawName,
                baseUrl = rawBaseUrl
            )
            if (!ids.add(profile.id)) throw ProfileMetadataStoreException()
            profiles += profile
        }
        return profiles.sortedBy { it.id }
    }

    private fun writeProfiles(profiles: List<ServerProfile>) {
        val array = JSONArray()
        profiles.sortedBy { it.id }.forEach { profile ->
            array.put(
                JSONObject()
                    .put(ID_FIELD, profile.id)
                    .put(NAME_FIELD, profile.profileName)
                    .put(BASE_URL_FIELD, profile.baseUrl)
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
        private const val FORMAT_VERSION = 1
        private const val VERSION_FIELD = "version"
        private const val PROFILES_FIELD = "profiles"
        private const val ID_FIELD = "id"
        private const val NAME_FIELD = "profile_name"
        private const val BASE_URL_FIELD = "base_url"
        private const val ENVELOPE_FIELD_COUNT = 2
        private const val PROFILE_FIELD_COUNT = 3
    }
}
