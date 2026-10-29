package io.github.excludebarrier.orsikrelay.ui.home

import io.github.excludebarrier.orsikrelay.domain.chat.SlashInput
import io.github.excludebarrier.orsikrelay.domain.host.HostMetricsResult

import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionFailureReason
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession

/** Reachability of the active server as the Home header shows it. */
sealed interface HomeConnection {
    data object Checking : HomeConnection
    data object Connected : HomeConnection
    data class Unreachable(val reason: ConnectionFailureReason) : HomeConnection
}

/** The last sessions of the active server. */
sealed interface HomeRecent {
    data object Loading : HomeRecent
    data class Ready(val sessions: List<HermesSession>) : HomeRecent
    data object Failed : HomeRecent
}

/** One run this app started, as a row or an approval card on Home. */
data class HomeRunItem(
    val sessionId: String,
    val title: String?,
    val awaitingApproval: Boolean,
    val uncertain: Boolean
)

/** A one-shot notice below the composer. */
enum class HomeNotice {
    SendFailed,
    Busy,
    AttachmentUnsupported,
    AttachmentTooLarge,
    AttachmentUnreadable,
    NewChatFailed,
    CommandInChat
}

/**
 * Why Home refuses to start a task, or `null` to send. A slash command is
 * never turned into an agent task here; commands work inside a chat.
 */
internal fun homeSendRefusal(draft: String, runActive: Boolean): HomeNotice? = when {
    SlashInput.parse(draft) != null -> HomeNotice.CommandInChat
    runActive -> HomeNotice.Busy
    else -> null
}

/** Everything the stateless [HomeScreen] renders. */
data class HomeUiState(
    val serverName: String? = null,
    val connection: HomeConnection = HomeConnection.Checking,
    val runs: List<HomeRunItem> = emptyList(),
    val recent: HomeRecent = HomeRecent.Loading,
    val draft: String = "",
    val attachmentName: String? = null,
    val attachmentBusy: Boolean = false,
    val sending: Boolean = false,
    val notice: HomeNotice? = null,
    /** Live server values; `null` until the first read. */
    val hostMetrics: HostMetricsResult? = null
) {
    /** The dock sends only to a reachable server and never twice. */
    val canSend: Boolean
        get() = connection == HomeConnection.Connected && !sending && !attachmentBusy &&
            (draft.isNotBlank() || attachmentName != null)

    val composerEnabled: Boolean
        get() = connection == HomeConnection.Connected && !sending
}
