package io.github.excludebarrier.orsikrelay.ui.sessions

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateError
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dialog for creating a new empty Hermes session.
 *
 * - The title is optional; the raw text is passed to [creator], the
 *   repository sanitizes it (trim, control characters, length limit).
 *   The input itself accepts at most [MAX_TITLE_CHARACTERS] characters
 *   (Hermes `SessionDB.MAX_TITLE_LENGTH = 100`) and shows the live
 *   `n/100` counter as supporting text.
 * - "Abbrechen" closes the dialog and sends nothing.
 * - "Erstellen" submits exactly once per tap: a second tap while a
 *   request is in flight is ignored; after a failure the retry is
 *   manual only (no automatic retry).
 * - A failure shows a closed, localized message without server,
 *   response, title, key or exception details. An already-used or
 *   over-long title (`SessionCreateError.InvalidTitle`) gets its own,
 *   actionable message.
 * - On success [onCreated] receives the server-generated session ID;
 *   the parent closes the dialog and navigates.
 *
 * [creator] is the test seam; production injects the repository.
 */
@Composable
fun NewSessionDialog(
    creator: SessionCreator,
    profileId: String,
    onDismiss: () -> Unit,
    onCreated: (sessionId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var titleInput by remember { mutableStateOf("") }
    var submitting by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<SessionCreateError?>(null) }

    fun submit() {
        if (submitting) return
        submitting = true
        failure = null
        scope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    creator.createSession(
                        profileId,
                        titleInput.takeUnless { it.isBlank() }
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                SessionCreateResult.Failure(SessionCreateError.Network)
            }
            submitting = false
            when (result) {
                is SessionCreateResult.Success -> onCreated(result.sessionId)
                is SessionCreateResult.Failure -> failure = result.error
            }
        }
    }

    AlertDialog(
        modifier = modifier,
        onDismissRequest = { if (!submitting) onDismiss() },
        title = { Text(text = stringResource(R.string.new_session_title)) },
        text = {
            Column {
                if (submitting) {
                    Text(
                        text = stringResource(R.string.new_session_loading),
                        style = MaterialTheme.typography.bodyLarge
                    )
                } else {
                    OutlinedTextField(
                        value = titleInput,
                        onValueChange = { titleInput = it.take(MAX_TITLE_CHARACTERS) },
                        label = {
                            Text(text = stringResource(R.string.new_session_title_label))
                        },
                        supportingText = {
                            Text(
                                text = stringResource(
                                    R.string.new_session_title_counter,
                                    titleInput.length,
                                    MAX_TITLE_CHARACTERS
                                )
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (failure != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = when (failure) {
                                SessionCreateError.MissingProfile ->
                                    stringResource(R.string.error_session_profile)
                                SessionCreateError.InvalidTitle ->
                                    stringResource(R.string.new_session_error_invalid_title)
                                else ->
                                    stringResource(R.string.new_session_error_generic)
                            },
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { submit() },
                enabled = !submitting
            ) {
                Text(
                    text = if (failure != null) {
                        stringResource(R.string.sessions_retry)
                    } else {
                        stringResource(R.string.new_session_create)
                    }
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !submitting
            ) {
                Text(text = stringResource(R.string.new_session_cancel))
            }
        }
    )
}

/**
 * Hermes rejects titles longer than `SessionDB.MAX_TITLE_LENGTH = 100`
 * characters, so the dialog never lets the input grow beyond that.
 */
internal const val MAX_TITLE_CHARACTERS = 100
