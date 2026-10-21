package io.github.excludebarrier.orsikrelay.ui.home

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentReadResult
import io.github.excludebarrier.orsikrelay.data.chat.ChatAttachmentSource
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentDraft
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentError
import io.github.excludebarrier.orsikrelay.domain.chat.ChatAttachmentRules
import io.github.excludebarrier.orsikrelay.domain.chat.ChatOutboundMessage
import io.github.excludebarrier.orsikrelay.domain.connection.ConnectionFailureReason
import io.github.excludebarrier.orsikrelay.domain.connection.HermesConnectionResult
import io.github.excludebarrier.orsikrelay.domain.home.HomeRunTracker
import io.github.excludebarrier.orsikrelay.domain.home.ServerConnectionCheck
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileAdmin
import io.github.excludebarrier.orsikrelay.domain.run.RunSessionController
import io.github.excludebarrier.orsikrelay.domain.run.RunStatusRequester
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreateResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreator
import io.github.excludebarrier.orsikrelay.domain.session.SessionListResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import io.github.excludebarrier.orsikrelay.ui.chat.rememberImagePicker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** How many sessions the "Recent" section shows. */
internal const val HOME_RECENT_LIMIT = 3

/**
 * The last connection result and recent sessions per profile, kept for the
 * process so returning to Home (e.g. from a chat) shows them at once while
 * they are refreshed, instead of flashing the loading skeleton. Only status
 * values and session metadata; no credential or chat content.
 */
private object HomeMemory {
    val connection = mutableMapOf<String, HomeConnection>()
    val recent = mutableMapOf<String, HomeRecent>()
}

/**
 * Wires [HomeScreen] to the existing sources. Sending from Home creates a
 * new session through [SessionCreator], starts the first turn through the
 * app-level [RunSessionController] and then opens that chat, which attaches
 * to the running turn. Nothing here decides an approval.
 */
