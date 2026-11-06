package io.github.excludebarrier.orsikrelay.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.excludebarrier.orsikrelay.domain.run.ApprovalRequest
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalRequester

/**
 * Presentation boundary for a validated pending approval.
 *
 * Capability negotiation and strict protocol-v1 validation happen before the
 * event enters the UI. Therefore this composable has no second capability
 * flag: a non-null [ApprovalRequest] is the single typed delivery proof, and
 * no request renders nothing. It holds no state, performs no I/O and persists
 * no approval content.
 */
@Composable
fun ApprovalUi(
    pendingApproval: ApprovalRequest?,
    profileId: String,
    requester: RunApprovalRequester,
    onDismiss: () -> Unit,
    onFinished: () -> Unit = {},
    /** The profile currently uses its public address (approvals refused there). */
    privateOnly: Boolean = false,
    modifier: Modifier = Modifier
) {
    // A pending request itself is the fail-closed delivery proof. Do not
    // gate it on stale capability metadata (see KDoc above).
    val request = pendingApproval ?: return
    key(request.runId, request.requestId) {
        // Hidden only in this composition; the request stays pending and
        // shows again once the private path is back.
        var hidden by remember(privateOnly) { mutableStateOf(false) }
        if (!hidden) ApprovalDialog(
            request = request,
            profileId = profileId,
            requester = requester,
            onDismiss = onDismiss,
            onFinished = onFinished,
            privateOnly = privateOnly,
            onHide = { hidden = true },
            modifier = modifier
        )
    }
}
