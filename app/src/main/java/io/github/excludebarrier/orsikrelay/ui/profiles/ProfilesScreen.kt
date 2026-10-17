package io.github.excludebarrier.orsikrelay.ui.profiles

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.device.DeviceDisconnectResult
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileAdmin
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileCredentialKind
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Server management screen.
 *
 * - Lists the stored servers exactly as [ProfileAdmin.listProfiles]
 *   reports them (name plus base URL); no network request is made here.
 * - The currently active server is marked; the unambiguous action
 *   [R.string.profiles_select_active] switches the active server via
 *   [onServerSelected]. Selection is pure metadata.
 * - Deletion stays a separate, confirmed action (tap the row to open the
 *   dialog) through the verified [ProfileAdmin.deleteProfile] path.
 * - A profile whose credential kind is
 *   [ProfileCredentialKind.DEVICE_CREDENTIAL] (derived from NON-SECRET
 *   device metadata only) shows the additional "Gerät trennen" action.
 *   It is a TWO-STEP action: the row action opens a dedicated dialog,
 *   whose confirm button performs the disconnect. API-key profiles never
 *   offer this action and can never trigger a self-revoke.
 * - While a disconnect is in flight the confirm button is disabled so a
 *   double tap cannot start a second revocation; a remote failure deletes
 *   nothing locally and shows a fixed, generic German message with no
 *   profile ID, URL or exception detail.
 *
 * [admin] is the test seam; production injects
 * [io.github.excludebarrier.orsikrelay.data.profile.ServerProfileAdmin].
 * [onDeviceDisconnected] (default no-op) is the safe-navigation hook,
 * triggered exactly like [onProfileDeleted] after a
 * successful disconnect.
 */
