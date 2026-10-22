package io.github.excludebarrier.orsikrelay.ui.sessions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import io.github.excludebarrier.orsikrelay.domain.session.HermesSession
import io.github.excludebarrier.orsikrelay.domain.session.SessionManager
import io.github.excludebarrier.orsikrelay.domain.session.SessionManageResult
import io.github.excludebarrier.orsikrelay.domain.session.SessionManageError
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikDialog
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.ui.home.PreviewHarnesses
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.excludebarrier.orsikrelay.ui.components.GroupGap
import io.github.excludebarrier.orsikrelay.ui.components.EmptyState
import io.github.excludebarrier.orsikrelay.ui.components.OrsikLoadingIndicator
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikShapes
import androidx.compose.material.icons.filled.Warning
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import io.github.excludebarrier.orsikrelay.ui.components.OrsikLargeHeader
import io.github.excludebarrier.orsikrelay.ui.components.PreviewBadge
import io.github.excludebarrier.orsikrelay.ui.components.dashedBorder
import io.github.excludebarrier.orsikrelay.ui.components.groupPosition
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikType
import io.github.excludebarrier.orsikrelay.domain.session.SessionCreator
import io.github.excludebarrier.orsikrelay.domain.session.SessionListError
import io.github.excludebarrier.orsikrelay.domain.session.SessionPageSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock

/**
 * Real session list screen.
 *
 * States:
 * - initial [SessionListState.Loading];
 * - success: server-ordered list grouped into pinned, today, yesterday,
 *   the last seven days and older, with compact rows (title, source icon and
 *   readable source name, message count and last activity) and no dividers;
 *   empty groups are omitted;
 * - empty: only when the server really delivered an empty `data` list;
 * - failure: a localized generic text, an actionable hint where the
 *   failure allows one, and a manual retry.
 *
 * Pull-to-refresh reloads page 0 and stays available in the empty and error
 * states; the error state also keeps its visible retry button. Screen readers
 * reach the same refresh through the overflow menu. Load-more appends the next
 * page and only appears when `has_more` is true. A load in flight blocks every
 * further load, so double taps never start a parallel second request. No
 * automatic retry.
 *
 * Creating a session is a rounded extended floating action button in the
 * bottom-end corner; it opens the same [NewSessionDialog], is absent without a
 * creator, and collapses to its icon once the list is scrolled down. The list
 * reserves bottom padding so the button never covers the last row.
 *
 * The filled, pill-shaped search field under the header filters the already
 * loaded sessions locally by title ([SessionSearch]); it never issues a
 * request. The query survives recreation, refresh and loading more, is not
 * persisted, and is
 * cleared only by the clear button. While a non-blank query is active and
 * the server has more pages, a hint explains that only loaded sessions are
 * searched and load-more stays reachable, also with zero hits.
 *
 * There is no header menu: servers live in the Server tab, settings and
 * "about" behind the gear on Home.
 *
 * [pageSource] is the test seam; production injects the repository.
 * [clock] is the test seam for deterministic date grouping; production uses
 * the system clock and zone. The API key and raw response/exception text
 * never enter the state.
 */
