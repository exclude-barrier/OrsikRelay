package io.github.excludebarrier.orsikrelay.ui.profiles

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.security.KeyChain
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionAdmin
import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionMode
import io.github.excludebarrier.orsikrelay.domain.profile.ConnectionSettings
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileBaseUrlValidator
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A profile's second, public endpoint: its HTTPS URL, the mode and the
 * client certificate (picked in the system dialog; the key stays in the
 * Android key store). The device key reaches the public endpoint only after
 * the bridge there reports the server identity pinned from the paired one.
 */
@Composable
internal fun ConnectionDialog(
    profile: ServerProfile,
    admin: ConnectionAdmin,
    onDismiss: () -> Unit,
    onSaved: () -> Unit
) {
    val current = profile.connection
    var url by remember { mutableStateOf(current.publicBaseUrl.orEmpty()) }
    var mode by remember { mutableStateOf(current.mode) }
    var alias by remember { mutableStateOf(current.clientCertAlias) }
    var error by remember { mutableStateOf<Int?>(null) }
    var saving by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val activity = LocalContext.current.findActivity()

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

    AlertDialog(
        onDismissRequest = { if (!saving) onDismiss() },
        modifier = Modifier.testTag("connectionDialog"),
        title = { Text(stringResource(R.string.connection_title)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.connection_private, profile.baseUrl.removePrefix("https://")),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it; error = null },
                    label = { Text(stringResource(R.string.connection_public_url)) },
                    placeholder = { Text("https://relay.example.org") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("connectionPublicUrl")
                )
                Column(Modifier.selectableGroup()) {
                    listOf(
                        ConnectionMode.AUTOMATIC to R.string.connection_mode_automatic,
                        ConnectionMode.PRIVATE_ONLY to R.string.connection_mode_private,
                        ConnectionMode.PUBLIC_ONLY to R.string.connection_mode_public
                    ).forEach { (option, label) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = mode == option, role = Role.RadioButton) { mode = option }
                                .testTag("connectionMode:${option.name}")
                        ) {
                            RadioButton(selected = mode == option, onClick = null)
                            Text(stringResource(label))
                        }
                    }
                }
                Text(
                    text = alias?.let { stringResource(R.string.connection_certificate, it) }
                        ?: stringResource(R.string.connection_certificate_none),
                    style = MaterialTheme.typography.bodyMedium
                )
                OutlinedButton(
                    enabled = activity != null && !saving,
                    onClick = {
                        val host = ProfileBaseUrlValidator.validate(url.trim()).getOrNull()
                            ?.let { runCatching { URI(it) }.getOrNull() }
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
                    },
                    modifier = Modifier.testTag("connectionChooseCertificate")
                ) { Text(stringResource(R.string.connection_choose_certificate)) }
                Text(
                    text = stringResource(R.string.connection_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let {
                    Text(
                        text = stringResource(it),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.testTag("connectionError")
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { save() }, enabled = !saving, modifier = Modifier.testTag("connectionSave")) {
                Text(stringResource(R.string.connection_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !saving) { Text(stringResource(R.string.connection_cancel)) }
        }
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
