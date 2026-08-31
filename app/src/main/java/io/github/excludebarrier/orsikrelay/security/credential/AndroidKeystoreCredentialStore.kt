package io.github.excludebarrier.orsikrelay.security.credential

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// The KTX edit extension discards commit()'s Boolean result, which this
// security boundary must verify before reporting success.
@SuppressLint("UseKtx")
class AndroidKeystoreCredentialStore(
    context: Context,
    private val preferencesName: String = DEFAULT_PREFERENCES_NAME,
    private val aliasNamespace: String = DEFAULT_ALIAS_NAMESPACE
) : CredentialStore {

    private val preferences: SharedPreferences
    private val keyStore: KeyStore

    init {
        validateConfiguration()
        try {
            preferences = context.applicationContext.getSharedPreferences(
                preferencesName,
                Context.MODE_PRIVATE
            )
            keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        } catch (_: Exception) {
            throw CredentialStoreException()
        }
    }

    override fun put(profileId: String, secret: SecretValue) {
        validateProfileId(profileId)
        if (secret.isEmpty()) throw CredentialStoreException()

        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey(aliasFor(profileId)))
            val payload = secret.useRaw { raw ->
                val plaintext = raw.toByteArray(Charsets.UTF_8)
                try {
                    val ciphertext = cipher.doFinal(plaintext)
                    encodePayload(cipher.iv, ciphertext)
                } finally {
                    plaintext.fill(0)
                }
            }

            if (!preferences.edit().putString(preferenceKeyFor(profileId), payload).commit()) {
                throw CredentialStoreException()
            }
        } catch (error: CredentialStoreException) {
            throw error
        } catch (_: Exception) {
            throw CredentialStoreException()
        }
    }

    override fun get(profileId: String): SecretValue? {
        validateProfileId(profileId)

        try {
            val payload = preferences.getString(preferenceKeyFor(profileId), null) ?: return null
            val parsed = decodePayload(payload)
            val key = keyStore.getKey(aliasFor(profileId), null) as? SecretKey
                ?: throw CredentialStoreException()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key,
                GCMParameterSpec(GCM_TAG_LENGTH_BITS, parsed.iv)
            )
            val plaintext = cipher.doFinal(parsed.ciphertext)
            return try {
                SecretValue(String(plaintext, Charsets.UTF_8))
            } finally {
                plaintext.fill(0)
            }
        } catch (error: CredentialStoreException) {
            throw error
        } catch (_: Exception) {
            throw CredentialStoreException()
        }
    }

    override fun delete(profileId: String) {
        validateProfileId(profileId)

        try {
            val alias = aliasFor(profileId)
            if (keyStore.containsAlias(alias)) {
                keyStore.deleteEntry(alias)
            }
            if (!preferences.edit().remove(preferenceKeyFor(profileId)).commit()) {
                throw CredentialStoreException()
            }
        } catch (error: CredentialStoreException) {
            throw error
        } catch (_: Exception) {
            throw CredentialStoreException()
        }
    }

    internal fun aliasFor(profileId: String): String {
        validateProfileId(profileId)
        return "$ALIAS_PREFIX${digest("alias:$aliasNamespace:$profileId")}"
    }

    internal fun preferenceKeyFor(profileId: String): String {
        validateProfileId(profileId)
        return "$PREFERENCE_KEY_PREFIX${digest("preference:$aliasNamespace:$profileId")}"
    }

    private fun validateConfiguration() {
        if (preferencesName.isBlank() || aliasNamespace.isBlank()) {
            throw CredentialStoreException()
        }
    }

    private fun validateProfileId(profileId: String) {
        if (profileId.isBlank()) throw CredentialStoreException()
    }

    private fun getOrCreateKey(alias: String): SecretKey {
        val existing = keyStore.getKey(alias, null) as? SecretKey
        if (existing != null) return existing

        val generator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE
        )
        generator.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(AES_KEY_SIZE_BITS)
                .build()
        )
        return generator.generateKey()
    }

    private fun encodePayload(iv: ByteArray, ciphertext: ByteArray): String {
        if (iv.isEmpty() || ciphertext.isEmpty()) throw CredentialStoreException()
        return listOf(
            PAYLOAD_VERSION,
            Base64.encodeToString(iv, BASE64_FLAGS),
            Base64.encodeToString(ciphertext, BASE64_FLAGS)
        ).joinToString(":")
    }

    private fun decodePayload(payload: String): EncryptedPayload {
        val parts = payload.split(':', limit = 3)
        if (parts.size != 3 || parts[0] != PAYLOAD_VERSION) {
            throw CredentialStoreException()
        }
        val iv = Base64.decode(parts[1], BASE64_FLAGS)
        val ciphertext = Base64.decode(parts[2], BASE64_FLAGS)
        if (iv.size != GCM_IV_LENGTH_BYTES || ciphertext.size < GCM_TAG_LENGTH_BYTES) {
            throw CredentialStoreException()
        }
        return EncryptedPayload(iv = iv, ciphertext = ciphertext)
    }

    private fun digest(value: String): String {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val hash = try {
            MessageDigest.getInstance("SHA-256").digest(bytes)
        } finally {
            bytes.fill(0)
        }
        return Base64.encodeToString(hash, BASE64_FLAGS)
    }

    private class EncryptedPayload(
        val iv: ByteArray,
        val ciphertext: ByteArray
    )

    private companion object {
        const val ANDROID_KEY_STORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val AES_KEY_SIZE_BITS = 256
        const val GCM_TAG_LENGTH_BITS = 128
        const val GCM_TAG_LENGTH_BYTES = GCM_TAG_LENGTH_BITS / 8
        const val GCM_IV_LENGTH_BYTES = 12
        const val PAYLOAD_VERSION = "v1"
        const val DEFAULT_PREFERENCES_NAME = "hermes_credentials"
        const val DEFAULT_ALIAS_NAMESPACE = "io.github.excludebarrier.orsikrelay"
        const val ALIAS_PREFIX = "hermes.credential."
        const val PREFERENCE_KEY_PREFIX = "credential."
        const val BASE64_FLAGS = Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
    }
}
