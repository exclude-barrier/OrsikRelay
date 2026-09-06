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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileDraft
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileFormValidator
import io.github.excludebarrier.orsikrelay.domain.profile.SecretValue

@Composable
fun OnboardingScreen(
    onValidDraft: (ProfileDraft) -> Unit,
    modifier: Modifier = Modifier,
    connectionState: OnboardingConnectionState = OnboardingConnectionState.Idle,
    onInputChanged: () -> Unit = {}
) {
    var profileName by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var validationError by remember {
        mutableStateOf<ProfileFormValidator.ValidationError?>(null)
    }
    val apiKeyLabel = stringResource(R.string.onboarding_api_key_label)
    val isConnecting = connectionState is OnboardingConnectionState.Connecting

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
                validationError = null
                onInputChanged()
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
                validationError = null
                onInputChanged()
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

        OutlinedTextField(
            value = apiKey,
            onValueChange = {
                apiKey = it
                validationError = null
                onInputChanged()
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
            Text(
                text = stringResource(connectionErrorMessage(connectionState.reason)),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .testTag("onboardingConnectionError")
            )
        }

        Button(
            onClick = {
                val result = ProfileFormValidator.validate(
                    profileName = profileName,
                    baseUrl = baseUrl,
                    apiKey = SecretValue(apiKey)
                )
                when (val error = result.exceptionOrNull()) {
                    null -> {
                        validationError = null
                        onValidDraft(result.getOrThrow())
                    }

                    is ProfileFormValidator.ValidationError -> validationError = error
                    else -> Unit
                }
            },
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

private fun connectionErrorMessage(reason: OnboardingFailureReason): Int = when (reason) {
    OnboardingFailureReason.INVALID_INPUT -> R.string.error_connection_invalid_input
    OnboardingFailureReason.INVALID_CREDENTIALS -> R.string.error_connection_invalid_credentials
    OnboardingFailureReason.RATE_LIMITED -> R.string.error_connection_rate_limited
    OnboardingFailureReason.INCOMPATIBLE_API -> R.string.error_connection_incompatible_api
    OnboardingFailureReason.TLS -> R.string.error_connection_tls
    OnboardingFailureReason.TIMEOUT -> R.string.error_connection_timeout
    OnboardingFailureReason.NETWORK -> R.string.error_connection_network
    OnboardingFailureReason.HTTP -> R.string.error_connection_http
    OnboardingFailureReason.PERSISTENCE -> R.string.error_connection_persistence
}
