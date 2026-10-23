package io.github.excludebarrier.orsikrelay.ui.terminal

import android.annotation.SuppressLint
import android.net.Uri
import android.util.Base64
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
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
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

internal object TerminalTags {
    const val SCREEN = "terminal_screen"
    const val VIEW = "terminal_view"
    const val KEYS = "terminal_keys"
    const val RECONNECT = "terminal_reconnect"
    const val UNLOCK = "terminal_unlock"
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

/**
 * The server shell of the active profile, in xterm.js. Every visit (and
 * every return from the background) asks for the device credential first;
 * leaving the screen closes the connection while the tmux session keeps
 * running on the server.
 */
@Composable
internal fun TerminalRoute(
    profileId: String,
    source: TerminalSource,
    authenticator: AppAuthenticator?,
    onBack: () -> Unit
) {
    // Checked before anything else: an old WebView must not open a shell
    // on the server that it then cannot show.
    val webViewSupported = remember { WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) }
    if (!webViewSupported) {
        TerminalUnavailable(onBack)
        return
    }
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
                    if (controller.state.value is TerminalUiState.Connected) TerminalUnlockCarry.carry()
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
    TerminalScreen(state = state, controller = controller, onBack = onBack, onRetry = controller::check)
}

@Composable
private fun TerminalUnavailable(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().testTag(TerminalTags.SCREEN)) {
        TerminalTopBar(onBack)
        Box(Modifier.padding(16.dp)) {
            NoticeCard(tone = NoticeTone.Error, title = stringResource(R.string.terminal_webview_unsupported))
        }
    }
}

@Composable
private fun TerminalTopBar(onBack: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.terminal_back))
        }
        Text(
            text = stringResource(R.string.terminal_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun TerminalScreen(
    state: TerminalUiState,
    controller: TerminalController,
    onBack: () -> Unit,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .testTag(TerminalTags.SCREEN)
    ) {
        TerminalTopBar(onBack)
        when (state) {
            TerminalUiState.Connected -> TerminalConsole(controller, Modifier.weight(1f))
            else -> Box(Modifier.weight(1f).padding(16.dp)) {
                TerminalNotice(state, onRetry)
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
    when (state) {
        TerminalUiState.Checking, TerminalUiState.NeedsUnlock, TerminalUiState.Paused, TerminalUiState.Connected ->
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

private fun closedText(reason: TerminalCloseReason): Int = when (reason) {
    TerminalCloseReason.Exited -> R.string.terminal_closed_exited
    TerminalCloseReason.Unauthorized -> R.string.terminal_closed_unauthorized
    TerminalCloseReason.Disabled -> R.string.terminal_unsupported_title
    TerminalCloseReason.Busy -> R.string.terminal_closed_busy
    TerminalCloseReason.Disconnected -> R.string.terminal_closed_disconnected
}

private const val ASSET_ORIGIN = "https://appassets.androidplatform.net"
private const val PAGE_URL = "$ASSET_ORIGIN/assets/terminal/index.html"

@Composable
private fun TerminalConsole(controller: TerminalController, modifier: Modifier = Modifier) {
    var page by remember { mutableStateOf<JavaScriptReplyProxy?>(null) }
    var ctrlLatched by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    val fontScale = LocalDensity.current.fontScale
    val theme = remember(colors, fontScale) {
        JSONObject()
            .put("background", css(colors.surfaceContainerLowest))
            .put("foreground", css(colors.onSurface))
            .put("cursor", css(colors.primary))
            .put("selection", css(colors.primaryContainer))
            .put("fontSize", (14 * fontScale).coerceIn(10f, 28f).toInt())
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
    Column(modifier) {
        AndroidView(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .semantics { contentDescription = description }
                .testTag(TerminalTags.VIEW),
            factory = { context ->
                createTerminalWebView(context) { message, proxy ->
                    when {
                        message == "r:" -> page = proxy
                        message.startsWith("d:") -> controller.input(message.substring(2).toByteArray(Charsets.UTF_8))
                        message.startsWith("b:") -> runCatching { Base64.decode(message.substring(2), Base64.NO_WRAP) }
                            .getOrNull()?.let(controller::input)
                        message.startsWith("s:") -> parseSize(message.substring(2))?.let { (columns, rows) ->
                            controller.resize(columns, rows)
                        }
                        message.startsWith("c:") -> ctrlLatched = message == "c:1"
                    }
                }
            },
            onRelease = { it.destroy() }
        )
        TerminalKeyRow(ctrlLatched = ctrlLatched, onKey = { key -> page?.postMessage("k:$key") })
    }
}

private fun parseSize(raw: String): Pair<Int, Int>? {
    val parts = raw.split(',')
    if (parts.size != 2) return null
    val columns = parts[0].toIntOrNull() ?: return null
    val rows = parts[1].toIntOrNull() ?: return null
    return if (columns in 1..500 && rows in 1..300) columns to rows else null
}

private fun css(color: Color): String = String.format("#%06X", color.toArgb() and 0xFFFFFF)

/**
 * A WebView that can load only this app's terminal page (assets through
 * [WebViewAssetLoader]); every other request is refused, there is no
 * navigation, no file or content access and the page talks to the app only
 * through a message listener bound to the asset origin.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun createTerminalWebView(
    context: android.content.Context,
    onMessage: (String, JavaScriptReplyProxy) -> Unit
): WebView {
    val loader = WebViewAssetLoader.Builder()
        .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
        .build()
    return WebView(context).apply {
        // Compose would hand the view WRAP_CONTENT, and a WebView then sizes
        // its page by the content: an empty terminal would be 0 px high.
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
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

@Composable
private fun TerminalKeyRow(ctrlLatched: Boolean, onKey: (String) -> Unit) {
    val keys = listOf(
        "esc" to stringResource(R.string.terminal_key_esc),
        "tab" to stringResource(R.string.terminal_key_tab),
        "ctrl" to stringResource(R.string.terminal_key_ctrl),
        "left" to "←",
        "down" to "↓",
        "up" to "↑",
        "right" to "→",
        "pipe" to "|",
        "dash" to "-",
        "slash" to "/",
        "tilde" to "~"
    )
    val arrowDescriptions = mapOf(
        "left" to stringResource(R.string.terminal_key_left),
        "down" to stringResource(R.string.terminal_key_down),
        "up" to stringResource(R.string.terminal_key_up),
        "right" to stringResource(R.string.terminal_key_right)
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 4.dp)
            .testTag(TerminalTags.KEYS),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        keys.forEach { (key, label) ->
            val modifier = Modifier
                .heightIn(min = 48.dp)
                .semantics { arrowDescriptions[key]?.let { contentDescription = it } }
                .testTag("terminal_key_$key")
            if (key == "ctrl" && ctrlLatched) {
                FilledTonalButton(onClick = { onKey(key) }, modifier = modifier) { Text(label) }
            } else {
                TextButton(onClick = { onKey(key) }, modifier = modifier) { Text(label) }
            }
        }
    }
}
