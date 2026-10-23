package io.github.excludebarrier.orsikrelay.ui.chat

import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.BasicAlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikDialog
import io.github.excludebarrier.orsikrelay.domain.run.ApprovalRequest
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalChoice
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalRequester
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalResult
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * The safe run-approval decision dialog.
 *
 * This composable is the ONLY approval UI in the app. It renders fixed app
 * copy and no request detail at all — neither from [ApprovalRequest] nor
 * from any server value: no run ID, request ID, command, arguments, path,
 * URL, pattern key, tool output, environment output or exception detail.
 * The body states that Hermes wants to run an action and that allowing
 * applies to this one action only.
 *
 * Safety rules:
 * - "Ablehnen" is the first, neutral outlined button: it is the safe
 *   default and looks neutral rather than recommended. "Erlauben" is the
 *   second button, is filled in the error colour with a warning icon to
 *   mark it as the risky action, and is the only way to consciously
 *   approve; it is never the default and is never triggered implicitly.
 * - Both buttons share one width and one height (equal `weight` in a Row
 *   with an intrinsic height), so neither is visually favoured by size.
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
 *   the generic error and leaves both actions terminally disabled. Because a
 *   failed response may already have been applied server-side, the app never
 *   retries automatically or manually.
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
    /**
     * Notified for the authoritative terminal outcome that is NOT an applied
     * decision: a 409 `NoPending` answer (the request was already answered
     * elsewhere or timed out). The app-level approval record must then be
     * dropped, so a re-created screen cannot resurrect a non-answerable
     * dialog. The dialog itself stays open in this composition to show its
     * short note. Never called for an ambiguous transport failure - that
     * request deliberately stays resolvable.
     */
    onFinished: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    // True while exactly one decision request is in flight.
    var inFlight by remember { mutableStateOf(false) }
    // A closed terminal outcome after a completed request, or null while
    // the dialog is still awaiting a decision. A request this process has
    // already sent a decision for (the dialog was re-created by a rotation
    // or by leaving and re-entering the chat) starts as "already sent", so
    // a second, non-idempotent decision is impossible.
    val attemptKey = request.runId + "/" + request.requestId
    var outcome by remember {
        mutableStateOf(if (AttemptedApprovals.contains(attemptKey)) ApprovalOutcome.AlreadySent else null)
    }

    fun decide(choice: RunApprovalChoice) {
        // Any completed attempt is terminal. A transport failure is
        // ambiguous, so sending the same non-idempotent decision again is
        // forbidden just like an already-resolved response.
        if (inFlight || outcome != null) return
        if (!AttemptedApprovals.mark(attemptKey)) {
            outcome = ApprovalOutcome.AlreadySent
            return
        }
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
                is RunApprovalResult.NoPending -> {
                    // The server's authoritative "not pending" answer is
                    // terminal for the request: report it so the app-level
                    // record is dropped (no resurrection after recreation),
                    // then keep this composition's note.
                    outcome = ApprovalOutcome.AlreadyResolved
                    onFinished()
                }
                is RunApprovalResult.Failure -> outcome = ApprovalOutcome.Failed
            }
        }
    }

    val buttonsEnabled = !inFlight && outcome == null

    // The content-lambda `AlertDialog` overload is
    // @Deprecated in material3 1.3.2 ("Use BasicAlertDialog instead", proven
    // from the local AAR bytecode). `BasicAlertDialog` is the non-deprecated
    // equivalent with the same ColumnScope content contract and no buttons
    // block — exactly what this dialog needs. No @Suppress is used.
    BasicAlertDialog(
        modifier = modifier,
        // No outside-tap confirmation: the dialog is non-dismissable.
        onDismissRequest = {}
    ) {
        ObscuredTouchGuard()
        // BasicAlertDialog draws no container of its own: without this
        // surface the dialog text would sit transparently over the chat.
        Surface(
            shape = OrsikDialog.shape,
            color = OrsikDialog.containerColor,
            tonalElevation = OrsikDialog.tonalElevation,
            modifier = Modifier.testTag(TAG_SURFACE)
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text(
                    text = stringResource(R.string.approval_title),
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.approval_body_request),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.approval_body_once),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
                when (outcome) {
                    ApprovalOutcome.Failed -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.approval_error_generic),
                            style = MaterialTheme.typography.bodyMedium,
                            // Announced: the decision buttons just went disabled.
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                    ApprovalOutcome.AlreadyResolved -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.approval_already_resolved),
                            style = MaterialTheme.typography.bodyMedium,
                            // Announced: the decision buttons just went disabled.
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
                        )
                    }
                    ApprovalOutcome.AlreadySent -> {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = stringResource(R.string.approval_already_sent),
                            style = MaterialTheme.typography.bodyMedium,
                            // Announced: the decision buttons just went disabled.
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
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
                // Deny first (neutral, safe default), then Allow (risk-marked
                // in the error colour with a warning icon, the only conscious
                // approval path). Equal weights make both buttons the same
                // width; the intrinsic row height keeps them equally tall
                // even when a label wraps at a large font scale.
                Row(modifier = Modifier.height(IntrinsicSize.Min)) {
                    OutlinedButton(
                        onClick = { decide(RunApprovalChoice.DENY) },
                        enabled = buttonsEnabled,
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.onSurface
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .testTag(TAG_DENY)
                    ) {
                        Text(text = stringResource(R.string.approval_deny))
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = { decide(RunApprovalChoice.ONCE) },
                        enabled = buttonsEnabled,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .testTag(TAG_ALLOW)
                    ) {
                        // Decorative: the accessible name stays the text.
                        Icon(
                            imageVector = Icons.Filled.Warning,
                            contentDescription = null,
                            modifier = Modifier.size(ButtonDefaults.IconSize)
                        )
                        Spacer(modifier = Modifier.width(ButtonDefaults.IconSpacing))
                        Text(text = stringResource(R.string.approval_allow))
                    }
                }
                // After a decided, failed or already-answered request (and
                // while one is in flight) the decision buttons stay disabled;
                // this is the way out.
                if (outcome != null || inFlight) {
                    Spacer(modifier = Modifier.height(8.dp))
                    TextButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .align(Alignment.End)
                            .testTag(TAG_CLOSE)
                    ) {
                        Text(text = stringResource(R.string.approval_close))
                    }
                }
            }
        }
    }
}

