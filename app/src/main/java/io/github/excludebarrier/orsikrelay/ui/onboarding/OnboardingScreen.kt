package io.github.excludebarrier.orsikrelay.ui.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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

/**
 * Onboarding screen with the secure server-profile form (Mission 002A).
 *
 * State is held locally in Compose state per the ticket: no ViewModel,
 * no repository, no persistence, no networking. The API key lives only
 * in a [SecretValue] and is rendered exclusively in a masked password
 * field; the raw value never reaches error texts, semantics or logs.
 */
@Composable
fun OnboardingScreen(
    onValidDraft: (ProfileDraft) -> Unit,
    modifier: Modifier = Modifier
) {
    var profileName by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var validationError by remember { mutableStateOf<ProfileFormValidator.ValidationError?>(null) }
    val apiKeyLabel = stringResource(R.string.onboarding_api_key_label)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.onboarding_title),
            style = MaterialTheme.typography.headlineMedium,
            textAlign = TextAlign.Center
        )

        OutlinedTextField(
            value = profileName,
            onValueChange = { profileName = it },
            label = { Text(text = stringResource(R.string.onboarding_profile_name_label)) },
            supportingText = validationError?.profileNameError
                ?.let { code -> { Text(text = stringResource(profileNameErrorMessage(code))) } },
            isError = validationError?.profileNameError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp)
                .testTag("onboardingProfileName")
        )

        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text(text = stringResource(R.string.onboarding_base_url_label)) },
            supportingText = validationError?.baseUrlError
                ?.let { code -> { Text(text = stringResource(baseUrlErrorMessage(code))) } },
            isError = validationError?.baseUrlError != null,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .testTag("onboardingBaseUrl")
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text(text = apiKeyLabel) },
            supportingText = validationError?.apiKeyError
                ?.let { code -> { Text(text = stringResource(apiKeyErrorMessage(code))) } },
            isError = validationError?.apiKeyError != null,
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .semantics {
                    semanticsTestTag = "onboardingApiKey"
                    contentDescription = apiKeyLabel
                    password()
                }
        )

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
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 32.dp)
                .testTag("onboardingSubmitButton")
        ) {
            Text(text = stringResource(R.string.onboarding_submit))
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
