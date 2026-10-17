package io.github.excludebarrier.orsikrelay.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag as semanticsTestTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairCode
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileFormValidator
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue

/**
 * Onboarding form.
 *
 * Two visible connection modes share the server name and the HTTPS base
 * URL:
 *
 * - [OnboardingMode.Pairing] (recommended, the DEFAULT): a single
 *   pairing-code field. The code is treated as a secret: password
 *   semantics, no plain-text rendering, local validation through
 *   [DevicePairCode.normalize]. There is NO API-key field in this mode.
 * - [OnboardingMode.ApiKey]: the pre-existing mode, semantically
 *   unchanged (same fields, same validation, same submit path).
 *
 * While a connection request is in flight the mode switch, all inputs
 * and the submit button are disabled, so a double tap can never start a
 * second request. Errors are closed, German, input-free messages.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    onValidDraft: (ProfileDraft) -> Unit,
    onValidPairing: (profileName: String, baseUrl: String, pairingCode: String) -> Unit,
    modifier: Modifier = Modifier,
    connectionState: OnboardingConnectionState = OnboardingConnectionState.Idle,
    onInputChanged: () -> Unit = {},
    // Product Gate: pre-filled from a pairing deep link (server + code) so the
    // user never types a base URL or API key. Empty when no deep link was used.
    initialProfileName: String = "",
    initialBaseUrl: String = "",
    initialPairingCode: String = ""
) {
    // Non-secret fields survive a rotation/recreation via the saved instance
    // state. The secrets (API key, one-time pairing code) deliberately stay in
    // plain `remember`: persisting them into the Bundle would write credentials
    // to disk-backed saved state.
    var mode by rememberSaveable { mutableStateOf(OnboardingMode.Pairing) }
    var profileName by rememberSaveable { mutableStateOf(initialProfileName) }
    var baseUrl by rememberSaveable { mutableStateOf(initialBaseUrl) }
    var apiKey by remember { mutableStateOf("") }
    var pairingCode by remember { mutableStateOf(initialPairingCode) }
    var validationError by remember {
        mutableStateOf<ProfileFormValidator.ValidationError?>(null)
    }
    var pairingCodeError by remember { mutableStateOf(false) }

    // Re-tap support: a deep link delivered after first composition (onNewIntent)
    // updates the pre-filled fields. Only non-empty values override, so a
    // bootstrap never wipes a field the user has since edited.
    LaunchedEffect(initialProfileName, initialBaseUrl, initialPairingCode) {
        if (initialProfileName.isNotEmpty()) profileName = initialProfileName
        if (initialBaseUrl.isNotEmpty()) baseUrl = initialBaseUrl
        if (initialPairingCode.isNotEmpty()) pairingCode = initialPairingCode
    }
    val apiKeyLabel = stringResource(R.string.onboarding_api_key_label)
    // Resolved in the composable scope: the semantics block below is not
    // @Composable and therefore cannot call stringResource itself.
    val pairingCodeLabel = stringResource(R.string.onboarding_pairing_code_label)
    val isConnecting = connectionState is OnboardingConnectionState.Connecting

    fun clearFieldErrors() {
        validationError = null
        pairingCodeError = false
        onInputChanged()
    }

    fun switchMode(target: OnboardingMode) {
        if (isConnecting || mode == target) return
        mode = target
        clearFieldErrors()
    }

    fun submit() {
        when (mode) {
            OnboardingMode.Pairing -> {
                // The pairing mode has no API-key field, so
                // only the shared connection fields (name + HTTPS base URL)
                // are validated — never the `API_KEY_EMPTY` rule. The
                // pairing code is validated separately against the closed
                // `DevicePairCode.normalize` contract before any request.
                val codeValid = DevicePairCode.normalize(pairingCode) != null
                val result = ProfileFormValidator.validateConnectionFields(
                    profileName = profileName,
                    baseUrl = baseUrl
                )
                val error = result.exceptionOrNull() as? ProfileFormValidator.ValidationError
                if (error == null && codeValid) {
                    validationError = null
                    pairingCodeError = false
                    val draft = result.getOrThrow()
                    onValidPairing(draft.profileName, draft.baseUrl, pairingCode)
                } else {
                    validationError = error
                    pairingCodeError = !codeValid
                }
            }
            OnboardingMode.ApiKey -> {
                val result = ProfileFormValidator.validate(
                    profileName = profileName,
                    baseUrl = baseUrl,
                    apiKey = SecretValue(apiKey)
                )
                when (val error = result.exceptionOrNull()) {
                    null -> {
                        validationError = null
                        pairingCodeError = false
                        onValidDraft(result.getOrThrow())
                    }
                    is ProfileFormValidator.ValidationError -> validationError = error
                    else -> Unit
                }
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )
        Text(
            text = stringResource(R.string.onboarding_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 8.dp)
        )

        SingleChoiceSegmentedButtonRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
        ) {
            SegmentedButton(
                selected = mode == OnboardingMode.Pairing,
                onClick = { switchMode(OnboardingMode.Pairing) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                modifier = Modifier.testTag("onboardingModePairing")
            ) {
                Text(
                    text = stringResource(R.string.onboarding_mode_pairing) +
                        " (" + stringResource(R.string.onboarding_mode_pairing_recommended) + ")"
                )
            }
            SegmentedButton(
                selected = mode == OnboardingMode.ApiKey,
                onClick = { switchMode(OnboardingMode.ApiKey) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                modifier = Modifier.testTag("onboardingModeApiKey")
            ) {
                Text(text = stringResource(R.string.onboarding_mode_api_key))
            }
        }

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = profileName,
                    onValueChange = {
                        profileName = it
                        clearFieldErrors()
                    },
                    label = { Text(text = stringResource(R.string.onboarding_profile_name_label)) },
                    supportingText = validationError?.profileNameError
                        ?.let { code -> { Text(text = stringResource(profileNameErrorMessage(code))) } },
                    isError = validationError?.profileNameError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    enabled = !isConnecting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("onboardingProfileName")
                )

                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = {
                        baseUrl = it
                        clearFieldErrors()
                    },
                    label = { Text(text = stringResource(R.string.onboarding_base_url_label)) },
                    supportingText = validationError?.baseUrlError
                        ?.let { code -> { Text(text = stringResource(baseUrlErrorMessage(code))) } },
                    isError = validationError?.baseUrlError != null,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    enabled = !isConnecting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                        .testTag("onboardingBaseUrl")
                )

                if (mode == OnboardingMode.Pairing) {
                    // The pairing code is a single-use
                    // secret. It is rendered with password semantics and
                    // validated locally; the API-key field is not present
                    // in this mode at all.
                    OutlinedTextField(
                        value = pairingCode,
                        onValueChange = {
                            pairingCode = it
                            clearFieldErrors()
                        },
                        label = { Text(text = stringResource(R.string.onboarding_pairing_code_label)) },
                        supportingText = {
                            if (pairingCodeError) {
                                Text(text = stringResource(R.string.error_pairing_code_local_invalid))
                            }
                        },
                        isError = pairingCodeError,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !isConnecting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                            .semantics {
                                semanticsTestTag = "onboardingPairingCode"
                                contentDescription = pairingCodeLabel
                                password()
                            }
                    )
                } else {
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = {
                            apiKey = it
                            clearFieldErrors()
                        },
                        label = { Text(text = apiKeyLabel) },
                        supportingText = validationError?.apiKeyError
                            ?.let { code -> { Text(text = stringResource(apiKeyErrorMessage(code))) } },
                        isError = validationError?.apiKeyError != null,
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        enabled = !isConnecting,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 16.dp)
                            .semantics {
                                semanticsTestTag = "onboardingApiKey"
                                contentDescription = apiKeyLabel
                                password()
                            }
                    )
                }
            }
        }

        if (connectionState is OnboardingConnectionState.Failure) {
            Text(
                text = stringResource(onboardingFailureMessage(connectionState.reason)),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .testTag("onboardingConnectionError")
            )
        }

        Button(
            onClick = { submit() },
            enabled = !isConnecting,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .testTag("onboardingSubmitButton")
        ) {
            if (isConnecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    text = stringResource(R.string.onboarding_connecting),
                    modifier = Modifier.padding(start = 8.dp)
                )
            } else {
                Text(text = stringResource(R.string.onboarding_submit))
            }
        }
    }
}

private fun profileNameErrorMessage(code: String): Int = when (code) {
    ProfileFormValidator.ErrorCodes.PROFILE_NAME_EMPTY -> R.string.error_profile_name_empty
    ProfileFormValidator.ErrorCodes.PROFILE_NAME_TOO_LONG -> R.string.error_profile_name_too_long
    else -> R.string.error_profile_name_generic
}

private fun baseUrlErrorMessage(code: String): Int = when (code) {
    ProfileFormValidator.ErrorCodes.BASE_URL_INVALID -> R.string.error_base_url_invalid
    else -> R.string.error_base_url_generic
}

private fun apiKeyErrorMessage(code: String): Int = when (code) {
    ProfileFormValidator.ErrorCodes.API_KEY_EMPTY -> R.string.error_api_key_empty
    else -> R.string.error_api_key_generic
}

private fun onboardingFailureMessage(reason: OnboardingFailureReason): Int = when (reason) {
    OnboardingFailureReason.INVALID_INPUT -> R.string.error_connection_invalid_input
    OnboardingFailureReason.INVALID_CREDENTIALS -> R.string.error_connection_invalid_credentials
    OnboardingFailureReason.RATE_LIMITED -> R.string.error_connection_rate_limited
    OnboardingFailureReason.INCOMPATIBLE_API -> R.string.error_connection_incompatible_api
    OnboardingFailureReason.TLS -> R.string.error_connection_tls
    OnboardingFailureReason.TIMEOUT -> R.string.error_connection_timeout
    OnboardingFailureReason.NETWORK -> R.string.error_connection_network
    OnboardingFailureReason.HTTP -> R.string.error_connection_http
    OnboardingFailureReason.PERSISTENCE -> R.string.error_connection_persistence
    OnboardingFailureReason.PAIRING_CODE_INVALID -> R.string.error_pairing_code_invalid
    OnboardingFailureReason.PAIRING_DEVICE_LIMIT -> R.string.error_pairing_device_limit
    OnboardingFailureReason.PAIRING_RATE_LIMITED -> R.string.error_pairing_rate_limited
    OnboardingFailureReason.PAIRING_INVALID_REQUEST -> R.string.error_pairing_invalid_request
    OnboardingFailureReason.PAIRING_INCOMPATIBLE -> R.string.error_pairing_incompatible_api
    OnboardingFailureReason.PAIRING_FAILED -> R.string.error_pairing_failed
    OnboardingFailureReason.PAIRING_CREDENTIAL_REJECTED -> R.string.error_pairing_credential_rejected
}
