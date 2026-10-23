package io.github.excludebarrier.orsikrelay.ui.terminal

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.util.Base64
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalCloseReason
import io.github.excludebarrier.orsikrelay.domain.terminal.TerminalSource
import io.github.excludebarrier.orsikrelay.security.applock.AppAuthenticator
import io.github.excludebarrier.orsikrelay.ui.common.NoticeAction
import io.github.excludebarrier.orsikrelay.ui.common.NoticeCard
import io.github.excludebarrier.orsikrelay.ui.common.NoticeTone
import io.github.excludebarrier.orsikrelay.ui.components.AgentComposerDock
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import io.github.excludebarrier.orsikrelay.ui.files.FilesAskResult
import io.github.excludebarrier.orsikrelay.ui.files.dashedOutline
import io.github.excludebarrier.orsikrelay.ui.files.sensitivePlainText
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikMono
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikTheme
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

internal object TerminalTags {
    const val SCREEN = "terminal_screen"
    const val VIEW = "terminal_view"
    const val KEYS = "terminal_keys"
    const val RECONNECT = "terminal_reconnect"
    const val CLOSE = "terminal_close"
    const val CANCEL = "terminal_cancel"
    const val UNLOCK = "terminal_unlock"
    const val CONNECTING = "terminal_connecting"
    const val DISCONNECTED = "terminal_disconnected"
    const val SUBTITLE = "terminal_subtitle"
    const val MODIFIER_HINT = "terminal_modifier_hint"
    const val SELECTION_BAR = "terminal_selection_bar"
    const val COPY = "terminal_copy"
    const val PASTE = "terminal_paste"
    const val TO_HERMES = "terminal_to_hermes"
    const val LAST_OUTPUT = "terminal_last_output"
    const val COMPOSER = "terminal_composer"
    const val KEYBOARD = "terminal_keyboard"
    const val FONT = "terminal_font"
    const val FONT_SMALLER = "terminal_font_smaller"
    const val FONT_LARGER = "terminal_font_larger"
    const val MORE = "terminal_more"
    const val SESSIONS = "terminal_sessions"
    const val TABS = "terminal_tabs"
}

/**
 * Carries an unlock across a configuration change (rotation) of the same
 * visit, never across process death or a return from the background.
 */
internal object TerminalUnlockCarry {
    private val carried = AtomicBoolean(false)
    fun carry() = carried.set(true)
    fun take(): Boolean = carried.getAndSet(false)
}

/** Hands terminal text to Hermes: a new session whose first message holds it as a code block. */
internal fun interface TerminalHermes {
    suspend fun ask(context: String, question: String): FilesAskResult
}

/**
 * The server shell of the active profile, in xterm.js (design v2). Every
 * visit (and every return from the background) asks for the device
 * credential first; leaving the screen closes the connection while the
 * tmux session keeps running on the server.
 */
