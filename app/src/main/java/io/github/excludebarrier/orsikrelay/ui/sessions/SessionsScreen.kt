package io.github.excludebarrier.orsikrelay.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreator
import io.github.excludebarrier.orsikrelay.domain.session.SessionListError
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Real session list screen (Mission 004A).
 *
 * States:
 * - initial [SessionListState.Loading];
 * - success: server-ordered list with title, optional source, message
 *   count, pin status and locally formatted last activity;
 * - empty: only when the server really delivered an empty `data` list;
 * - failure: generic German text with manual "Erneut versuchen".
 *
 * "Aktualisieren" reloads page 0; "Mehr laden" appends the next page and
 * only appears when `has_more` is true. A load in flight blocks every
 * further load, so double taps never start a parallel second request.
 * No automatic retry.
 *
 * [pageSource] is the test seam; production injects the repository.
 * The API key and raw response/exception text never enter the state.
 */
@Composable
fun SessionsScreen(
    pageSource: SessionPageSource,
    profileId: String,
    modifier: Modifier = Modifier,
    creator: SessionCreator? = null,
    onSessionSelected: (sessionId: String, title: String?) -> Unit = { _, _ -> },
    onSessionCreated: (sessionId: String) -> Unit = {}
) {
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) { configuration.locales[0] }
    val coroutineScope = rememberCoroutineScope()
    var state by remember { mutableStateOf<SessionListState>(SessionListState.Loading) }
    var loadInFlight by remember { mutableStateOf(false) }
    var showNewSessionDialog by remember { mutableStateOf(false) }
    val canCreateSession = !profileId.isBlank() && creator != null

    fun loadPage(offset: Int, append: Boolean) {
        if (loadInFlight) return
        loadInFlight = true
        if (offset == 0) {
            state = SessionListState.Loading
        }
        coroutineScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    pageSource.fetchPage(
                        profileId = profileId,
                        limit = PAGE_SIZE,
                        offset = offset
                    )
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                io.github.excludebarrier.orsikrelay.domain.session.SessionListResult.Failure(
                    SessionListError.Network
                )
            }
            when (result) {
                is io.github.excludebarrier.orsikrelay.domain.session.SessionListResult.Success -> {
                    val page = result.page
                    state = SessionListState.Ready(
                        sessions = if (append) {
                            val previous = (state as? SessionListState.Ready)?.sessions
                                ?: emptyList()
                            previous + page.sessions
                        } else {
                            page.sessions
                        },
                        hasMore = page.hasMore
                    )
                }

                is io.github.excludebarrier.orsikrelay.domain.session.SessionListResult.Failure -> {
                    state = SessionListState.Failed(result.error)
                }
            }
            loadInFlight = false
        }
    }

    LaunchedEffect(profileId) {
        loadPage(offset = 0, append = false)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
        Text(
            text = stringResource(R.string.sessions_title),
            style = MaterialTheme.typography.headlineMedium,
            modifier = Modifier.padding(top = 16.dp)
        )
        Button(
            onClick = { loadPage(offset = 0, append = false) },
            modifier = Modifier
                .padding(top = 8.dp)
                .padding(start = 8.dp)
        ) {
            Text(text = stringResource(R.string.sessions_refresh))
        }
        when (val current = state) {
            is SessionListState.Loading -> {
                Text(
                    text = stringResource(R.string.sessions_loading),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                )
            }

            is SessionListState.Failed -> {
                val message = when (current.error) {
                    SessionListError.MissingProfile ->
                        stringResource(R.string.error_session_profile)
                    else ->
                        stringResource(R.string.error_session_generic)
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Button(
                        onClick = { loadPage(offset = 0, append = false) },
                        modifier = Modifier.padding(top = 16.dp)
                    ) {
                        Text(text = stringResource(R.string.sessions_retry))
                    }
                }
            }

            is SessionListState.Ready -> {
                if (current.sessions.isEmpty()) {
                    Text(
                        text = stringResource(R.string.sessions_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 24.dp)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) {
                        items(current.sessions, key = { it.id }) { session ->
                            SessionRow(
                                session = session,
                                locale = locale,
                                onClick = { onSessionSelected(session.id, session.title) }
                            )
                        }
                        if (current.hasMore) {
                            item {
                                val offset = current.sessions.size
                                Button(
                                    onClick = { loadPage(offset = offset, append = true) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(8.dp)
                                ) {
                                    Text(text = stringResource(R.string.sessions_load_more))
                                }
                            }
                        }
                    }
                }
            }
        }
        }

        if (canCreateSession) {
            FloatingActionButton(
                onClick = { showNewSessionDialog = true },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.new_session_fab)
                )
            }
        }
    }

    if (showNewSessionDialog && creator != null) {
        NewSessionDialog(
            creator = creator,
            profileId = profileId,
            onDismiss = { showNewSessionDialog = false },
            onCreated = { sessionId ->
                showNewSessionDialog = false
                onSessionCreated(sessionId)
            }
        )
    }
}

@Composable
private fun SessionRow(
    session: HermesSession,
    locale: Locale,
    onClick: () -> Unit
) {
    val title = session.title?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.session_fallback_title)
    val source = session.source?.takeIf { it.isNotBlank() }
    val lastActive = SessionListFormatter.formatLastActive(
        session.lastActiveEpochSeconds,
        locale
    )
    val messageCount = SessionListFormatter.formatMessageCount(
        session.messageCount,
        locale
    )
    val pinned = if (session.pinned) {
        stringResource(R.string.session_pinned)
    } else {
        null
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium
        )
        buildDetailLine(source, pinned)
            ?.let { detail ->
                Text(
                    text = detail,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        buildMetaLine(messageCount, lastActive)
            ?.let { meta ->
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall
                )
            }
    }
}

private fun buildDetailLine(source: String?, pinned: String?): String? {
    val parts = listOfNotNull(source, pinned)
    return parts.joinToString(separator = " · ")
}

private fun buildMetaLine(messageCount: String?, lastActive: String?): String? {
    val parts = listOfNotNull(messageCount, lastActive)
    return parts.joinToString(separator = " · ")
}

private const val PAGE_SIZE = 50
