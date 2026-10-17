package io.github.excludebarrier.orsikrelay.data.device

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-private [DeviceCredentialMetadataStore] on top of
 * [SharedPreferences].
 *
 * Stores ONLY the non-secret metadata of the paired device, one
 * versioned JSON record per profile, in a dedicated app-private
 * preferences file. The device key is NEVER stored here: it lives only
 * in the
 * [io.github.excludebarrier.orsikrelay.security.credential.CredentialStore]
 * (Android Keystore-wrapped) — same protection levels as the API key.
 *
 * The profile ID is digested (SHA-256, base64url) into the preference
 * key, mirroring the credential store's key derivation: the raw
 * profile ID never appears in the key, and no two profiles collide.
 *
 * The record is a strict, closed envelope (version + exactly the
 * metadata fields); a missing key reads null and a malformed record
 * fails closed with [DeviceCredentialStoreException]. Every operation
 * uses `commit()` (durable, synchronous) and must run off the main
 * thread; every failure maps to the closed
 * [DeviceCredentialStoreException] and never echoes input.
 */
@SuppressLint("UseKtx")
class SharedPreferencesDeviceCredentialMetadataStore(
    context: Context,
    private val preferencesName: String = DEFAULT_PREFERENCES_NAME
) : DeviceCredentialMetadataStore {

    private val preferences: SharedPreferences

    init {
        if (preferencesName.isBlank()) throw DeviceCredentialStoreException()
        preferences = try {
            context.applicationContext.getSharedPreferences(
                preferencesName,
                Context.MODE_PRIVATE
            )
        } catch (_: Exception) {
            throw DeviceCredentialStoreException()
        }
    }

    override fun put(profileId: String, metadata: DeviceCredentialMetadata) {
        validateProfileId(profileId)
        storeCall {
            validateMetadata(metadata)
            val encoded = encode(metadata)
            if (!preferences.edit().putString(preferenceKeyFor(profileId), encoded).commit()) {
                throw DeviceCredentialStoreException()
            }
        }
    }

    override fun get(profileId: String): DeviceCredentialMetadata? {
        validateProfileId(profileId)
        return storeCall {
            val encoded = preferences.getString(preferenceKeyFor(profileId), null) ?: return null
            decode(encoded)
        }
    }

    override fun delete(profileId: String) {
        validateProfileId(profileId)
        storeCall {
            // Idempotent: removing an absent key still commits successfully.
            if (!preferences.edit().remove(preferenceKeyFor(profileId)).commit()) {
                throw DeviceCredentialStoreException()
            }
        }
    }

    internal fun preferenceKeyFor(profileId: String): String {
        validateProfileId(profileId)
        return "$PREFERENCE_KEY_PREFIX${digest(profileId)}"
    }

    private fun validateProfileId(profileId: String) {
        if (profileId.isBlank()) throw DeviceCredentialStoreException()
    }

    private fun encode(metadata: DeviceCredentialMetadata): String {
        val permissions = JSONArray()
        metadata.permissions.forEach { permissions.put(it) }
        return try {
            JSONObject()
                .put(VERSION_FIELD, FORMAT_VERSION)
                .put(DEVICE_ID_FIELD, metadata.deviceId)
                .put(PROFILE_FIELD, metadata.profile)
                .put(LABEL_FIELD, metadata.label)
                .put(PERMISSIONS_FIELD, permissions)
                .put(ISSUED_AT_FIELD, metadata.issuedAt)
                .toString()
        } catch (_: Exception) {
            throw DeviceCredentialStoreException()
        }
    }

    private fun decode(encoded: String): DeviceCredentialMetadata {
        val root = try {
            JSONObject(encoded)
        } catch (_: Exception) {
            throw DeviceCredentialStoreException()
        }
        val fields = HashSet<String>()
        root.keys().forEach { fields.add(it) }
        if (fields != RECORD_FIELDS) {
            throw DeviceCredentialStoreException()
        }
        val version = root.opt(VERSION_FIELD)
        if (version !is Int || version != FORMAT_VERSION) {
            throw DeviceCredentialStoreException()
        }
        val deviceId = root.opt(DEVICE_ID_FIELD) as? String
            ?: throw DeviceCredentialStoreException()
        val profile = root.opt(PROFILE_FIELD) as? String
            ?: throw DeviceCredentialStoreException()
        val label = root.opt(LABEL_FIELD) as? String
            ?: throw DeviceCredentialStoreException()
        val permissionsArray = root.opt(PERMISSIONS_FIELD) as? JSONArray
            ?: throw DeviceCredentialStoreException()
        val permissions = ArrayList<String>(permissionsArray.length())
        for (index in 0 until permissionsArray.length()) {
            val permission = permissionsArray.opt(index) as? String
                ?: throw DeviceCredentialStoreException()
            permissions.add(permission)
        }
        val issuedAt = root.opt(ISSUED_AT_FIELD)
        if (issuedAt !is Number) {
            throw DeviceCredentialStoreException()
        }
        val metadata = DeviceCredentialMetadata(
            deviceId = deviceId,
            profile = profile,
            label = label,
            permissions = permissions,
            issuedAt = issuedAt.toDouble()
        )
        validateMetadata(metadata)
        return metadata
    }

    private fun validateMetadata(metadata: DeviceCredentialMetadata) {
        val canonicalUuid = try {
            UUID.fromString(metadata.deviceId).toString()
        } catch (_: IllegalArgumentException) {
            throw DeviceCredentialStoreException()
        }
        if (!canonicalUuid.equals(metadata.deviceId, ignoreCase = true) ||
            metadata.profile.isBlank() ||
            metadata.permissions.size != EXPECTED_PERMISSIONS.size ||
            metadata.permissions.toSet() != EXPECTED_PERMISSIONS ||
            !metadata.issuedAt.isFinite() ||
            metadata.issuedAt < 0.0
        ) {
            throw DeviceCredentialStoreException()
        }
    }

    private fun digest(profileId: String): String {
        val bytes = profileId.toByteArray(Charsets.UTF_8)
        val hash = try {
            MessageDigest.getInstance("SHA-256").digest(bytes)
        } finally {
            bytes.fill(0)
        }
        return Base64.encodeToString(hash, BASE64_FLAGS)
    }

    private inline fun <T> storeCall(block: () -> T): T = try {
        block()
    } catch (error: DeviceCredentialStoreException) {
        throw error
    } catch (_: Exception) {
        throw DeviceCredentialStoreException()
    }

    companion object {
        internal const val DEFAULT_PREFERENCES_NAME = "hermes_device_credentials"
        internal const val PREFERENCE_KEY_PREFIX = "device.credential.v1."
        private const val FORMAT_VERSION = 1
        private const val VERSION_FIELD = "version"
        private const val DEVICE_ID_FIELD = "device_id"
        private const val PROFILE_FIELD = "profile"
        private const val LABEL_FIELD = "label"
        private const val PERMISSIONS_FIELD = "permissions"
        private const val ISSUED_AT_FIELD = "issued_at"
        private val EXPECTED_PERMISSIONS = setOf("approvals", "chat", "runs")
        private val RECORD_FIELDS = setOf(
            VERSION_FIELD,
            DEVICE_ID_FIELD,
            PROFILE_FIELD,
            LABEL_FIELD,
            PERMISSIONS_FIELD,
            ISSUED_AT_FIELD
        )
        private const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    }
}