@Composable
fun SessionsScreen(
    pageSource: SessionPageSource,
    profileId: String,
    modifier: Modifier = Modifier,
    creator: SessionCreator? = null,
    onSessionSelected: (sessionId: String, title: String?) -> Unit = { _, _ -> },
    onSessionCreated: (sessionId: String) -> Unit = {},
    runningSessionId: String? = null,
    manager: SessionManager? = null,
    clock: Clock = Clock.systemDefaultZone()
) {
    val configuration = LocalConfiguration.current
    val locale = remember(configuration) { configuration.locales[0] }
    val focusManager = LocalFocusManager.current
    val coroutineScope = rememberCoroutineScope()
    var state by remember { mutableStateOf<SessionListState>(SessionListState.Loading) }
    var loadInFlight by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var showNewSessionDialog by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var hadSessions by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val canCreateSession = !profileId.isBlank() && creator != null
    val refreshLabel = stringResource(R.string.sessions_refresh)
    val snackbarHostState = remember { SnackbarHostState() }
    // Archive/delete are offered only when the server advertises them.
    var canManage by remember(profileId, manager) { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<String?>(null) }
    var deleteCandidate by remember { mutableStateOf<HermesSession?>(null) }
    var manageInFlight by remember { mutableStateOf(false) }
    val archivedLabel = stringResource(R.string.sessions_archived)
    val undoLabel = stringResource(R.string.sessions_undo)
    val deletedLabel = stringResource(R.string.sessions_deleted)
    val activeLabel = stringResource(R.string.sessions_manage_active)
    val failedLabel = stringResource(R.string.sessions_manage_failed)

    LaunchedEffect(profileId, manager) {
        canManage = manager != null && profileId.isNotBlank() &&
            withContext(Dispatchers.IO) { manager.supportsManagement(profileId) }
    }

    fun removeLocally(sessionId: String) {
        (state as? SessionListState.Ready)?.let { ready ->
            state = ready.copy(sessions = ready.sessions.filterNot { it.id == sessionId })
        }
    }

    fun restoreLocally(session: HermesSession, index: Int) {
        (state as? SessionListState.Ready)?.let { ready ->
            if (ready.sessions.any { it.id == session.id }) return
            val sessions = ready.sessions.toMutableList()
            sessions.add(index.coerceIn(0, sessions.size), session)
            state = ready.copy(sessions = sessions)
        }
    }

    /**
     * Runs one archive/delete request (never retried). The row disappears
     * only after the server confirmed it (or confirms the chat is gone);
     * a running turn and every other failure keep the row and say so.
     */
    fun manage(
        session: HermesSession,
        request: (SessionManager) -> SessionManageResult,
        onDone: suspend () -> Unit
    ) {
        val sessionManager = manager ?: return
        if (manageInFlight) return
        manageInFlight = true
        coroutineScope.launch {
            val result = withContext(Dispatchers.IO) { request(sessionManager) }
            manageInFlight = false
            when (result) {
                SessionManageResult.Success -> {
                    removeLocally(session.id)
                    onDone()
                }
                is SessionManageResult.Failure -> when (result.error) {
                    SessionManageError.NotFound -> removeLocally(session.id)
                    SessionManageError.ActiveTurn -> snackbarHostState.showSnackbar(activeLabel)
                    SessionManageError.Failed -> snackbarHostState.showSnackbar(failedLabel)
                }
            }
        }
    }

    fun archive(session: HermesSession) {
        val index = (state as? SessionListState.Ready)?.sessions?.indexOfFirst { it.id == session.id } ?: 0
        manage(session, { it.setArchived(profileId, session.id, archived = true) }) {
            val undo = snackbarHostState.showSnackbar(
                message = archivedLabel,
                actionLabel = undoLabel,
                duration = SnackbarDuration.Long
            )
            if (undo == SnackbarResult.ActionPerformed) {
                val restored = manager?.let { sessionManager ->
                    withContext(Dispatchers.IO) { sessionManager.setArchived(profileId, session.id, archived = false) }
                }
                if (restored == SessionManageResult.Success) {
                    restoreLocally(session, index)
                } else {
                    snackbarHostState.showSnackbar(failedLabel)
                }
            }
        }
    }

    fun delete(session: HermesSession) {
        manage(session, { it.delete(profileId, session.id) }) {
            snackbarHostState.showSnackbar(deletedLabel)
        }
    }

    fun loadPage(offset: Int, append: Boolean, pullRefresh: Boolean = false) {
        if (loadInFlight) return
        loadInFlight = true
        if (pullRefresh) {
            isRefreshing = true
        } else if (offset == 0) {
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
            isRefreshing = false
        }
    }

    LaunchedEffect(profileId) {
        loadPage(offset = 0, append = false)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
        OrsikLargeHeader(
            title = stringResource(R.string.sessions_title),
            modifier = Modifier.testTag(SESSIONS_HEADER_TAG)
        )
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
        // Refreshing is pull-to-refresh; screen readers get the same
        // refresh as an accessibility action on the list.
        // Searching only makes sense with sessions to search: the field is
        // hidden on an error, for an empty list and before the first page,
        // but stays during a refresh (no layout jump) and while a query is
        // active, so it can always be cleared.
        (state as? SessionListState.Ready)?.let { hadSessions = it.sessions.isNotEmpty() }
        val searchable = searchQuery.isNotEmpty() ||
            (hadSessions && state !is SessionListState.Failed)
        if (searchable) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("sessionsSearchField"),
                placeholder = { Text(text = stringResource(R.string.sessions_search_placeholder), fontSize = 16.sp) },
                leadingIcon = {
                    Icon(
                        imageVector = OrsikIcons.Search,
                        contentDescription = null,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                },
                trailingIcon = if (searchQuery.isNotEmpty()) {
                    {
                        IconButton(
                            onClick = { searchQuery = "" },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("sessionsClearSearch")
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Clear,
                                contentDescription = stringResource(R.string.sessions_search_clear)
                            )
                        }
                    }
                } else {
                    null
                },
                singleLine = true,
                shape = RoundedCornerShape(percent = 50),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                    unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent,
                    disabledIndicatorColor = Color.Transparent,
                    errorIndicatorColor = Color.Transparent
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { focusManager.clearFocus() }
                )
            )
        }
        AgentFilterChips(modifier = Modifier.padding(top = 12.dp))
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { loadPage(offset = 0, append = false, pullRefresh = true) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction(refreshLabel) {
                            loadPage(offset = 0, append = false)
                            true
                        }
                    )
                }
                .testTag("sessionsPullToRefresh")
        ) {
            when (val current = state) {
                is SessionListState.Loading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        EmptyState(
                            title = stringResource(R.string.sessions_loading),
                            leading = { OrsikLoadingIndicator(size = 56.dp) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 48.dp)
                        )
                    }
                }

                is SessionListState.Failed -> {
                    val message = when (current.error) {
                        SessionListError.MissingProfile ->
                            stringResource(R.string.error_session_profile)
                        else ->
                            stringResource(R.string.error_session_generic)
                    }
                    val hint = sessionErrorHint(current.error)
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(top = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        EmptyState(
                            title = message,
                            hint = hint,
                            hintTestTag = "sessionsErrorHint",
                            leading = {
                                Icon(
                                    Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier
                                        .size(40.dp)
                                        .testTag("sessionsErrorIcon")
                                )
                            },
                            action = {
                                Button(
                                    onClick = { loadPage(offset = 0, append = false) },
                                    shape = OrsikShapes.pill
                                ) {
                                    Text(text = stringResource(R.string.sessions_retry))
                                }
                            }
                        )
                    }
                }

                is SessionListState.Ready -> {
                    val now = remember(current.sessions) { clock.instant() }
                    val searching = searchQuery.isNotBlank()
                    val filteredSessions = remember(current.sessions, searchQuery, locale) {
                        SessionSearch.filter(current.sessions, searchQuery, locale)
                    }
                    val sections = remember(filteredSessions, now) {
                        SessionGrouping.groupSessions(
                            sessions = filteredSessions,
                            now = now,
                            zone = clock.zone
                        )
                    }
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (searching && current.hasMore) {
                            Text(
                                text = stringResource(R.string.sessions_search_only_loaded_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp)
                                    .testTag("sessionsSearchLoadMoreHint")
                            )
                        }
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag(SESSIONS_LIST_TAG),
                            // Bottom padding clears the floating 56dp FAB
                            // (56 + 16 margin) so the last session or the
                            // "load more" button is never hidden underneath.
                            contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp)
                        ) {
                            if (filteredSessions.isEmpty()) {
                                item(key = "empty") {
                                    EmptyState(
                                        title = if (searching) {
                                            stringResource(
                                                R.string.sessions_search_no_results,
                                                SessionSearch.displayQuery(searchQuery)
                                            )
                                        } else {
                                            stringResource(R.string.sessions_empty)
                                        },
                                        icon = if (searching) OrsikIcons.Search else OrsikIcons.Chat,
                                        hint = if (searching || !canCreateSession) {
                                            null
                                        } else {
                                            stringResource(R.string.sessions_empty_hint)
                                        },
                                        titleTestTag = if (searching) {
                                            "sessionsSearchNoResults"
                                        } else {
                                            "sessionsEmpty"
                                        },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 40.dp)
                                    )
                                }
                            }
                            sections.forEach { section ->
                                item(key = "section-${section.group.name}") {
                                    SessionGroupHeader(section.group)
                                }
                                itemsIndexed(
                                    items = section.sessions,
                                    key = { _, session -> "session-${session.id}" }
                                ) { index, session ->
                                    Box(modifier = Modifier.padding(bottom = GroupGap)) {
                                    SessionListItem(
                                        session = session,
                                        position = groupPosition(index, section.sessions.size),
                                        locale = locale,
                                        zone = clock.zone,
                                        showTimeOnly = SessionGrouping.dayGroupOf(
                                            session.lastActiveEpochSeconds,
                                            now,
                                            clock.zone
                                        ).let {
                                            it == SessionGroup.Today || it == SessionGroup.Yesterday
                                        },
                                        onClick = {
                                            onSessionSelected(session.id, session.title)
                                        },
                                        running = session.id == runningSessionId,
                                        showPin = true,
                                        onLongClick = if (canManage && session.id != runningSessionId) {
                                            { actionsFor = session.id }
                                        } else {
                                            null
                                        },
                                        modifier = Modifier.testTag("sessionRow:${session.id}")
                                    )
                                    SessionActionsMenu(
                                        expanded = actionsFor == session.id,
                                        // One request at a time: never a silently dropped tap.
                                        enabled = !manageInFlight,
                                        onDismiss = { actionsFor = null },
                                        onArchive = {
                                            actionsFor = null
                                            archive(session)
                                        },
                                        onDelete = {
                                            actionsFor = null
                                            deleteCandidate = session
                                        }
                                    )
                                    }
                                }
                            }
                            if (current.hasMore && (searching || current.sessions.isNotEmpty())) {
                                item(key = "load-more") {
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
        }
        }

        if (canCreateSession) {
            val newSessionLabel = stringResource(R.string.new_session_fab)
            ExtendedFloatingActionButton(
                text = { Text(text = newSessionLabel, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
                icon = {
                    Icon(
                        imageVector = OrsikIcons.Plus,
                        contentDescription = null
                    )
                },
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                onClick = { showNewSessionDialog = true },
                expanded = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp)
                    .testTag("sessionsNewSessionFab")
                    .semantics { contentDescription = newSessionLabel }
            )
        }
        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 88.dp)
        )
    }

    deleteCandidate?.let { session ->
        DeleteSessionDialog(
            title = session.title?.takeIf { it.isNotBlank() }
                ?: stringResource(R.string.session_fallback_title),
            onConfirm = {
                deleteCandidate = null
                delete(session)
            },
            onDismiss = { deleteCandidate = null }
        )
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
private fun SessionGroupHeader(group: SessionGroup) {
    Text(
        text = stringResource(group.titleRes()),
        style = OrsikType.groupLabel,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 6.dp, top = 14.dp, bottom = 8.dp)
            .semantics { heading() }
    )
}

