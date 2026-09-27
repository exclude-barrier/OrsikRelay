package io.github.excludebarrier.orsikrelay.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
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
    modifier: Modifier = Modifier
) {
    // A pending request itself is the fail-closed delivery proof. Do not
    // gate it on stale capability metadata (see KDoc above).
    val request = pendingApproval ?: return
    key(request.runId, request.requestId) {
        ApprovalDialog(
            request = request,
            profileId = profileId,
            requester = requester,
            onDismiss = onDismiss,
            onFinished = onFinished,
            modifier = modifier
        )
    }
}