@Composable
internal fun TerminalRoute(
    profileId: String,
    source: TerminalSource,
    authenticator: AppAuthenticator?,
    onBack: () -> Unit,
    serverLabel: String = "",
    hermes: TerminalHermes? = null,
    fontStore: TerminalFontStore? = null
) {
    // Checked before anything else: an old WebView must not open a shell
    // on the server that it then cannot show.
    val webViewSupported = remember { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }
    if (!webViewSupported) {
        TerminalUnavailable(onBack)
        return
    }
    val context = LocalContext.current
    val store = fontStore ?: remember(context) { SharedPreferencesTerminalFontStore(context.applicationContext) }
    val scope = rememberCoroutineScope()
    val controller = remember(profileId, source) { TerminalController(source, profileId, scope) }
    val state by controller.state.collectAsState()
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var carriedUnlock by remember { mutableStateOf(TerminalUnlockCarry.take()) }
    val promptTitle = stringResource(R.string.terminal_unlock_prompt)

    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (controller.state.value == TerminalUiState.Paused) controller.check()
                Lifecycle.Event.ON_STOP -> if (activity?.isChangingConfigurations == true) {
                    val live = controller.state.value
                    if (live is TerminalUiState.Connected || live is TerminalUiState.Connecting) TerminalUnlockCarry.carry()
                } else {
                    controller.pause()
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.dispose()
        }
    }
    LaunchedEffect(controller) { controller.check() }

    fun unlock() {
        when {
            carriedUnlock -> {
                carriedUnlock = false
                controller.connect()
            }
            authenticator == null || !authenticator.canAuthenticate() -> controller.unlockUnavailable()
            else -> authenticator.authenticate(promptTitle) { ok ->
                if (ok) controller.connect() else controller.unlockFailed()
            }
        }
    }
    LaunchedEffect(state) {
        if (state == TerminalUiState.NeedsUnlock) unlock()
    }

    // Retrying (unlock or reconnect) starts over: availability, then the
    // device credential again.
    TerminalScreen(
        state = state,
        controller = controller,
        serverLabel = serverLabel,
        hermes = hermes,
        fontStore = store,
        onBack = onBack,
        onRetry = controller::check,
        onCancel = {
            controller.cancel()
            onBack()
        }
    )
}

@Composable
private fun TerminalUnavailable(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().testTag(TerminalTags.SCREEN)) {
        TerminalTopBar(title = stringResource(R.string.terminal_title), subtitle = null, dot = null, onBack = onBack)
        Box(Modifier.padding(16.dp)) {
            NoticeCard(tone = NoticeTone.Error, title = stringResource(R.string.terminal_webview_unsupported))
        }
    }
}

@Composable
private fun TerminalTopBar(
    title: String,
    subtitle: String?,
    dot: Color?,
    onBack: () -> Unit,
    actions: @Composable () -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(OrsikIcons.ArrowBack, contentDescription = stringResource(R.string.terminal_back))
        }
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (subtitle != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag(TerminalTags.SUBTITLE)) {
                    if (dot != null) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(dot))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        actions()
    }
}

