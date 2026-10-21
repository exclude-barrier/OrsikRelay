package io.github.excludebarrier.orsikrelay.ui.sessions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import java.time.Clock
import java.time.ZoneId
import java.util.Locale

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
 * The header's overflow menu links to server management, settings and the
 * about screen; the appearance switcher lives in settings now.
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
    onOpenProfileManagement: () -> Unit = {},
    onOpenSettings: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
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
    var showOverflowMenu by remember { mutableStateOf(false) }
    var searchQuery by rememberSaveable { mutableStateOf("") }
    var hadSessions by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val canCreateSession = !profileId.isBlank() && creator != null

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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.sessions_title),
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.weight(1f))
            Box {
                IconButton(
                    onClick = { showOverflowMenu = true },
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.sessions_more_options)
                    )
                }
                DropdownMenu(
                    expanded = showOverflowMenu,
                    onDismissRequest = { showOverflowMenu = false }
                ) {
                    Column(modifier = Modifier.testTag("sessions_overflow_menu")) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.sessions_refresh)) },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Filled.Refresh,
                                    contentDescription = null
                                )
                            },
                            modifier = Modifier.testTag("sessions_menu_refresh"),
                            onClick = {
                                showOverflowMenu = false
                                loadPage(offset = 0, append = false)
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.profiles_manage)) },
                            modifier = Modifier.testTag("sessions_menu_manage_servers"),
                            onClick = {
                                showOverflowMenu = false
                                onOpenProfileManagement()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.settings_entry)) },
                            modifier = Modifier.testTag("sessions_menu_settings"),
                            onClick = {
                                showOverflowMenu = false
                                onOpenSettings()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(text = stringResource(R.string.settings_about_section)) },
                            modifier = Modifier.testTag("sessions_menu_about"),
                            onClick = {
                                showOverflowMenu = false
                                onOpenAbout()
                            }
                        )
                    }
                }
            }
        }
        // Refreshing stays reachable for screen readers through the
        // overflow menu; the visible control is pull-to-refresh.
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
                    .padding(bottom = 8.dp)
                    .testTag("sessionsSearchField"),
                placeholder = { Text(text = stringResource(R.string.sessions_search_placeholder)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = null
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
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { loadPage(offset = 0, append = false, pullRefresh = true) },
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .testTag("sessionsPullToRefresh")
        ) {
            when (val current = state) {
                is SessionListState.Loading -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = stringResource(R.string.sessions_loading),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = 24.dp)
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
                            .padding(top = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center
                        )
                        if (hint != null) {
                            Text(
                                text = hint,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .testTag("sessionsErrorHint")
                            )
                        }
                        Button(
                            onClick = { loadPage(offset = 0, append = false) },
                            modifier = Modifier.padding(top = 16.dp)
                        ) {
                            Text(text = stringResource(R.string.sessions_retry))
                        }
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
                            modifier = Modifier.fillMaxSize(),
                            // Bottom padding clears the floating 56dp FAB
                            // (56 + 16 margin) so the last session or the
                            // "load more" button is never hidden underneath.
                            contentPadding = PaddingValues(top = 4.dp, bottom = 88.dp)
                        ) {
                            if (filteredSessions.isEmpty()) {
                                item(key = "empty") {
                                    Text(
                                        text = if (searching) {
                                            stringResource(
                                                R.string.sessions_search_no_results,
                                                SessionSearch.displayQuery(searchQuery)
                                            )
                                        } else {
                                            stringResource(R.string.sessions_empty)
                                        },
                                        style = MaterialTheme.typography.bodyLarge,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 24.dp)
                                            .testTag(
                                                if (searching) {
                                                    "sessionsSearchNoResults"
                                                } else {
                                                    "sessionsEmpty"
                                                }
                                            )
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
                                ) { _, session ->
                                    SessionRow(
                                        session = session,
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
                                        }
                                    )
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

        if (canCreateSession) {
            val newSessionLabel = stringResource(R.string.new_session_fab)
            ExtendedFloatingActionButton(
                text = { Text(text = newSessionLabel) },
                icon = {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = null
                    )
                },
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
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 8.dp)
            .semantics { heading() }
    )
}

@Composable
internal fun SessionRow(
    session: HermesSession,
    locale: Locale,
    zone: ZoneId,
    showTimeOnly: Boolean,
    onClick: () -> Unit
) {
    val title = session.title?.takeIf { it.isNotBlank() }
        ?: stringResource(R.string.session_fallback_title)
    val source = sessionSourceDescriptor(session.source)
    val sourceName = source.displayName()
    val sourceIcon = source.icon()
    val lastActive = if (showTimeOnly) {
        SessionListFormatter.formatLastActiveTime(
            session.lastActiveEpochSeconds,
            locale,
            zone
        )
    } else {
        SessionListFormatter.formatLastActiveDate(
            session.lastActiveEpochSeconds,
            locale,
            zone
        )
    }
    val messageCount = session.messageCount?.let { count ->
        pluralStringResource(
            R.plurals.session_message_count,
            count.coerceIn(0L, Int.MAX_VALUE.toLong()).toInt(),
            SessionListFormatter.formatMessageCount(count, locale).orEmpty()
        )
    }
    val meta = buildMetaLine(sourceName, messageCount, lastActive)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (sourceIcon != null) {
            Icon(
                imageVector = sourceIcon,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
        } else {
            Spacer(modifier = Modifier.size(24.dp))
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (meta != null) {
                Text(
                    text = meta,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun SessionSourceDescriptor.displayName(): String? = when (this) {
    SessionSourceDescriptor.Missing -> null
    SessionSourceDescriptor.Terminal -> stringResource(R.string.session_source_terminal)
    SessionSourceDescriptor.Telegram -> stringResource(R.string.session_source_telegram)
    SessionSourceDescriptor.Email -> stringResource(R.string.session_source_email)
    SessionSourceDescriptor.Sms -> stringResource(R.string.session_source_sms)
    SessionSourceDescriptor.WhatsApp -> stringResource(R.string.session_source_whatsapp)
    SessionSourceDescriptor.Signal -> stringResource(R.string.session_source_signal)
    SessionSourceDescriptor.Scheduled -> stringResource(R.string.session_source_scheduled)
    is SessionSourceDescriptor.Other -> rawName
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

private fun buildMetaLine(
    sourceName: String?,
    messageCount: String?,
    lastActive: String?
): String? {
    val parts = listOfNotNull(sourceName, messageCount, lastActive)
    return parts.joinToString(separator = " · ")
}

private const val PAGE_SIZE = 50