@Composable
fun HomeRoute(
    profileId: String,
    profileAdmin: ProfileAdmin,
    connectionCheck: ServerConnectionCheck,
    sessionPageSource: SessionPageSource,
    sessionCreator: SessionCreator,
    runController: RunSessionController,
    runStatusRequester: RunStatusRequester,
    attachmentSource: ChatAttachmentSource,
    onOpenChat: (sessionId: String, title: String?) -> Unit,
    onOpenAllSessions: () -> Unit,
    onSwitchServer: () -> Unit,
    onOpenSettings: () -> Unit,
    /** No server is saved at all (e.g. the last one was deleted). */
    onNoServer: () -> Unit = {},
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    val scope = rememberCoroutineScope()
    var serverName by remember(profileId) { mutableStateOf<String?>(null) }
    LaunchedEffect(profileId) {
        val profiles = try {
            withContext(ioDispatcher) { profileAdmin.listProfiles() }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        // Only a cleared selection (deleted or unavailable profile) can mean
        // "no server left"; a resolved profile is never second-guessed.
        if (profileId.isBlank() && profiles != null && profiles.isEmpty()) {
            onNoServer()
            return@LaunchedEffect
        }
        serverName = profiles?.firstOrNull { it.id == profileId }?.profileName
    }

    var retryCount by remember { mutableIntStateOf(0) }
    var connection by remember(profileId) {
        mutableStateOf(HomeMemory.connection[profileId] ?: HomeConnection.Checking)
    }
    LaunchedEffect(profileId, retryCount) {
        // An explicit retry shows the check; a return to Home refreshes silently.
        if (retryCount > 0 || HomeMemory.connection[profileId] == null) {
            connection = HomeConnection.Checking
        }
        val result = try {
            withContext(ioDispatcher) { connectionCheck.check(profileId) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            HermesConnectionResult.Failure(ConnectionFailureReason.NETWORK)
        }
        connection = when (result) {
            is HermesConnectionResult.Success -> HomeConnection.Connected
            is HermesConnectionResult.Failure -> HomeConnection.Unreachable(result.reason)
        }
        HomeMemory.connection[profileId] = connection
    }

    var recent by remember(profileId) {
        mutableStateOf(HomeMemory.recent[profileId] ?: HomeRecent.Loading)
    }
    val connected = connection == HomeConnection.Connected
    LaunchedEffect(profileId, connected) {
        if (!connected) return@LaunchedEffect
        val result = try {
            withContext(ioDispatcher) { sessionPageSource.fetchPage(profileId, HOME_RECENT_LIMIT, 0) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            null
        }
        recent = when (result) {
            is SessionListResult.Success -> HomeRecent.Ready(result.page.sessions.take(HOME_RECENT_LIMIT))
            else -> HomeRecent.Failed
        }
        HomeMemory.recent[profileId] = recent
    }

    // Polls GET /v1/runs/{id} only while Home is at least STARTED.
    val tracker = remember(runController, runStatusRequester) {
        HomeRunTracker(runController.activeRun, runStatusRequester)
    }
    val trackedFlow = remember(tracker) { tracker.runs() }
    val tracked by trackedFlow.collectAsStateWithLifecycle(initialValue = emptyList())
    val activeRun by runController.activeRun.collectAsState()
    val readyRecent = (recent as? HomeRecent.Ready)?.sessions.orEmpty()
    val runs = tracked
        .filter { it.profileId == profileId }
        .map { run ->
            val title = readyRecent.firstOrNull { it.id == run.sessionId }?.title?.takeIf { it.isNotBlank() }
                ?: activeRun?.takeIf { it.sessionId == run.sessionId }?.userMessage
                    ?.lineSequence()?.firstOrNull()?.take(80)?.takeIf { it.isNotBlank() }
            HomeRunItem(run.sessionId, title, run.awaitingApproval, run.uncertain)
        }

    var draft by rememberSaveable(profileId) { mutableStateOf("") }
    var attachment by remember(profileId) { mutableStateOf<ChatAttachmentDraft?>(null) }
    var attachmentBusy by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<HomeNotice?>(null) }

    val pickImage = rememberImagePicker { uri ->
        attachmentBusy = true
        notice = null
        scope.launch {
            val metadata = try {
                withContext(ioDispatcher) { attachmentSource.readMetadata(uri) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            val validated = metadata?.let {
                ChatAttachmentRules.validate(
                    localId = "home",
                    contentUri = uri,
                    displayName = it.displayName,
                    mimeType = it.mimeType,
                    sizeBytes = it.sizeBytes
                )
            }
            attachment = validated?.getOrNull()
            if (attachment == null) {
                val error = (validated?.exceptionOrNull() as? ChatAttachmentRules.AttachmentRejected)?.error
                notice = when (error) {
                    ChatAttachmentError.UnsupportedType -> HomeNotice.AttachmentUnsupported
                    ChatAttachmentError.TooLarge -> HomeNotice.AttachmentTooLarge
                    else -> HomeNotice.AttachmentUnreadable
                }
            }
            attachmentBusy = false
        }
    }

    fun send() {
        if (sending) return
        // One run slot: refuse before creating a session that would stay empty.
        if (runController.activeRun.value != null) {
            notice = HomeNotice.Busy
            return
        }
        val text = draft
        val picked = attachment
        sending = true
        notice = null
        scope.launch {
            try {
                val imageUrls = if (picked == null) {
                    emptyList()
                } else {
                    val read = withContext(ioDispatcher) {
                        attachmentSource.readInlineDataUrlResult(
                            contentUri = picked.contentUri,
                            mimeType = picked.mimeType,
                            maxBytes = ChatAttachmentRules.MAX_IMAGE_BYTES
                        )
                    }
                    when (read) {
                        is ChatAttachmentReadResult.DataUrl -> listOf(read.value)
                        ChatAttachmentReadResult.TooLarge -> {
                            notice = HomeNotice.AttachmentTooLarge
                            return@launch
                        }
                        else -> {
                            notice = HomeNotice.AttachmentUnreadable
                            return@launch
                        }
                    }
                }
                val outbound = ChatOutboundMessage.create(text, imageUrls)
                if (outbound == null) {
                    notice = HomeNotice.SendFailed
                    return@launch
                }
                val created = withContext(ioDispatcher) { sessionCreator.createSession(profileId, null) }
                if (created !is SessionCreateResult.Success) {
                    notice = HomeNotice.SendFailed
                    return@launch
                }
                when (
                    runController.startTurn(
                        profileId = profileId,
                        sessionId = created.sessionId,
                        message = outbound.text,
                        imageDataUrls = outbound.imageDataUrls
                    )
                ) {
                    RunSessionController.StartTurnResult.Started -> {
                        draft = ""
                        attachment = null
                        onOpenChat(created.sessionId, created.title)
                    }
                    RunSessionController.StartTurnResult.ActiveRunConflict -> notice = HomeNotice.Busy
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                notice = HomeNotice.SendFailed
            } finally {
                sending = false
            }
        }
    }

    HomeScreen(
        state = HomeUiState(
            serverName = serverName,
            connection = connection,
            runs = runs,
            recent = recent,
            draft = draft,
            attachmentName = attachment?.displayName,
            attachmentBusy = attachmentBusy,
            sending = sending,
            notice = notice
        ),
        onDraftChange = {
            draft = it
            notice = null
        },
        onSend = ::send,
        onAttach = pickImage,
        onRemoveAttachment = { attachment = null },
        onOpenRun = { sessionId ->
            onOpenChat(sessionId, readyRecent.firstOrNull { it.id == sessionId }?.title)
        },
        onOpenSession = { session -> onOpenChat(session.id, session.title) },
        onOpenAllSessions = onOpenAllSessions,
        onSwitchServer = onSwitchServer,
        onOpenSettings = onOpenSettings,
        onRetry = { retryCount += 1 }
    )
}