@Composable
fun ProfilesScreen(
    admin: ProfileAdmin,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    activeProfileId: String? = null,
    onProfileDeleted: (profileId: String) -> Unit = {},
    onServerSelected: (profileId: String) -> Unit = {},
    onDeviceDisconnected: (profileId: String) -> Unit = {},
    disconnect: (profileId: String) -> DeviceDisconnectResult = {
        DeviceDisconnectResult.Failure(
            io.github.excludebarrier.orsikrelay.domain.device.DeviceDisconnectError.InvalidState
        )
    }
) {
    val coroutineScope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf<List<ServerProfile>?>(null) }
    // A load failure must be reachable and retryable: without this the screen
    // stayed on the loading text forever after a transient store failure.
    var loadFailed by remember { mutableStateOf(false) }
    // Profile ID -> non-secret credential kind, loaded
    // together with the list (device metadata only, no credential read).
    var credentialKinds by remember { mutableStateOf<Map<String, ProfileCredentialKind>>(emptyMap()) }
    var deleteTarget by remember { mutableStateOf<ServerProfile?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var deleteFailed by remember { mutableStateOf(false) }
    // Exactly one selection may be in flight; the button is
    // disabled while it runs and the navigation switch is triggered only
    // after the persist has succeeded.
    var selecting by remember { mutableStateOf<String?>(null) }
    // The two-step "Gerät trennen" state. [disconnectTarget]
    // is the profile whose confirmation dialog is open; [disconnecting]
    // guards the single in-flight revocation; [disconnectFailed] drives
    // the fixed, generic error text.
    var disconnectTarget by remember { mutableStateOf<ServerProfile?>(null) }
    var disconnecting by remember { mutableStateOf(false) }
    var disconnectFailed by remember { mutableStateOf(false) }

    fun loadProfiles() {
        coroutineScope.launch {
            loadFailed = false
            val list = try {
                withContext(Dispatchers.IO) { admin.listProfiles() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (list == null) {
                loadFailed = true
                return@launch
            }
            profiles = list
            // The credential kind is NON-SECRET metadata
            // (device metadata presence only; no credential is read).
            // It is loaded together with the list; a query failure or a
            // non-device kind simply means the action is not offered —
            // fail-closed.
            credentialKinds = withContext(Dispatchers.IO) {
                list.associate { profile ->
                    profile.id to
                        try {
                            admin.credentialKind(profile.id)
                        } catch (_: Exception) {
                            ProfileCredentialKind.API_KEY
                        }
                }
            }
        }
    }

    fun confirmDelete(target: ServerProfile) {
        if (deleting) return
        deleting = true
        deleteFailed = false
        coroutineScope.launch {
            val failed = try {
                withContext(Dispatchers.IO) { admin.deleteProfile(target.id) }
                false
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                true
            }
            deleting = false
            if (failed) {
                deleteFailed = true
            } else {
                deleteTarget = null
                loadProfiles()
                onProfileDeleted(target.id)
            }
        }
    }

    // Persist first, navigate second. The safe navigation
    // switch ([onServerSelected]) is triggered only after
    // [ProfileAdmin.selectActiveServer] succeeded, so a failed selection is
    // never applied optimistically. A store failure keeps the previous
    // selection on disk AND on screen: no state is changed and the user can
    // retry. No error details or IDs are surfaced on this path.
    fun confirmSelect(target: ServerProfile) {
        if (selecting != null) return
        selecting = target.id
        coroutineScope.launch {
            val persisted = try {
                withContext(Dispatchers.IO) { admin.selectActiveServer(target.id) }
                true
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                // Persist failed: the switch is NOT triggered and the
                // previous selection (in memory and on disk) is unchanged.
                false
            } finally {
                selecting = null
            }
            if (persisted) {
                onServerSelected(target.id)
            }
        }
    }

    // Exactly one disconnect may be in flight. Only the
    // confirmed path runs [disconnect]; the revocation happens exactly
    // once inside it and a failure deletes nothing locally.
    fun confirmDisconnect(target: ServerProfile) {
        if (disconnecting) return
        disconnecting = true
        disconnectFailed = false
        coroutineScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { disconnect(target.id) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                DeviceDisconnectResult.Failure(
                    io.github.excludebarrier.orsikrelay.domain.device.DeviceDisconnectError.RevokeFailed
                )
            }
            disconnecting = false
            if (result is DeviceDisconnectResult.Disconnected) {
                disconnectTarget = null
                // The verified deletion already removed the profile from
                // the stores; refresh the list so the row disappears, then
                // trigger the safe navigation hook (same order as the
                // deletion path).
                loadProfiles()
                onDeviceDisconnected(target.id)
            } else {
                // Fixed generic error; the dialog stays open, nothing
                // local was deleted.
                disconnectFailed = true
            }
        }
    }

    LaunchedEffect(Unit) {
        loadProfiles()
    }

    Column(
        modifier = modifier
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
                text = stringResource(R.string.profiles_title),
                style = MaterialTheme.typography.headlineMedium
            )
            Spacer(modifier = Modifier.weight(1f))
            TextButton(onClick = onBack) {
                Text(text = stringResource(R.string.chat_back))
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
        when (val current = profiles) {
            null -> if (loadFailed) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.profiles_error_generic),
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = { loadProfiles() },
                        modifier = Modifier.testTag("profiles_retry")
                    ) {
                        Text(text = stringResource(R.string.sessions_retry))
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.profiles_loading),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                )
            }

            else -> {
                if (current.isEmpty()) {
                    Text(
                        text = stringResource(R.string.profiles_empty),
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
                            .weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(current, key = { it.id }) { profile ->
                            ProfileRow(
                                profile = profile,
                                isActive = profile.id == activeProfileId,
                                isSelecting = selecting != null,
                                isDeviceProfile = credentialKinds[profile.id] ==
                                    ProfileCredentialKind.DEVICE_CREDENTIAL,
                                onClick = {
                                    if (deleteTarget == null) {
                                        deleteTarget = profile
                                        deleteFailed = false
                                    }
                                },
                                onUseAsActive = { confirmSelect(profile) },
                                onDisconnectDevice = {
                                    if (disconnectTarget == null) {
                                        disconnectTarget = profile
                                        disconnectFailed = false
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    val target = deleteTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { if (!deleting) deleteTarget = null },
            title = { Text(text = stringResource(R.string.profiles_delete_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(
                            R.string.profiles_delete_message,
                            target.profileName
                        ),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (deleting) {
                        Text(
                            text = stringResource(R.string.profiles_deleting),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    if (deleteFailed) {
                        Text(
                            text = stringResource(R.string.profiles_delete_error_generic),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { confirmDelete(target) },
                    enabled = !deleting
                ) {
                    Text(
                        text = if (deleteFailed) {
                            stringResource(R.string.sessions_retry)
                        } else {
                            stringResource(R.string.profiles_delete_confirm)
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { deleteTarget = null },
                    enabled = !deleting
                ) {
                    Text(text = stringResource(R.string.new_session_cancel))
                }
            }
        )
    }

    // Second, dedicated confirmation step of the
    // "Gerät trennen" action. Opening the dialog costs nothing; only the
    // confirm button triggers the single revocation.
    val disconnectingTarget = disconnectTarget
    if (disconnectingTarget != null) {
        AlertDialog(
            onDismissRequest = { if (!disconnecting) disconnectTarget = null },
            title = { Text(text = stringResource(R.string.profiles_device_disconnect_title)) },
            text = {
                Column {
                    Text(
                        text = stringResource(
                            R.string.profiles_device_disconnect_message,
                            disconnectingTarget.profileName
                        ),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (disconnecting) {
                        Text(
                            text = stringResource(R.string.profiles_device_disconnecting),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    if (disconnectFailed) {
                        Text(
                            text = stringResource(R.string.profiles_device_disconnect_error_generic),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { confirmDisconnect(disconnectingTarget) },
                    enabled = !disconnecting,
                    modifier = Modifier.testTag("deviceDisconnectConfirm")
                ) {
                    Text(
                        text = if (disconnectFailed) {
                            stringResource(R.string.sessions_retry)
                        } else {
                            stringResource(R.string.profiles_device_disconnect_confirm)
                        }
                    )
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { disconnectTarget = null },
                    enabled = !disconnecting,
                    modifier = Modifier.testTag("deviceDisconnectDismiss")
                ) {
                    Text(text = stringResource(R.string.new_session_cancel))
                }
            }
        )
    }
}

@Composable
private fun ProfileRow(
    profile: ServerProfile,
    isActive: Boolean,
    isSelecting: Boolean,
    isDeviceProfile: Boolean,
    onClick: () -> Unit,
    onUseAsActive: () -> Unit,
    onDisconnectDevice: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = if (isActive) {
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = profile.profileName,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = profile.baseUrl,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (isActive) {
                    // Visible marker only; it carries no testTag because the
                    // clickable Card is a semantics merge boundary, so a tag
                    // on this inner Text is not addressable by itself. The
                    // test asserts the marker by its unique visible text.
                    Text(
                        text = stringResource(R.string.profiles_active),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (!isActive) {
                // Separate, unambiguous action: tapping the row
                // opens the deletion confirmation, while only this button
                // selects the server. Selection reads no credential on this
                // path. The inner button intercepts taps on its own area.
                TextButton(
                    onClick = onUseAsActive,
                    enabled = !isSelecting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                        .testTag("selectActiveServer:${profile.id}")
                ) {
                    Text(text = stringResource(R.string.profiles_select_active))
                }
            }
            // Offered ONLY for device-credential profiles.
            // API-key profiles never render this button and can therefore
            // never start a self-revoke. The row action only OPENS the
            // dedicated confirmation dialog; the revocation itself happens
            // exclusively on the dialog's confirm button.
            if (isDeviceProfile) {
                TextButton(
                    onClick = onDisconnectDevice,
                    enabled = !isSelecting,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                        .testTag("disconnectDevice:${profile.id}")
                ) {
                    Text(text = stringResource(R.string.profiles_device_disconnect_action))
                }
            }
        }
    }
}