private fun SessionGroup.titleRes(): Int = when (this) {
    SessionGroup.Pinned -> R.string.sessions_group_pinned
    SessionGroup.Today -> R.string.sessions_group_today
    SessionGroup.Yesterday -> R.string.sessions_group_yesterday
    SessionGroup.LastSevenDays -> R.string.sessions_group_last_7_days
    SessionGroup.Older -> R.string.sessions_group_older
}

@Composable
private fun sessionErrorHint(error: SessionListError): String? = when (error) {
    SessionListError.MissingProfile ->
        stringResource(R.string.sessions_error_hint_missing_profile)
    SessionListError.Network,
    SessionListError.Timeout ->
        stringResource(R.string.sessions_error_hint_unreachable)
    SessionListError.Tls ->
        stringResource(R.string.sessions_error_hint_tls)
    SessionListError.InvalidCredentials ->
        stringResource(R.string.sessions_error_hint_credentials)
    SessionListError.RateLimited ->
        stringResource(R.string.sessions_error_hint_rate_limited)
    SessionListError.IncompatibleApi ->
        stringResource(R.string.sessions_error_hint_incompatible_api)
    SessionListError.InvalidInput,
    SessionListError.Http -> null
}

internal const val SESSIONS_HEADER_TAG = "sessions_header"
internal const val SESSIONS_LIST_TAG = "sessions_list"
internal const val AGENT_FILTER_HERMES_TAG = "sessions_filter_hermes"
internal const val AGENT_FILTER_PREVIEW_PREFIX = "sessions_filter_preview_"