/** What "An Hermes" carries: a selection or the last output. */
private data class AskContext(val text: String, val selection: Boolean)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TerminalScreen(
    state: TerminalUiState,
    controller: TerminalController,
    serverLabel: String,
    hermes: TerminalHermes?,
    fontStore: TerminalFontStore,
    onBack: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    var page by remember { mutableStateOf<JavaScriptReplyProxy?>(null) }
    var webView by remember { mutableStateOf<WebView?>(null) }
    var size by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var modifier by remember { mutableStateOf("") }
    var selection by remember { mutableStateOf("") }
    var ask by remember { mutableStateOf<AskContext?>(null) }
    var notice by remember { mutableStateOf<Int?>(null) }
    var fontSize by remember { mutableIntStateOf(fontStore.fontSize()) }
    // The console stays once a connection was made: a dropped one keeps the
    // last output visible (dimmed) behind the "getrennt" card.
    var consoleShown by remember { mutableStateOf(false) }
    if (state == TerminalUiState.Connecting || state == TerminalUiState.Connected) consoleShown = true
    val connected = state == TerminalUiState.Connected
    val label = serverLabel.ifBlank { stringResource(R.string.terminal_server_fallback) }
    val status = OrsikTheme.statusColors
    val (subtitle, dot) = when (state) {
        TerminalUiState.Connected -> stringResource(
            R.string.terminal_subtitle_connected, label,
            size?.let { "${it.first}×${it.second}" } ?: "…"
        ) to status.ok
        is TerminalUiState.Closed -> stringResource(R.string.terminal_subtitle_closed, label) to MaterialTheme.colorScheme.error
        else -> stringResource(R.string.terminal_subtitle_connecting, label) to MaterialTheme.colorScheme.primary
    }
    LaunchedEffect(page, state) {
        page?.postMessage(if (state is TerminalUiState.Closed) "D:1" else "D:0")
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .testTag(TerminalTags.SCREEN)
    ) {
        TerminalTopBar(title = stringResource(R.string.terminal_title), subtitle = subtitle, dot = dot, onBack = onBack) {
            FontMenu(
                fontSize = fontSize,
                onSize = { next ->
                    fontSize = next.coerceIn(MIN_FONT, MAX_FONT)
                    fontStore.setFontSize(fontSize)
                    page?.postMessage("F:$fontSize")
                }
            )
            MoreMenu(connected = connected, onReconnect = onRetry, onDisconnect = onCancel)
        }
        SessionTabs()
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            if (consoleShown) {
                val palette = terminalPalette()
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(20.dp))
                        .background(palette.background)
                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
                ) {
                    TerminalConsole(
                        controller = controller,
                        palette = palette,
                        fontSize = fontSize,
                        page = page,
                        onPage = { page = it },
                        onWebView = { webView = it },
                        onSize = { size = it },
                        onModifier = { modifier = it },
                        onSelection = { selection = it },
                        onLastOutput = { text -> ask = AskContext(text, selection = false) },
                        onFontSize = { next ->
                            fontSize = next.coerceIn(MIN_FONT, MAX_FONT)
                            fontStore.setFontSize(fontSize)
                        }
                    )
                    when (state) {
                        TerminalUiState.Connected -> Unit
                        is TerminalUiState.Closed -> DisconnectedCard(state.reason, onRetry, onBack)
                        else -> ConnectingCard(onCancel, skeleton = state == TerminalUiState.Connecting && page == null)
                    }
                    if (modifier.isNotEmpty() && connected) ModifierHint(modifier)
                    if (selection.isNotEmpty() && connected) {
                        SelectionBar(
                            hermesAvailable = hermes != null,
                            onCopy = {
                                scope.launch { clipboard.setClipEntry(ClipEntry(sensitivePlainText(selection))) }
                                notice = R.string.terminal_copied
                                page?.postMessage("C:")
                                selection = ""
                            },
                            onPaste = {
                                scope.launch {
                                    val text = clipboard.getClipEntry()?.clipData?.takeIf { it.itemCount > 0 }
                                        ?.getItemAt(0)?.coerceToText(context)?.toString()
                                    if (!text.isNullOrEmpty()) controller.input(text.toByteArray(Charsets.UTF_8))
                                }
                                page?.postMessage("C:")
                                selection = ""
                            },
                            onHermes = {
                                ask = AskContext(selection, selection = true)
                                page?.postMessage("C:")
                                selection = ""
                            }
                        )
                    }
                }
            } else {
                TerminalNotice(state, onRetry)
            }
        }
        notice?.let { message ->
            Text(
                stringResource(message),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 20.dp).semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        if (consoleShown) {
            TerminalKeyRows(
                enabled = connected,
                modifier = modifier,
                onKey = { key -> page?.postMessage("k:$key") }
            )
            val current = ask
            if (current != null && hermes != null) {
                AskDock(
                    context = current,
                    onDismiss = { ask = null },
                    onSend = { question ->
                        when (hermes.ask(current.text, question)) {
                            FilesAskResult.Opened -> {
                                ask = null
                                true
                            }
                            FilesAskResult.Busy -> {
                                notice = R.string.files_ask_busy
                                false
                            }
                            FilesAskResult.Failed -> {
                                notice = R.string.files_ask_failed
                                false
                            }
                        }
                    }
                )
            } else {
                val imeVisible = WindowInsets.isImeVisible
                BottomBar(
                    hermesAvailable = hermes != null,
                    lastOutputLabel = stringResource(
                        if (state is TerminalUiState.Closed) R.string.terminal_output_to_hermes else R.string.terminal_last_output_to_hermes
                    ),
                    onLastOutput = { page?.postMessage("q:") },
                    keyboardShown = imeVisible,
                    onKeyboard = {
                        val view = webView ?: return@BottomBar
                        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        if (imeVisible) {
                            page?.postMessage("B:")
                            imm.hideSoftInputFromWindow(view.windowToken, 0)
                        } else {
                            view.requestFocus()
                            page?.postMessage("f:")
                            imm.showSoftInput(view, 0)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun TerminalNotice(state: TerminalUiState, onRetry: () -> Unit) {
    val reconnect = NoticeAction(
        label = stringResource(R.string.terminal_reconnect),
        onClick = onRetry,
        testTag = TerminalTags.RECONNECT
    )
    Box(Modifier.padding(8.dp)) {
        when (state) {
            TerminalUiState.Checking, TerminalUiState.NeedsUnlock, TerminalUiState.Paused,
            TerminalUiState.Connecting, TerminalUiState.Connected ->
                Text(stringResource(R.string.terminal_checking), style = MaterialTheme.typography.bodyMedium)
            TerminalUiState.Unsupported -> NoticeCard(
                tone = NoticeTone.Info,
                title = stringResource(R.string.terminal_unsupported_title),
                text = stringResource(R.string.terminal_unsupported_text)
            )
            TerminalUiState.CheckFailed -> NoticeCard(
                tone = NoticeTone.Error,
                title = stringResource(R.string.terminal_check_failed),
                actions = listOf(reconnect)
            )
            TerminalUiState.UnlockUnavailable -> NoticeCard(
                tone = NoticeTone.Info,
                title = stringResource(R.string.terminal_unlock_unavailable)
            )
            TerminalUiState.UnlockFailed -> NoticeCard(
                tone = NoticeTone.Info,
                title = stringResource(R.string.terminal_unlock_failed),
                actions = listOf(
                    NoticeAction(stringResource(R.string.terminal_unlock_again), onRetry, testTag = TerminalTags.UNLOCK)
                )
            )
            is TerminalUiState.Closed -> NoticeCard(
                tone = if (state.reason == TerminalCloseReason.Exited) NoticeTone.Info else NoticeTone.Error,
                title = stringResource(closedText(state.reason)),
                text = stringResource(R.string.terminal_session_kept),
                actions = if (state.reason == TerminalCloseReason.Disabled) emptyList() else listOf(reconnect)
            )
        }
    }
}

private fun closedText(reason: TerminalCloseReason): Int = when (reason) {
    TerminalCloseReason.Exited -> R.string.terminal_closed_exited
    TerminalCloseReason.Unauthorized -> R.string.terminal_closed_unauthorized
    TerminalCloseReason.Disabled -> R.string.terminal_unsupported_title
    TerminalCloseReason.Busy -> R.string.terminal_closed_busy
    TerminalCloseReason.Disconnected -> R.string.terminal_closed_disconnected
}

/** tmux tabs need a session list the bridge does not offer: a disabled preview. */
@Composable
private fun SessionTabs() {
    val preview = stringResource(R.string.terminal_tabs_preview)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics { contentDescription = preview }
            .testTag(TerminalTags.TABS)
    ) {
        Text(
            text = TERMINAL_SESSION,
            fontFamily = OrsikMono,
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.primaryContainer)
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .semantics { selected = true }
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(width = 40.dp, height = 36.dp)
                .dashedOutline(MaterialTheme.colorScheme.outlineVariant, radius = 30f)
        ) {
            Icon(OrsikIcons.Plus, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(18.dp))
        }
        Text(
            stringResource(R.string.terminal_preview_badge),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun FontMenu(fontSize: Int, onSize: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(TerminalTags.FONT)) {
            Icon(OrsikIcons.TextSize, contentDescription = stringResource(R.string.terminal_font_size, fontSize))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.terminal_font_larger)) },
                enabled = fontSize < MAX_FONT,
                onClick = { onSize(fontSize + 1) },
                modifier = Modifier.testTag(TerminalTags.FONT_LARGER)
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.terminal_font_smaller)) },
                enabled = fontSize > MIN_FONT,
                onClick = { onSize(fontSize - 1) },
                modifier = Modifier.testTag(TerminalTags.FONT_SMALLER)
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.terminal_font_reset, DEFAULT_FONT)) },
                onClick = {
                    onSize(DEFAULT_FONT)
                    open = false
                }
            )
        }
    }
}