/**
 * The approval requests this process has sent a decision for, so a
 * re-created dialog never offers a second one. Bounded; only opaque
 * run/request IDs, nothing persisted.
 */
internal object AttemptedApprovals {
    private const val MAX = 64
    private val keys = LinkedHashSet<String>()

    @Synchronized
    fun contains(key: String): Boolean = key in keys

    /** Tests share fixed request IDs across cases in one process. */
    @Synchronized
    internal fun reset() = keys.clear()

    /** Records [key]; `false` when a decision was already sent for it. */
    @Synchronized
    fun mark(key: String): Boolean {
        if (!keys.add(key)) return false
        if (keys.size > MAX) keys.remove(keys.first())
        return true
    }
}

/** Closed, non-serialised outcome states for the approval dialog. */
private enum class ApprovalOutcome {
    Failed,
    AlreadyResolved,
    AlreadySent
}

private const val TAG_SURFACE = "approval_surface"
private const val TAG_DENY = "approval_deny"
private const val TAG_ALLOW = "approval_allow"
internal const val TAG_CLOSE = "approval_close"

/**
 * Tapjacking guard for the window it is composed in: touches that arrive
 * while another app's window covers it are dropped, so an overlay cannot
 * trick the user into "Allow". Android 12+ blocks most such touches anyway;
 * this also covers Android 8-11.
 */
@Composable
internal fun ObscuredTouchGuard() {
    val view = LocalView.current
    DisposableEffect(view) {
        val previous = view.filterTouchesWhenObscured
        view.filterTouchesWhenObscured = true
        onDispose { view.filterTouchesWhenObscured = previous }
    }
}
