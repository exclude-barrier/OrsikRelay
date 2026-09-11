package io.github.excludebarrier.orsikrelay.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.run.ApprovalRequest
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalChoice
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalResult
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The safe run-approval decision dialog (Mission 014).
 *
 * This composable is the ONLY approval UI in the app. It renders exactly
 * the harmless, evidenced [ApprovalRequest] fields and nothing else —
 * no run ID, no request ID, no pattern key, no tool name, no raw log,
 * no environment output and no exception detail. The command and
 * description it shows are the SERVER-REDACTED values carried by the
 * model, so no secret text can reach the UI.
 *
 * Safety rules (Mission 014):
 * - "Ablehnen" is the first, filled (primary) button: it is the safe
 *   default. "Erlauben" is the second, secondary button and is the only
 *   way to consciously approve; it is never the default and is never
 *   triggered implicitly.
 * - The dialog is NOT dismissible by outside tap: [onDismissRequest] is a
 *   no-op, so a tap outside can neither approve nor deny (and cannot
 *   accidentally close the pending decision). Only the two explicit
 *   buttons act.
 * - Exactly one decision per request: while a response is in flight
 *   ([inFlight]) both buttons are disabled and a second tap is ignored,
 *   so a double tap can never send a second response.
 * - Every failure is shown as one closed, generic German message; no
 *   dynamic server value (run ID, request ID, body, exception text) is
 *   ever displayed.
 * - `Resolved` closes the dialog (the decision was applied). `NoPending`
 *   (409) means the request was already answered elsewhere or timed out;
 *   the dialog shows a short note and the buttons are disabled — no
 *   further decision is possible. `Failure` keeps the dialog open with
 *   the generic error and re-enables both buttons for ONE deliberate
 *   manual retry; there is no automatic retry.
 *
 * [requester] is the small domain seam; production injects the
 * repository, Compose tests inject a synthetic fake. Nothing here is
 * persisted and no credential, URL or error detail is ever modelled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ApprovalDialog(
    request: ApprovalRequest,
    profileId: String,
    requester: RunApprovalRequester,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    // True while exactly one decision request is in flight.
    var inFlight by remember { mutableStateOf(false) }
    // A closed terminal outcome after a completed request, or null while
    // the dialog is still awaiting a decision.
    var outcome by remember { mutableStateOf<ApprovalOutcome?>(null) }

    fun decide(choice: RunApprovalChoice) {
        // The in-flight lock: a decision already in progress (or already
        // settled as NoPending) must never open a second request.
        if (inFlight) return
        if (outcome == ApprovalOutcome.AlreadyResolved) return
        inFlight = true
        outcome = null
        scope.launch {
            val result = try {
                requester.respond(
                    profileId = profileId,
                    runId = request.runId,
                    requestId = request.requestId,
                    choice = choice
                )
            } catch (error: CancellationException) {
                // Propagate: the dialog is being disposed.
                throw error
            } catch (_: Exception) {
                RunApprovalResult.Failure(RunApprovalError.Network)
            }
            inFlight = false
            when (result) {
                is RunApprovalResult.Resolved -> onDismiss()
                is RunApprovalResult.NoPending -> outcome = ApprovalOutcome.AlreadyResolved
                is RunApprovalResult.Failure -> outcome = ApprovalOutcome.Failed
            }
        }
    }

    val buttonsEnabled = !inFlight && outcome != ApprovalOutcome.AlreadyResolved

    // Mission 014 (hard gate): the content-lambda `AlertDialog` overload is
    // @Deprecated in material3 1.3.2 ("Use BasicAlertDialog instead", proven
    // from the local AAR bytecode). `BasicAlertDialog` is the non-deprecated
    // equivalent with the same ColumnScope content contract and no buttons
    // block — exactly what this dialog needs. No @Suppress is used.
    BasicAlertDialog(
        modifier = modifier,
        // No outside-tap confirmation: the dialog is non-dismissable.
        onDismissRequest = {}
    ) {
        Column(modifier = Modifier.padding(24.dp)) {
            Text(
                text = stringResource(R.string.approval_title),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            FieldLabel(label = stringResource(R.string.approval_action_label))
            Text(
                text = stringResource(R.string.approval_action_value),
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            FieldLabel(label = stringResource(R.string.approval_target_label))
            Text(
                text = request.command,
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(8.dp))
            FieldLabel(label = stringResource(R.string.approval_reason_label))
            Text(
                text = request.description,
                style = MaterialTheme.typography.bodyLarge
            )
            if (request.smartDenied) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.approval_risk_note),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            when (outcome) {
                ApprovalOutcome.Failed -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.approval_error_generic),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                ApprovalOutcome.AlreadyResolved -> {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.approval_already_resolved),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                null -> Unit
            }
            if (inFlight) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.approval_in_flight),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
            // Deny first (filled, primary, safe default), then Allow
            // (secondary, the only conscious approval path).
            Row {
                Button(
                    onClick = { decide(RunApprovalChoice.DENY) },
                    enabled = buttonsEnabled,
                    modifier = Modifier.testTag(TAG_DENY)
                ) {
                    Text(text = stringResource(R.string.approval_deny))
                }
                Spacer(modifier = Modifier.width(12.dp))
                TextButton(
                    onClick = { decide(RunApprovalChoice.ONCE) },
                    enabled = buttonsEnabled,
                    modifier = Modifier.testTag(TAG_ALLOW)
                ) {
                    Text(text = stringResource(R.string.approval_allow))
                }
            }
        }
    }
}

@Composable
private fun FieldLabel(label: String) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(bottom = 2.dp)
    )
}

/** Closed, non-serialised outcome states for the approval dialog. */
private enum class ApprovalOutcome {
    Failed,
    AlreadyResolved
}

private const val TAG_DENY = "approval_deny"
private const val TAG_ALLOW = "approval_allow"
