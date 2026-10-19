package io.github.excludebarrier.orsikrelay.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.password
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag as semanticsTestTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.device.DevicePairCode
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileFormValidator
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue
import io.github.excludebarrier.orsikrelay.ui.common.NoticeCard
import io.github.excludebarrier.orsikrelay.ui.common.NoticeTone
import io.github.excludebarrier.orsikrelay.ui.pairing.PairingQrScannerScreen
import kotlinx.coroutines.flow.Flow

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
    initialPairingCode: String = "",
    // Set only when onboarding was opened from "Server verwalten": the
    // header then offers "Zurück". The first start and the pairing deep
    // link leave it null (no destination to return to, no visual change).
    onBack: (() -> Unit)? = null,
    // False on devices without a camera: the QR scan action is then hidden.
    cameraAvailable: Boolean = true,
    // Test seam for the QR scan source; null means the real camera scanner.
    scanFrames: Flow<String>? = null
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
    var showModeInfo by rememberSaveable { mutableStateOf(false) }
    var showScanner by rememberSaveable { mutableStateOf(false) }

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

    if (showScanner) {
        PairingQrScannerScreen(
            onScanned = { bootstrap ->
                // Pre-fill exactly what the shared deep-link parser accepted.
                // The server name stays untouched and nothing connects until
                // the user presses "Connect".
                baseUrl = bootstrap.baseUrl
                pairingCode = bootstrap.pairingCode
                clearFieldErrors()
                showScanner = false
            },
            onClose = { showScanner = false },
            scanFrames = scanFrames
        )
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        if (onBack != null) {
            Row(modifier = Modifier.fillMaxWidth()) {
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.testTag("onboardingBack")
                ) {
                    Text(text = stringResource(R.string.settings_back))
                }
            }
        }
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

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Equal segment heights at every font scale: the row adopts
                // the tallest intrinsic height (and at least a 48 dp touch
                // target), and each segment fills it. The pairing label is
                // deliberately just "Pairing code" so it stays on one line;
                // the recommendation moved to a badge below the segment.
                SingleChoiceSegmentedButtonRow(
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .height(IntrinsicSize.Min)
                ) {
                    SegmentedButton(
                        selected = mode == OnboardingMode.Pairing,
                        onClick = { switchMode(OnboardingMode.Pairing) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        modifier = Modifier
                            .fillMaxHeight()
                            .testTag("onboardingModePairing"),
                        icon = {}
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_mode_pairing),
                            textAlign = TextAlign.Center,
                            fontWeight = if (mode == OnboardingMode.Pairing) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            }
                        )
                    }
                    SegmentedButton(
                        selected = mode == OnboardingMode.ApiKey,
                        onClick = { switchMode(OnboardingMode.ApiKey) },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        modifier = Modifier
                            .fillMaxHeight()
                            .testTag("onboardingModeApiKey"),
                        icon = {}
                    ) {
                        Text(
                            text = stringResource(R.string.onboarding_mode_api_key),
                            textAlign = TextAlign.Center,
                            fontWeight = if (mode == OnboardingMode.ApiKey) {
                                FontWeight.SemiBold
                            } else {
                                FontWeight.Normal
                            }
                        )
                    }
                }
                IconButton(
                    onClick = { showModeInfo = true },
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .size(48.dp)
                        .testTag("onboardingModeInfo")
                ) {
                    Icon(
                        imageVector = Icons.Filled.Info,
                        contentDescription = stringResource(R.string.onboarding_mode_info_content_description)
                    )
                }
            }
            // Small badge under the pairing segment. Two equal weight cells
            // mirror the two segments; the trailing spacer matches the info
            // button, so the badge centre stays under the left segment.
            Row(modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    if (mode == OnboardingMode.Pairing) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = CircleShape
                        ) {
                            Text(
                                text = stringResource(R.string.onboarding_mode_pairing_recommended),
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                    .testTag("onboardingModePairingRecommended")
                            )
                        }
                    }
                }
                Box(modifier = Modifier.weight(1f))
                Spacer(modifier = Modifier.width(56.dp))
            }
        }

        if (mode == OnboardingMode.Pairing && cameraAvailable) {
            FilledTonalButton(
                onClick = { showScanner = true },
                enabled = !isConnecting,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
                    .testTag("onboardingScanQr")
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_qr_code_scanner),
                    contentDescription = null
                )
                Text(
                    text = stringResource(R.string.onboarding_scan_qr),
                    modifier = Modifier.padding(start = 8.dp)
                )
            }
        }

        // Fields sit freely (no bordered card) with a uniform 16 dp rhythm.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
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

        if (connectionState is OnboardingConnectionState.Failure) {
            val (title, text) = onboardingFailureCopy(connectionState.reason)
            NoticeCard(
                tone = NoticeTone.Error,
                title = stringResource(title),
                text = stringResource(text),
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

    if (showModeInfo) {
        AlertDialog(
            onDismissRequest = { showModeInfo = false },
            confirmButton = {
                TextButton(onClick = { showModeInfo = false }) {
                    Text(text = stringResource(R.string.onboarding_mode_info_ok))
                }
            },
            title = {
                Text(text = stringResource(R.string.onboarding_mode_info_title))
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(text = stringResource(R.string.onboarding_mode_info_pairing_body))
                    Text(text = stringResource(R.string.onboarding_mode_info_api_key_body))
                }
            }
        )
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

/**
 * Static, input-free title and one-sentence action text for a failed
 * connection attempt.
 *
 * Neither half contains URLs, host names, server messages, or exception
 * text. The title names the problem, the text says what the user can do.
 */
internal fun onboardingFailureCopy(reason: OnboardingFailureReason): Pair<Int, Int> = when (reason) {
    OnboardingFailureReason.INVALID_INPUT ->
        R.string.onboarding_error_invalid_input_title to R.string.onboarding_error_invalid_input_text
    OnboardingFailureReason.INVALID_CREDENTIALS ->
        R.string.onboarding_error_invalid_credentials_title to R.string.onboarding_error_invalid_credentials_text
    OnboardingFailureReason.RATE_LIMITED ->
        R.string.onboarding_error_rate_limited_title to R.string.onboarding_error_rate_limited_text
    OnboardingFailureReason.INCOMPATIBLE_API ->
        R.string.onboarding_error_incompatible_api_title to R.string.onboarding_error_incompatible_api_text
    OnboardingFailureReason.TLS ->
        R.string.onboarding_error_tls_title to R.string.onboarding_error_tls_text
    OnboardingFailureReason.TIMEOUT ->
        R.string.onboarding_error_timeout_title to R.string.onboarding_error_timeout_text
    OnboardingFailureReason.NETWORK ->
        R.string.onboarding_error_network_title to R.string.onboarding_error_network_text
    OnboardingFailureReason.HTTP ->
        R.string.onboarding_error_http_title to R.string.onboarding_error_http_text
    OnboardingFailureReason.PERSISTENCE ->
        R.string.onboarding_error_persistence_title to R.string.onboarding_error_persistence_text
    OnboardingFailureReason.PAIRING_CODE_INVALID ->
        R.string.onboarding_error_pairing_code_invalid_title to R.string.onboarding_error_pairing_code_invalid_text
    OnboardingFailureReason.PAIRING_DEVICE_LIMIT ->
        R.string.onboarding_error_pairing_device_limit_title to R.string.onboarding_error_pairing_device_limit_text
    OnboardingFailureReason.PAIRING_RATE_LIMITED ->
        R.string.onboarding_error_pairing_rate_limited_title to R.string.onboarding_error_pairing_rate_limited_text
    OnboardingFailureReason.PAIRING_INVALID_REQUEST ->
        R.string.onboarding_error_pairing_invalid_request_title to R.string.onboarding_error_pairing_invalid_request_text
    OnboardingFailureReason.PAIRING_INCOMPATIBLE ->
        R.string.onboarding_error_pairing_incompatible_title to R.string.onboarding_error_pairing_incompatible_text
    OnboardingFailureReason.PAIRING_FAILED ->
        R.string.onboarding_error_pairing_failed_title to R.string.onboarding_error_pairing_failed_text
    OnboardingFailureReason.PAIRING_CREDENTIAL_REJECTED ->
        R.string.onboarding_error_pairing_credential_rejected_title to
            R.string.onboarding_error_pairing_credential_rejected_text
}
