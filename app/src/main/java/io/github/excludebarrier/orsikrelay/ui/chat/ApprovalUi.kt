package io.github.excludebarrier.orsikrelay.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.excludebarrier.orsikrelay.domain.run.ApprovalRequest
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalRequester

/**
 * Capability gate for the run-approval UI (Mission 014).
 *
 * This is the single switch that keeps the approval UI invisible unless
 * the server CONFIRMED the approval capability AND a pending, UI-safe
 * [ApprovalRequest] is present:
 *
 * - [approvalUiEnabled] must be `true`. It is derived ONLY from the
 *   server's `features.run_approval_response` capability flag (read
 *   fail-closed: absent or non-boolean means `false`). Without a
 *   confirmed capability, NOTHING is rendered — even if a request is
 *   supplied — so no approval UI can ever appear for a server that does
 *   not advertise the capability.
 * - [pendingApproval] must be non-null. It is the closed, UI-safe model
 *   of exactly one pending approval; with no request there is nothing to
 *   show.
 *
 * Both conditions are AND-ed: the gate is `enabled && request != null`.
 * This composable holds no state and performs no I/O; the actual dialog
 * is [ApprovalDialog]. No approval content is persisted here.
 */
@Composable
fun ApprovalUi(
    approvalUiEnabled: Boolean,
    pendingApproval: ApprovalRequest?,
    profileId: String,
    requester: RunApprovalRequester,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!approvalUiEnabled) return
    val request = pendingApproval ?: return
    ApprovalDialog(
        request = request,
        profileId = profileId,
        requester = requester,
        onDismiss = onDismiss,
        modifier = modifier
    )
}