/**
 * The agent filter of design v2. Hermes is the only agent today and stays
 * selected; the future agents ([PreviewHarnesses], same list as the Home
 * picker) mark their place, dashed and not selectable.
 */
@Composable
private fun AgentFilterChips(modifier: Modifier = Modifier) {
    val soon = stringResource(R.string.home_soon_badge)
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
    ) {
        FilterChip(
            selected = true,
            onClick = {},
            label = { Text("Hermes", fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold) },
            leadingIcon = { Icon(OrsikIcons.Check, contentDescription = null, modifier = Modifier.size(16.dp)) },
            shape = RoundedCornerShape(10.dp),
            colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
            ),
            border = null,
            modifier = Modifier.testTag(AGENT_FILTER_HERMES_TAG)
        )
        PreviewHarnesses.forEach { harness ->
            val key = harness.key
            val name = harness.name
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .heightIn(min = 34.dp)
                    .dashedBorder(MaterialTheme.colorScheme.outlineVariant, 10.dp)
                    .padding(horizontal = 12.dp)
                    .semantics(mergeDescendants = true) {
                        disabled()
                        contentDescription = "$name, $soon"
                    }
                    .testTag(AGENT_FILTER_PREVIEW_PREFIX + key)
            ) {
                Text(name, fontSize = 13.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                PreviewBadge(text = soon)
            }
        }
    }
}