@Composable
private fun MoreMenu(connected: Boolean, onReconnect: () -> Unit, onDisconnect: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(TerminalTags.MORE)) {
            Icon(OrsikIcons.MoreVert, contentDescription = stringResource(R.string.terminal_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.terminal_sessions_preview)) },
                enabled = false,
                onClick = {},
                modifier = Modifier.testTag(TerminalTags.SESSIONS)
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.terminal_reconnect)) },
                onClick = {
                    open = false
                    onReconnect()
                }
            )
            if (connected) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.terminal_disconnect)) },
                    onClick = {
                        open = false
                        onDisconnect()
                    }
                )
            }
        }
    }
}

@Composable
private fun BoxScope.ConnectingCard(onCancel: () -> Unit, skeleton: Boolean) {
    val block = MaterialTheme.colorScheme.surfaceContainerHighest
    if (skeleton) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(18.dp)) {
            listOf(0.6f, 0.45f, 0.72f, 0.62f, 0.5f, 0.66f, 0.36f).forEach { fraction ->
                Box(Modifier.fillMaxWidth(fraction).height(12.dp).clip(RoundedCornerShape(6.dp)).background(block))
            }
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(24.dp))
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(TerminalTags.CONNECTING)
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
            Icon(OrsikIcons.Terminal, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
            Text(stringResource(R.string.terminal_connecting_title, TERMINAL_SESSION), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.terminal_connecting_text), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onCancel, modifier = Modifier.testTag(TerminalTags.CANCEL)) {
            Text(stringResource(R.string.terminal_cancel), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun BoxScope.DisconnectedCard(reason: TerminalCloseReason, onReconnect: () -> Unit, onClose: () -> Unit) {
    val error = MaterialTheme.colorScheme.error
    val canReconnect = reason != TerminalCloseReason.Disabled
    Column(
        modifier = Modifier
            .align(Alignment.Center)
            .padding(12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(1.dp, error.copy(alpha = 0.5f), RoundedCornerShape(28.dp))
            .padding(20.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(TerminalTags.DISCONNECTED)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(OrsikIcons.Close, contentDescription = null, tint = error)
            Spacer(Modifier.width(12.dp))
            Text(
                stringResource(if (reason == TerminalCloseReason.Disconnected) R.string.terminal_disconnected_title else closedText(reason)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = error
            )
        }
        Text(
            stringResource(R.string.terminal_disconnected_text),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp, start = 36.dp)
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 14.dp, start = 28.dp)) {
            if (canReconnect) {
                Button(
                    onClick = onReconnect,
                    colors = ButtonDefaults.buttonColors(containerColor = error, contentColor = MaterialTheme.colorScheme.onError),
                    modifier = Modifier.testTag(TerminalTags.RECONNECT)
                ) { Text(stringResource(R.string.terminal_reconnect)) }
            }
            TextButton(onClick = onClose, modifier = Modifier.testTag(TerminalTags.CLOSE)) {
                Text(stringResource(R.string.terminal_close), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun BoxScope.ModifierHint(modifier: String) {
    Text(
        text = stringResource(if (modifier == "alt") R.string.terminal_alt_hint else R.string.terminal_ctrl_hint),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimary,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary)
            .padding(horizontal = 18.dp, vertical = 10.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(TerminalTags.MODIFIER_HINT)
    )
}

@Composable
private fun BoxScope.SelectionBar(hermesAvailable: Boolean, onCopy: () -> Unit, onPaste: () -> Unit, onHermes: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .align(Alignment.TopCenter)
            .padding(top = 12.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(4.dp)
            .testTag(TerminalTags.SELECTION_BAR)
    ) {
        TextButton(onClick = onCopy, modifier = Modifier.testTag(TerminalTags.COPY)) {
            Icon(OrsikIcons.Copy, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.terminal_copy))
        }
        TextButton(onClick = onPaste, modifier = Modifier.testTag(TerminalTags.PASTE)) {
            Text(stringResource(R.string.terminal_paste))
        }
        if (hermesAvailable) {
            Button(onClick = onHermes, modifier = Modifier.testTag(TerminalTags.TO_HERMES)) {
                Text(stringResource(R.string.terminal_to_hermes))
            }
        }
    }
}

private val KEY_ROWS = listOf(
    listOf("esc", "tab", "ctrl", "alt", "up", "down", "left", "right"),
    listOf("pipe", "tilde", "slash", "dash", "underscore", "gt", "dollar", "enter")
)

/** Two rows of keys a phone keyboard lacks; Ctrl and Alt latch for the next key. */
@Composable
private fun TerminalKeyRows(enabled: Boolean, modifier: String, onKey: (String) -> Unit) {
    val labels = mapOf(
        "esc" to stringResource(R.string.terminal_key_esc),
        "tab" to stringResource(R.string.terminal_key_tab),
        "ctrl" to stringResource(R.string.terminal_key_ctrl),
        "alt" to stringResource(R.string.terminal_key_alt),
        "up" to "↑", "down" to "↓", "left" to "←", "right" to "→",
        "pipe" to "|", "tilde" to "~", "slash" to "/", "dash" to "-",
        "underscore" to "_", "gt" to ">", "dollar" to "$", "enter" to "⏎"
    )
    val descriptions = mapOf(
        "up" to stringResource(R.string.terminal_key_up),
        "down" to stringResource(R.string.terminal_key_down),
        "left" to stringResource(R.string.terminal_key_left),
        "right" to stringResource(R.string.terminal_key_right),
        "enter" to stringResource(R.string.terminal_key_enter)
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .testTag(TerminalTags.KEYS)
    ) {
        KEY_ROWS.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { key ->
                    val latched = key == modifier
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (latched) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                            .then(if (enabled) Modifier.clickable { onKey(key) } else Modifier)
                            .semantics {
                                descriptions[key]?.let { contentDescription = it }
                                if (key == "ctrl" || key == "alt") selected = latched
                            }
                            .testTag("terminal_key_$key")
                    ) {
                        Text(
                            labels.getValue(key),
                            fontFamily = OrsikMono,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            color = when {
                                latched -> MaterialTheme.colorScheme.onPrimary
                                enabled -> MaterialTheme.colorScheme.onSurface
                                else -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BottomBar(
    hermesAvailable: Boolean,
    lastOutputLabel: String,
    onLastOutput: () -> Unit,
    keyboardShown: Boolean,
    onKeyboard: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        if (hermesAvailable) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 52.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceContainer)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                    .clickable(onClick = onLastOutput)
                    .padding(horizontal = 8.dp)
                    .testTag(TerminalTags.LAST_OUTPUT)
            ) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), contentAlignment = Alignment.Center) {
                    Text("H", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(12.dp))
                Text(lastOutputLabel, style = MaterialTheme.typography.titleMedium)
            }
        } else {
            Spacer(Modifier.weight(1f))
        }
        IconButton(onClick = onKeyboard, modifier = Modifier.size(52.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainer).testTag(TerminalTags.KEYBOARD)) {
            Icon(
                OrsikIcons.Keyboard,
                contentDescription = stringResource(if (keyboardShown) R.string.terminal_keyboard_hide else R.string.terminal_keyboard_show)
            )
        }
    }
}

@Composable
private fun AskDock(context: AskContext, onDismiss: () -> Unit, onSend: suspend (String) -> Boolean) {
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Text(
                stringResource(if (context.selection) R.string.terminal_selection_badge else R.string.terminal_output_badge),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 10.dp, vertical = 3.dp)
            )
            Text(
                context.text.lineSequence().lastOrNull { it.isNotBlank() }.orEmpty(),
                fontFamily = OrsikMono,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp)
            )
            IconButton(onClick = onDismiss) {
                Icon(OrsikIcons.Close, contentDescription = stringResource(R.string.terminal_ask_dismiss), modifier = Modifier.size(18.dp))
            }
        }
        AgentComposerDock(
            draft = draft,
            onDraftChange = { draft = it },
            hint = stringResource(R.string.terminal_ask_hint),
            enabled = !sending,
            canSend = draft.isNotBlank() && !sending,
            sending = sending,
            onSend = {
                sending = true
                scope.launch {
                    if (onSend(draft.trim())) draft = ""
                    sending = false
                }
            },
            inputTestTag = TerminalTags.COMPOSER
        )
    }
}

/** The terminal palette of design v2 (GitHub-like ANSI colours on #0A0D12 / #FFFFFF). */
internal data class TerminalPalette(
    val background: Color,
    val foreground: Color,
    val cursor: Color,
    val selection: Color,
    val ansi: List<Color>
)

@Composable
private fun terminalPalette(): TerminalPalette {
    // Follows the app's theme (also when it differs from the system's).
    return if (MaterialTheme.colorScheme.surface.luminance() < 0.5f) DARK_PALETTE else LIGHT_PALETTE
}

private fun c(hex: Long) = Color(0xFF000000 or hex)

internal val DARK_PALETTE = TerminalPalette(
    background = c(0x0A0D12), foreground = c(0xE6EDF3), cursor = c(0x5B8FFF), selection = c(0x22396A),
    ansi = listOf(
        c(0x484F58), c(0xFF7B72), c(0x7EE787), c(0xE3B341), c(0x79C0FF), c(0xD2A8FF), c(0xA5D6FF), c(0xB1BAC4),
        c(0x6E7681), c(0xFFA198), c(0x56D364), c(0xE3B341), c(0x79C0FF), c(0xD2A8FF), c(0x56D4DD), c(0xFFFFFF)
    )
)

internal val LIGHT_PALETTE = TerminalPalette(
    background = c(0xFFFFFF), foreground = c(0x1F2328), cursor = c(0x0053FD), selection = c(0xD3E2FF),
    ansi = listOf(
        c(0x24292F), c(0xCF222E), c(0x116329), c(0x7D4E00), c(0x0550AE), c(0x8250DF), c(0x1B7C83), c(0x6E7781),
        c(0x57606A), c(0xA40E26), c(0x1A7F37), c(0x633C01), c(0x0969DA), c(0x8250DF), c(0x3192AA), c(0x8C959F)
    )
)

internal const val TERMINAL_SESSION = "orsik"
internal const val DEFAULT_FONT = 12
internal const val MIN_FONT = 8
internal const val MAX_FONT = 24

private const val ASSET_ORIGIN = "https://appassets.androidplatform.net"
private const val PAGE_URL = "$ASSET_ORIGIN/assets/terminal/index.html"

@Composable
private fun TerminalConsole(
    controller: TerminalController,
    palette: TerminalPalette,
    fontSize: Int,
    page: JavaScriptReplyProxy?,
    onPage: (JavaScriptReplyProxy) -> Unit,
    onWebView: (WebView) -> Unit,
    onSize: (Pair<Int, Int>) -> Unit,
    onModifier: (String) -> Unit,
    onSelection: (String) -> Unit,
    onLastOutput: (String) -> Unit,
    onFontSize: (Int) -> Unit
) {
    val theme = remember(palette, fontSize) {
        JSONObject()
            .put("background", css(palette.background))
            .put("foreground", css(palette.foreground))
            .put("cursor", css(palette.cursor))
            .put("selection", css(palette.selection))
            .put("ansi", JSONArray(palette.ansi.map(::css)))
            .put("fontSize", fontSize)
            .toString()
    }
    LaunchedEffect(page, theme) { page?.postMessage("t:$theme") }
    LaunchedEffect(page) {
        val target = page ?: return@LaunchedEffect
        controller.output.collect { bytes ->
            target.postMessage("o:" + Base64.encodeToString(bytes, Base64.NO_WRAP))
        }
    }
    val description = stringResource(R.string.terminal_view_description)
    AndroidView(
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = description }
            .testTag(TerminalTags.VIEW),
        factory = { context ->
            createTerminalWebView(context) { message, proxy ->
                when {
                    message == "r:" -> onPage(proxy)
                    message.startsWith("d:") -> controller.input(message.substring(2).toByteArray(Charsets.UTF_8))
                    message.startsWith("b:") -> runCatching { Base64.decode(message.substring(2), Base64.NO_WRAP) }
                        .getOrNull()?.let(controller::input)
                    message.startsWith("s:") -> parseSize(message.substring(2))?.let { (columns, rows) ->
                        onSize(columns to rows)
                        controller.resize(columns, rows)
                    }
                    message.startsWith("m:") -> onModifier(message.substring(2))
                    message.startsWith("S:") -> onSelection(message.substring(2).take(MAX_CONTEXT_CHARS))
                    message.startsWith("L:") -> onLastOutput(message.substring(2).takeLast(MAX_CONTEXT_CHARS))
                    message.startsWith("z:") -> message.substring(2).toIntOrNull()?.let(onFontSize)
                }
            }.also(onWebView)
        },
        onRelease = { it.destroy() }
    )
}

