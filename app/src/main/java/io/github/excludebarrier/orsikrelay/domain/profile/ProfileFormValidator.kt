package io.github.excludebarrier.orsikrelay.domain.profile

import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue

/**
 * Pure, local validation of the onboarding form.
 *
 * Rules:
 * - profile name: not empty after trim, at most [PROFILE_NAME_MAX_LENGTH]
 *   characters;
 * - base URL: see [ProfileBaseUrlValidator];
 * - API key: not empty; the raw value is never copied into errors,
 *   messages or logs.
 *
 * No network access, no persistence.
 */
object ProfileFormValidator {

    const val PROFILE_NAME_MAX_LENGTH = 64

    /**
     * Stable, machine-readable error codes. The UI maps them to localized
     * user messages. Codes contain no user input and are therefore safe
     * in messages, logs and test output.
     */
    object ErrorCodes {
        const val PROFILE_NAME_EMPTY = "profile_name_empty"
        const val PROFILE_NAME_TOO_LONG = "profile_name_too_long"
        const val PROFILE_NAME_INVALID = "profile_name_invalid"
        const val BASE_URL_INVALID = "base_url_invalid"
        const val API_KEY_EMPTY = "api_key_empty"
    }

    /**
     * Field-related error codes of a failed validation. A field is `null`
     * when that field was valid.
     */
    class ValidationError(
        val profileNameError: String? = null,
        val baseUrlError: String? = null,
        val apiKeyError: String? = null
    ) : Exception("onboarding form validation failed")

    /**
     * Validates the onboarding form fields.
     *
     * @return success with the normalized [ProfileDraft] or a failure
     * carrying [ValidationError] with the failed fields.
     */
    fun validate(
        profileName: String,
        baseUrl: String,
        apiKey: SecretValue
    ): Result<ProfileDraft> {
        val fields = validateConnectionFields(profileName, baseUrl)
        var keyError: String? = null
        if (apiKey.isEmpty()) {
            keyError = ErrorCodes.API_KEY_EMPTY
        }

        val failure = fields.exceptionOrNull() as? ValidationError
        if (failure != null || keyError != null) {
            return Result.failure(
                ValidationError(
                    profileNameError = failure?.profileNameError,
                    baseUrlError = failure?.baseUrlError,
                    apiKeyError = keyError
                )
            )
        }
        return Result.success(
            ProfileDraft(
                profileName = fields.getOrThrow().profileName,
                baseUrl = fields.getOrThrow().baseUrl,
                apiKey = apiKey
            )
        )
    }

    /**
     * Validates ONLY the shared connection fields (profile name + HTTPS
     * base URL) without any credential rule.
     *
     * The device-pairing mode has no API-key field, so it
     * must not be rejected by the `API_KEY_EMPTY` rule. The pairing code
     * is validated separately by the UI against the closed
     * `DevicePairCode.normalize` contract. The API-key mode keeps the
     * full [validate] behaviour, unchanged.
     *
     * @return success with the normalized profile name and base URL or a
     * failure carrying [ValidationError] with the failed fields.
     */
    fun validateConnectionFields(
        profileName: String,
        baseUrl: String
    ): Result<ProfileDraft> {
        val trimmedName = profileName.trim()
        var nameError: String? = null
        if (trimmedName.isEmpty()) {
            nameError = ErrorCodes.PROFILE_NAME_EMPTY
        } else if (trimmedName.length > PROFILE_NAME_MAX_LENGTH) {
            nameError = ErrorCodes.PROFILE_NAME_TOO_LONG
        } else if (trimmedName.any { it.isISOControl() }) {
            // The name is shown in the UI and in notifications.
            nameError = ErrorCodes.PROFILE_NAME_INVALID
        }

        val urlResult = ProfileBaseUrlValidator.validate(baseUrl)
        var urlError: String? = null
        if (urlResult.isFailure) {
            urlError = ErrorCodes.BASE_URL_INVALID
        }

        if (nameError != null || urlError != null) {
            return Result.failure(
                ValidationError(
                    profileNameError = nameError,
                    baseUrlError = urlError
                )
            )
        }
        return Result.success(
            ProfileDraft(
                profileName = trimmedName,
                baseUrl = urlResult.getOrThrow(),
                // No credential is present in this mode; the draft's
                // credential slot stays empty by design.
                apiKey = SecretValue("")
            )
        )
    }
}