private const val PAGE_SIZE = 50

/** Long-press menu of one chat: archive, delete. */
@Composable
private fun SessionActionsMenu(
    expanded: Boolean,
    enabled: Boolean,
    onDismiss: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            text = { Text(stringResource(R.string.sessions_archive)) },
            leadingIcon = { Icon(OrsikIcons.Archive, contentDescription = null) },
            onClick = onArchive,
            enabled = enabled,
            modifier = Modifier.testTag(SESSION_ARCHIVE_TAG)
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.sessions_delete), color = MaterialTheme.colorScheme.error) },
            leadingIcon = { Icon(OrsikIcons.Trash, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            onClick = onDelete,
            enabled = enabled,
            modifier = Modifier.testTag(SESSION_DELETE_TAG)
        )
    }
}

/** Irreversible: deleting needs an explicit confirmation naming the chat (list and chat). */
@Composable
internal fun DeleteSessionDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = OrsikDialog.shape,
        containerColor = OrsikDialog.containerColor,
        tonalElevation = OrsikDialog.tonalElevation,
        title = { Text(stringResource(R.string.sessions_delete_title)) },
        text = { Text(stringResource(R.string.sessions_delete_text, title)) },
        confirmButton = {
            TextButton(onClick = onConfirm, modifier = Modifier.testTag(SESSION_DELETE_CONFIRM_TAG)) {
                Text(stringResource(R.string.sessions_delete_confirm), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.sessions_delete_cancel)) }
        },
        modifier = Modifier.testTag(SESSION_DELETE_DIALOG_TAG)
    )
}

internal const val SESSION_ARCHIVE_TAG = "sessionArchive"
internal const val SESSION_DELETE_TAG = "sessionDelete"
internal const val SESSION_DELETE_DIALOG_TAG = "sessionDeleteDialog"
internal const val SESSION_DELETE_CONFIRM_TAG = "sessionDeleteConfirm"
