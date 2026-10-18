package io.github.excludebarrier.orsikrelay.ui.profiles

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.device.DeviceDisconnectError
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
 * - Each row is compact and carries the non-secret credential kind as a
 *   chip ("Gekoppelt" / "API-Key"); the active server shows the "Aktiv"
 *   chip. Tapping a non-active row switches the active server via
 *   [onServerSelected] (exactly like the ⋮ action
 *   [R.string.profiles_select_active]). Tapping the active row does
 *   nothing. Selection is pure metadata.
 * - Deletion is a separate, confirmed action behind the row's ⋮ menu. It
 *   opens the existing confirmation dialog unchanged.
 * - A profile whose credential kind is
 *   [ProfileCredentialKind.DEVICE_CREDENTIAL] (derived from NON-SECRET
 *   device metadata only) has exactly ONE removal action, "Löschen".
 *   Its confirmation dialog states that the device is disconnected on the
 *   server first and only then removed locally. Confirming runs the
 *   existing, verified disconnect path, which revokes the device access
 *   exactly once and removes the local profile only after the server
 *   acknowledged the revocation. API-key profiles keep the pure local
 *   deletion path and can never trigger a self-revoke.
 * - If the revocation fails or the device access cannot be loaded locally,
 *   NOTHING is deleted and nothing is retried automatically. The dialog
 *   shows a fixed, generic hint (no URL, profile ID or exception detail)
 *   and offers the deliberate second action "Nur auf diesem Gerät
 *   entfernen", which warns that the server-side access stays valid until
 *   it is revoked there. The revocation itself can only be retried by
 *   explicitly tapping "Erneut versuchen" (one request per tap). A local
 *   cleanup failure after a successful revocation keeps the existing
 *   generic message; its explicit retry repeats the local deletion only.
 * - While a removal is in flight every dialog button is disabled so a
 *   double tap cannot start a second revocation.
 *
 * [admin] is the test seam; production injects
 * [io.github.excludebarrier.orsikrelay.data.profile.ServerProfileAdmin].
 * [onDeviceDisconnected] (default no-op) is the safe-navigation hook,
 * triggered exactly like [onProfileDeleted] after a successful
 * disconnect.
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
        DeviceDisconnectResult.Failure(DeviceDisconnectError.InvalidState)
    },
    // Opens the onboarding route to add another server. The bottom action
    // is always present, also in the empty state.
    onAddServer: () -> Unit = {}
) {
    val coroutineScope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf<List<ServerProfile>?>(null) }
    // A load failure must be reachable and retryable: without this the screen
    // stayed on the loading text forever after a transient store failure.
    var loadFailed by remember { mutableStateOf(false) }
    // Profile ID -> non-secret credential kind, loaded
    // together with the list (device metadata only, no credential read).
    // A null kind means the query failed: such a profile is NOT treated as
    // an API-key profile (that would delete it locally and leave a possible
    // device access valid on the server) but takes the disconnect path.
    var credentialKinds by remember { mutableStateOf<Map<String, ProfileCredentialKind?>>(emptyMap()) }
    var deleteTarget by remember { mutableStateOf<ServerProfile?>(null) }
    var removalStep by remember { mutableStateOf(RemovalStep.Idle) }
    var removeFailure by remember { mutableStateOf(RemoveFailure.None) }
    // Exactly one selection may be in flight; the button is
    // disabled while it runs and the navigation switch is triggered only
    // after the persist has succeeded.
    var selecting by remember { mutableStateOf<String?>(null) }

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
            // The credential kind is NON-SECRET metadata
            // (device metadata presence only; no credential is read).
            // It is loaded together with the list. A known API_KEY profile
            // keeps the pure local deletion; a DEVICE_CREDENTIAL profile and
            // a failed query (null) go through the disconnect path, whose
            // production source re-checks the device metadata and fails
            // closed into the fallback dialog.
            credentialKinds = withContext(Dispatchers.IO) {
                list.associate { profile ->
                    profile.id to
                        try {
                            admin.credentialKind(profile.id)
                        } catch (_: Exception) {
                            null
                        }
                }
            }
            // Rows appear only together with their kinds, so a tap can never
            // see a profile whose kind has not been queried yet.
            profiles = list
        }
    }

    fun finishRemoval(target: ServerProfile, deviceDisconnected: Boolean) {
        deleteTarget = null
        loadProfiles()
        if (deviceDisconnected) {
            onDeviceDisconnected(target.id)
        } else {
            onProfileDeleted(target.id)
        }
    }

    // Local-only deletion. Used for API-key profiles and as the deliberate
    // fallback when the device revocation could not be completed. It never
    // talks to the server and therefore uses the profile-deletion
    // navigation hook, not the disconnect one.
    fun confirmLocalDelete(target: ServerProfile) {
        if (removalStep != RemovalStep.Idle) return
        removalStep = RemovalStep.Deleting
        coroutineScope.launch {
            val failed = try {
                withContext(Dispatchers.IO) { admin.deleteProfile(target.id) }
                false
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                true
            }
            removalStep = RemovalStep.Idle
            if (failed) {
                removeFailure = RemoveFailure.DeletionFailed
            } else {
                finishRemoval(target, deviceDisconnected = false)
            }
        }
    }

    // The single removal action. Device-credential profiles go through the
    // existing disconnect orchestration (one revocation per request);
    // API-key profiles keep the local deletion path.
    fun confirmRemove(target: ServerProfile) {
        if (removalStep != RemovalStep.Idle) return
        removeFailure = RemoveFailure.None
        if (!revokesOnDelete(credentialKinds, target.id)) {
            confirmLocalDelete(target)
            return
        }
        removalStep = RemovalStep.Disconnecting
        coroutineScope.launch {
            val result = try {
                withContext(Dispatchers.IO) { disconnect(target.id) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                DeviceDisconnectResult.Failure(DeviceDisconnectError.RevokeFailed)
            }
            removalStep = RemovalStep.Idle
            when (result) {
                is DeviceDisconnectResult.Disconnected -> {
                    // The verified deletion already removed the profile
                    // from the stores.
                    finishRemoval(target, deviceDisconnected = true)
                }

                is DeviceDisconnectResult.Failure -> when (result.reason) {
                    DeviceDisconnectError.InvalidState,
                    DeviceDisconnectError.RevokeFailed ->
                        // Nothing local was deleted; offer the fallback
                        // and an explicit retry.
                        removeFailure = RemoveFailure.RevokeFailed

                    DeviceDisconnectError.DeletionFailed ->
                        removeFailure = RemoveFailure.DeletionFailed
                }
            }
        }
    }

    // The deliberate second action of the fallback dialog: remove the
    // profile locally even though the server access stays valid. This is a
    // plain local deletion, so it uses the profile-deletion navigation
    // hook, not the disconnect one.
    fun confirmLocalOnlyRemove(target: ServerProfile) {
        if (removalStep != RemovalStep.Idle) return
        confirmLocalDelete(target)
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

    LaunchedEffect(Unit) {
        loadProfiles()
    }

    fun requestDelete(target: ServerProfile) {
        deleteTarget = target
        removalStep = RemovalStep.Idle
        removeFailure = RemoveFailure.None
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
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
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
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 8.dp)
                        ) {
                            itemsIndexed(current, key = { _, profile -> profile.id }) { index, profile ->
                                Column {
                                    if (index > 0) {
                                        HorizontalDivider(
                                            color = MaterialTheme.colorScheme.outlineVariant
                                        )
                                    }
                                    ProfileRow(
                                        profile = profile,
                                        isActive = profile.id == activeProfileId,
                                        isSelecting = selecting != null,
                                        credentialKind = credentialKinds[profile.id],
                                        onClick = {
                                            if (profile.id != activeProfileId) {
                                                confirmSelect(profile)
                                            }
                                        },
                                        onUseAsActive = { confirmSelect(profile) },
                                        onDelete = { requestDelete(profile) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        // Same bottom placement as the composer: the list takes the
        // remaining height and this action stays at the bottom edge. The
        // activity root already applies the safe-drawing insets, so no
        // navigation-bar inset is added here.
        Button(
            onClick = onAddServer,
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp)
                .testTag("addServerButton")
        ) {
            Text(text = stringResource(R.string.onboarding_title))
        }
    }

    val target = deleteTarget
    if (target != null) {
        val deviceProfile = revokesOnDelete(credentialKinds, target.id)
        val inFlight = removalStep != RemovalStep.Idle
        val fallback = removeFailure == RemoveFailure.RevokeFailed
        AlertDialog(
            onDismissRequest = { if (!inFlight) deleteTarget = null },
            title = { Text(text = stringResource(R.string.profiles_delete_title)) },
            text = {
                Column {
                    Text(
                        text = if (deviceProfile) {
                            stringResource(
                                R.string.profiles_delete_message_device,
                                target.profileName
                            )
                        } else {
                            stringResource(
                                R.string.profiles_delete_message,
                                target.profileName
                            )
                        },
                        style = MaterialTheme.typography.bodyLarge
                    )
                    if (inFlight) {
                        Text(
                            text = if (removalStep == RemovalStep.Disconnecting) {
                                stringResource(R.string.profiles_delete_disconnecting)
                            } else {
                                stringResource(R.string.profiles_deleting)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    if (fallback) {
                        // Fixed, generic hint without URL, profile ID or
                        // exception details.
                        Text(
                            text = stringResource(R.string.profiles_delete_revoke_error_generic),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                        Text(
                            text = stringResource(R.string.profiles_delete_local_only_warning),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                    if (removeFailure == RemoveFailure.DeletionFailed) {
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
                if (fallback) {
                    // Three actions with long labels do not fit side by side:
                    // they are stacked at full width, the deliberate local
                    // removal first, the explicit retry and "Abbrechen" below.
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Deliberate second action: local removal only.
                        Button(
                            onClick = { confirmLocalOnlyRemove(target) },
                            enabled = !inFlight,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                                contentColor = MaterialTheme.colorScheme.onError
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("profileDeleteLocalOnly")
                        ) {
                            Text(text = stringResource(R.string.profiles_delete_local_only))
                        }
                        // The revocation itself may only be retried by an
                        // explicit tap, one request per tap.
                        FilledTonalButton(
                            onClick = { confirmRemove(target) },
                            enabled = !inFlight,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("profileDeleteRetry")
                        ) {
                            Text(text = stringResource(R.string.sessions_retry))
                        }
                        TextButton(
                            onClick = { deleteTarget = null },
                            enabled = !inFlight,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("profileDeleteDismiss")
                        ) {
                            Text(text = stringResource(R.string.new_session_cancel))
                        }
                    }
                } else {
                    Button(
                        onClick = {
                            if (removeFailure == RemoveFailure.DeletionFailed) {
                                // The revocation already succeeded; only
                                // the local cleanup failed, so the explicit
                                // retry repeats the local deletion and
                                // never issues a second revocation.
                                confirmLocalDelete(target)
                            } else {
                                confirmRemove(target)
                            }
                        },
                        enabled = !inFlight,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                            contentColor = MaterialTheme.colorScheme.onError
                        ),
                        modifier = Modifier.testTag("profileDeleteConfirm")
                    ) {
                        Text(
                            text = if (removeFailure == RemoveFailure.None) {
                                stringResource(R.string.profiles_delete_confirm)
                            } else {
                                stringResource(R.string.sessions_retry)
                            }
                        )
                    }
                }
            },
            // In the fallback state "Abbrechen" is part of the stacked actions.
            dismissButton = if (fallback) {
                null
            } else {
                {
                    TextButton(
                        onClick = { deleteTarget = null },
                        enabled = !inFlight,
                        modifier = Modifier.testTag("profileDeleteDismiss")
                    ) {
                        Text(text = stringResource(R.string.new_session_cancel))
                    }
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
    credentialKind: ProfileCredentialKind?,
    onClick: () -> Unit,
    onUseAsActive: () -> Unit,
    onDelete: () -> Unit
) {
    var menuOpen by remember(profile.id) { mutableStateOf(false) }
    val address = remember(profile.baseUrl) {
        profile.baseUrl.removePrefix("https://")
    }
    // The active row and every row while a selection is in flight are
    // disabled, so a tap can neither re-select the active server nor start a
    // second selection.
    val rowEnabled = !isActive && !isSelecting
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(enabled = rowEnabled, onClick = onClick)
            .padding(vertical = 8.dp)
            .testTag("serverRow:${profile.id}"),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = DnsIcon,
            // Decorative: the row already names the server.
            contentDescription = null,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.profileName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = address,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp)
            )
            if (isActive ||
                credentialKind == ProfileCredentialKind.DEVICE_CREDENTIAL ||
                credentialKind == ProfileCredentialKind.API_KEY
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 6.dp)
                ) {
                    if (isActive) {
                        ProfileChip(
                            label = stringResource(R.string.profiles_chip_active),
                            highlighted = true,
                            showCheck = true
                        )
                    }
                    when (credentialKind) {
                        ProfileCredentialKind.DEVICE_CREDENTIAL -> ProfileChip(
                            label = stringResource(R.string.profiles_chip_paired),
                            highlighted = false
                        )

                        ProfileCredentialKind.API_KEY -> ProfileChip(
                            label = stringResource(R.string.profiles_chip_api_key),
                            highlighted = false
                        )

                        // A failed kind query shows no credential chip; the
                        // removal path still treats it as a device profile.
                        null -> Unit
                    }
                }
            }
        }
        Box {
            IconButton(
                onClick = { menuOpen = true },
                enabled = !isSelecting,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("serverActions:${profile.id}")
            ) {
                Icon(
                    imageVector = Icons.Filled.MoreVert,
                    contentDescription = stringResource(R.string.profiles_actions)
                )
            }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false }
            ) {
                if (!isActive) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.profiles_select_active)) },
                        onClick = {
                            menuOpen = false
                            onUseAsActive()
                        },
                        enabled = !isSelecting,
                        modifier = Modifier.testTag("selectActiveServer:${profile.id}")
                    )
                }
                DropdownMenuItem(
                    text = { Text(text = stringResource(R.string.profiles_delete_confirm)) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                    enabled = !isSelecting,
                    modifier = Modifier.testTag("serverDelete:${profile.id}")
                )
            }
        }
    }
}

@Composable
private fun ProfileChip(
    label: String,
    highlighted: Boolean,
    showCheck: Boolean = false
) {
    val containerColor = if (highlighted) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (highlighted) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (showCheck) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
            }
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

/**
 * Whether deleting [profileId] has to revoke a device access first. Only a
 * profile known to hold an API key is deleted purely locally; an unknown
 * kind (failed query) is handled like a device profile.
 */
private fun revokesOnDelete(
    kinds: Map<String, ProfileCredentialKind?>,
    profileId: String
): Boolean = kinds[profileId] != ProfileCredentialKind.API_KEY

/** In-flight step of the unified removal action. */
private enum class RemovalStep { Idle, Disconnecting, Deleting }

/** Closed failure state shown inside the removal dialog. */
private enum class RemoveFailure { None, RevokeFailed, DeletionFailed }
