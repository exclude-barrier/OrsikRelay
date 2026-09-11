package io.github.excludebarrier.orsikrelay.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import io.github.excludebarrier.orsikrelay.domain.run.ApprovalRequest
import io.github.excludebarrier.orsikrelay.domain.run.RunApprovalRequester

/**
 * Capability gate for the run-approval UI (Mission 014).
 *
 * A strictly validated, pending [ApprovalRequest] is sufficient to render
 * the dialog. The event is server-authoritative: it arrived on the active,
 * authenticated SSE turn and carries the exact run/request identifiers the
 * response endpoint requires. Requiring a separately persisted capability
 * flag here would deadlock a newly upgraded server: the server waits for a
 * decision while an old app profile hides the valid request.
 *
 * [approvalUiEnabled] remains a compatibility hint for callers, but it must
 * never suppress a valid pending request. With no request, nothing renders.
 * This composable holds no state and performs no I/O; the actual dialog is
 * [ApprovalDialog]. No approval content is persisted here.
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
    // A pending request itself is the fail-closed delivery proof. Do not
    // gate it on stale capability metadata (see KDoc above).
    val request = pendingApproval ?: return
    ApprovalDialog(
        request = request,
        profileId = profileId,
        requester = requester,
        onDismiss = onDismiss,
        modifier = modifier
    )
}