/** Text handed to Hermes from the terminal is bounded (the chat message limit is larger). */
private const val MAX_CONTEXT_CHARS = 16_000

private fun parseSize(raw: String): Pair<Int, Int>? {
    val parts = raw.split(',')
    if (parts.size != 2) return null
    val columns = parts[0].toIntOrNull() ?: return null
    val rows = parts[1].toIntOrNull() ?: return null
    return if (columns in 1..500 && rows in 1..300) columns to rows else null
}

private fun css(color: Color): String = String.format("#%06X", color.toArgb() and 0xFFFFFF)

/**
 * A WebView that can load only this app's terminal page (assets, and the
 * bundled Roboto Mono through [WebViewAssetLoader]); every other request is
 * refused, there is no navigation, no file or content access and the page
 * talks to the app only through a message listener bound to the asset
 * origin.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createTerminalWebView(
    context: Context,
    onMessage: (String, JavaScriptReplyProxy) -> Unit
): WebView {
    val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .addPathHandler("/res/", WebViewAssetLoader.ResourcesPathHandler(context))
        .build()
    return WebView(context).apply {
        // Compose would hand the view WRAP_CONTENT, and a WebView then sizes
        // its page by the content: an empty terminal would be 0 px high.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setBackgroundColor(android.graphics.Color.TRANSPARENT)
        settings.javaScriptEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.domStorageEnabled = false
        settings.setSupportZoom(false)
        settings.setGeolocationEnabled(false)
        webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse =
                loader.shouldInterceptRequest(request.url)
                    ?: WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", emptyMap(), null)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true
        }
        WebViewCompat.addWebMessageListener(this, "OrsikTerminal", setOf(ASSET_ORIGIN)) {
                _, message, sourceOrigin, isMainFrame, replyProxy ->
            val text = message.data
            if (isMainFrame && sourceOrigin == Uri.parse(ASSET_ORIGIN) && text != null) onMessage(text, replyProxy)
        }
        loadUrl(PAGE_URL)
    }
}

/** The terminal font size the user chose (A-/A+ or a pinch), kept for the next visit. */
internal interface TerminalFontStore {
    fun fontSize(): Int
    fun setFontSize(size: Int)
}

private class SharedPreferencesTerminalFontStore(context: Context) : TerminalFontStore {
    private val prefs = context.getSharedPreferences("terminal", Context.MODE_PRIVATE)
    override fun fontSize(): Int = prefs.getInt("font_size", DEFAULT_FONT).coerceIn(MIN_FONT, MAX_FONT)
    override fun setFontSize(size: Int) {
        prefs.edit().putInt("font_size", size.coerceIn(MIN_FONT, MAX_FONT)).apply()
    }
}
