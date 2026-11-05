package io.github.excludebarrier.orsikrelay.ui.profiles

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.security.KeyChain
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionAdmin
import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionMode
import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionSettings
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import io.github.excludebarrier.orsikrelay.ui.components.ConnectedButtonGroup
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import io.github.excludebarrier.orsikrelay.ui.components.OrsikLargeHeader
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikMono
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikTheme
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A profile's two paths as one full screen: the paired (Tailscale) endpoint,
 * an optional public HTTPS address, which path to use, and the client
 * certificate for the public one (picked in the system dialog; the key stays
 * in the Android key store). The device key reaches the public address only
 * after the bridge there reports the server identity pinned from the paired
 * one.
 */
@Composable
internal fun ConnectionScreen(
    profile: ServerProfile,
    admin: ConnectionAdmin,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val current = profile.connection
    var url by remember { mutableStateOf(current.publicBaseUrl.orEmpty()) }
    var adding by remember { mutableStateOf(false) }
    var mode by remember { mutableStateOf(current.mode) }
    var alias by remember { mutableStateOf(current.clientCertAlias) }
    var error by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current.findActivity()

    val hasUrl = url.isNotBlank()
    val effectiveMode = if (hasUrl) mode else ConnectionMode.PRIVATE_ONLY
    val dirty = url.trim() != current.publicBaseUrl.orEmpty() ||
        effectiveMode != current.mode ||
        alias != current.clientCertAlias

    fun save() {
        val trimmed = url.trim()
        val publicUrl = if (trimmed.isEmpty()) null else ProfileBaseUrlValidator.validate(trimmed).getOrNull()
        if (trimmed.isNotEmpty() && (publicUrl == null || publicUrl == profile.baseUrl)) {
            error = R.string.connection_error_url
            return
        }
        val settings = try {
            ConnectionSettings.create(
                mode = if (publicUrl == null) ConnectionMode.PRIVATE_ONLY else mode,
                publicBaseUrl = publicUrl,
                clientCertAlias = alias,
                serverId = current.serverId
            )
        } catch (_: IllegalArgumentException) {
            error = R.string.connection_error_url
            return
        }
        saving = true
        scope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { admin.save(profile.id, settings) }
                true
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                false
            }
            saving = false
            if (ok) onSaved() else error = R.string.connection_error_save
        }
    }

    fun chooseCertificate() {
        val host = ProfileBaseUrlValidator.validate(url.trim()).getOrNull()?.let { runCatching { URI(it) }.getOrNull() }
        activity?.let { act ->
            KeyChain.choosePrivateKeyAlias(
                act,
                { chosen -> act.runOnUiThread { if (chosen != null) alias = chosen } },
                arrayOf("EC", "RSA"),
                null,
                host?.host,
                host?.port ?: -1,
                alias
            )
        }
    }

    Dialog(
        onDismissRequest = { if (!saving) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize().testTag("connectionScreen")) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                ) {
                    OrsikLargeHeader(
                        title = stringResource(R.string.connection_title),
                        onBack = { if (!saving) onDismiss() },
                        backTestTag = "connectionBack"
                    )
                    Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
                        Text(
                            text = stringResource(R.string.connection_subtitle, profile.profileName),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )

                        SectionLabel(R.string.connection_section_routes)
                        RouteCard(
                            icon = OrsikIcons.ShieldCheck,
                            tileColor = MaterialTheme.colorScheme.primaryContainer,
                            iconColor = MaterialTheme.colorScheme.primary,
                            title = stringResource(R.string.connection_private_title),
                            pill = when {
                                effectiveMode == ConnectionMode.PUBLIC_ONLY -> Pill(R.string.connection_pill_off, false)
                                effectiveMode == ConnectionMode.AUTOMATIC -> Pill(R.string.connection_pill_preferred, true)
                                else -> Pill(R.string.connection_pill_active, true)
                            },
                            pillTag = "connectionPrivatePill",
                            subtitle = profile.baseUrl.removePrefix("https://")
                        )
                        DashedConnector()
                        if (!hasUrl && !adding) {
                            AddPublicCard(onClick = { adding = true })
                        } else {
                            RouteCard(
                                icon = OrsikIcons.Globe,
                                tileColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                                iconColor = MaterialTheme.colorScheme.primary,
                                title = stringResource(R.string.connection_public_title),
                                pill = when {
                                    !hasUrl -> Pill(R.string.connection_pill_new, false)
                                    effectiveMode == ConnectionMode.PRIVATE_ONLY -> Pill(R.string.connection_pill_off, false)
                                    effectiveMode == ConnectionMode.AUTOMATIC -> Pill(R.string.connection_pill_fallback, false)
                                    else -> Pill(R.string.connection_pill_active, true)
                                },
                                pillTag = "connectionPublicPill",
                                error = error != null
                            ) {
                                PublicAddressField(
                                    value = url,
                                    onValueChange = { url = it; error = null },
                                    onClear = { url = ""; adding = false; mode = ConnectionMode.PRIVATE_ONLY; error = null }
                                )
                                error?.let {
                                    Text(
                                        text = stringResource(it),
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier.padding(top = 8.dp, start = 2.dp).testTag("connectionError")
                                    )
                                }
                            }
                        }

                        SectionLabel(R.string.connection_section_mode)
                        val labels = mapOf(
                            ConnectionMode.AUTOMATIC to stringResource(R.string.connection_mode_automatic),
                            ConnectionMode.PRIVATE_ONLY to stringResource(R.string.connection_mode_private),
                            ConnectionMode.PUBLIC_ONLY to stringResource(R.string.connection_mode_public)
                        )
                        ConnectedButtonGroup(
                            options = listOf(ConnectionMode.AUTOMATIC, ConnectionMode.PRIVATE_ONLY, ConnectionMode.PUBLIC_ONLY),
                            selected = effectiveMode,
                            label = { labels.getValue(it) },
                            onSelect = { if (hasUrl) mode = it },
                            testTag = { "connectionMode:${it.name}" },
                            modifier = Modifier.alpha(if (hasUrl) 1f else 0.45f)
                        )
                        Row(Modifier.padding(top = 10.dp, start = 4.dp, end = 4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Icon(OrsikIcons.Relay, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text(
                                text = stringResource(
                                    when {
                                        !hasUrl -> R.string.connection_help_none
                                        mode == ConnectionMode.AUTOMATIC -> R.string.connection_help_auto
                                        mode == ConnectionMode.PRIVATE_ONLY -> R.string.connection_help_vpn
                                        else -> R.string.connection_help_public
                                    }
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.testTag("connectionHelp")
                            )
                        }

                        SectionLabel(R.string.connection_section_cert)
                        CertificateCard(
                            alias = alias,
                            enabled = activity != null && !saving,
                            warn = effectiveMode != ConnectionMode.PRIVATE_ONLY && alias == null,
                            onChoose = ::chooseCertificate
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier
                                .padding(top = 22.dp)
                                .fillMaxWidth()
                                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(20.dp))
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                        ) {
                            Icon(OrsikIcons.ShieldCheck, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(20.dp))
                            Text(
                                text = stringResource(R.string.connection_note),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Button(
                    onClick = ::save,
                    enabled = dirty && !saving,
                    modifier = Modifier
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .fillMaxWidth()
                        .height(56.dp)
                        .testTag("connectionSave")
                ) {
                    Text(stringResource(R.string.connection_save), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

private data class Pill(val text: Int, val positive: Boolean)

@Composable
private fun SectionLabel(text: Int) {
    Text(
        text = stringResource(text),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .padding(start = 4.dp, top = 22.dp, bottom = 8.dp)
            .semantics { heading() }
    )
}

@Composable
private fun Tile(icon: ImageVector, background: Color, tint: Color) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(44.dp)
            .background(background, RoundedCornerShape(14.dp))
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun PillChip(pill: Pill, tag: String) {
    val status = OrsikTheme.statusColors
    val (bg, fg) = if (pill.positive) {
        status.okContainer to status.okText
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(
        text = stringResource(pill.text),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = fg,
        modifier = Modifier
            .background(bg, RoundedCornerShape(13.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag(tag)
    )
}

@Composable
private fun RouteCard(
    icon: ImageVector,
    tileColor: Color,
    iconColor: Color,
    title: String,
    pill: Pill,
    pillTag: String,
    subtitle: String? = null,
    error: Boolean = false,
    content: @Composable () -> Unit = {}
) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = if (error) {
            BorderStroke(2.dp, MaterialTheme.colorScheme.error)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Tile(icon, tileColor, iconColor)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    subtitle?.let { MonoLine(it) }
                }
                PillChip(pill, pillTag)
            }
            content()
        }
    }
}

@Composable
private fun MonoLine(text: String) {
    Text(
        text = text,
        fontFamily = OrsikMono,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )
}

@Composable
private fun DashedConnector() {
    val color = MaterialTheme.colorScheme.outlineVariant
    Canvas(Modifier.padding(start = 37.dp).width(2.dp).height(18.dp)) {
        drawLine(
            color = color,
            start = Offset(size.width / 2, 0f),
            end = Offset(size.width / 2, size.height),
            strokeWidth = size.width,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))
        )
    }
}

@Composable
private fun AddPublicCard(onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = Color.Transparent,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("connectionAddPublic")
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier
                .dashedBorder(outline)
                .padding(16.dp)
        ) {
            Tile(OrsikIcons.Plus, MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.primary)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    text = stringResource(R.string.connection_add_public),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(R.string.connection_add_public_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** A 1.5 dp dashed outline on a 24 dp rounded rectangle (the "add" card). */
private fun Modifier.dashedBorder(color: Color): Modifier = drawBehind {
    val stroke = 1.5.dp.toPx()
    drawRoundRect(
        color = color,
        topLeft = Offset(stroke / 2, stroke / 2),
        size = Size(size.width - stroke, size.height - stroke),
        cornerRadius = CornerRadius(24.dp.toPx()),
        style = Stroke(width = stroke, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
    )
}

@Composable
private fun PublicAddressField(value: String, onValueChange: (String) -> Unit, onClear: () -> Unit) {
    val label = stringResource(R.string.connection_public_url)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 4.dp)
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text(
                    text = "https://relay.example.org",
                    fontFamily = OrsikMono,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                // A URL keyboard: no auto-space after ".", no autocorrect.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = OrsikMono,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = label }
                    .testTag("connectionPublicUrl")
            )
        }
        IconButton(onClick = onClear, modifier = Modifier.testTag("connectionRemovePublic")) {
            Icon(OrsikIcons.Close, contentDescription = stringResource(R.string.connection_remove_public), modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun CertificateCard(alias: String?, enabled: Boolean, warn: Boolean, onChoose: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(start = 16.dp, end = 14.dp, top = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Tile(OrsikIcons.Key, MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(
                        text = alias ?: stringResource(R.string.connection_cert_none),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("connectionCertificate")
                    )
                    Text(
                        text = stringResource(R.string.connection_cert_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                FilledTonalButton(onClick = onChoose, enabled = enabled, modifier = Modifier.testTag("connectionChooseCertificate")) {
                    Text(stringResource(if (alias == null) R.string.connection_cert_choose else R.string.connection_cert_change), fontWeight = FontWeight.Bold)
                }
            }
            if (warn) {
                val status = OrsikTheme.statusColors
                Text(
                    text = stringResource(R.string.connection_cert_warning),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = status.onWarnContainer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(status.warnContainer, RoundedCornerShape(12.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("connectionCertWarning")
                )
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
