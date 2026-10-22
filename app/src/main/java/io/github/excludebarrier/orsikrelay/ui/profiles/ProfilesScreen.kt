package io.github.excludebarrier.orsikrelay.ui.profiles

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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import io.github.excludebarrier.orsikrelay.ui.components.OrsikLargeHeader
import io.github.excludebarrier.orsikrelay.ui.components.PreviewBadge
import io.github.excludebarrier.orsikrelay.ui.components.PreviewTile
import io.github.excludebarrier.orsikrelay.ui.components.ServerBadge
import io.github.excludebarrier.orsikrelay.ui.components.ServerProfileCard
import io.github.excludebarrier.orsikrelay.ui.components.StatusChip
import io.github.excludebarrier.orsikrelay.ui.components.StatusKind
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikShapes
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikType
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
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    activeProfileId: String? = null,
    // Reachability of the active server (the same probe as Home); null
    // while unknown. Only the active card shows it.
    activeReachable: Boolean? = null,
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
            .testTag(PROFILES_SCREEN_TAG)
    ) {
        // As a bottom-bar tab the screen has no way back; opened from
        // settings or the Home server switcher it keeps its back arrow.
        OrsikLargeHeader(title = stringResource(R.string.profiles_title), onBack = onBack)
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when (val current = profiles) {
                null -> item(key = "state") {
                    if (loadFailed) {
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
                }

                else -> if (current.isEmpty()) {
                    item(key = "state") {
                        Text(
                            text = stringResource(R.string.profiles_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp)
                        )
                    }
                } else {
                    items(current, key = { profile -> profile.id }) { profile ->
                        ProfileRow(
                            profile = profile,
                            isActive = profile.id == activeProfileId,
                            reachable = activeReachable,
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
            item(key = "add") {
                OutlinedButton(
                    onClick = onAddServer,
                    shape = OrsikShapes.pill,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .testTag("addServerButton")
                ) {
                    Icon(OrsikIcons.Plus, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(text = stringResource(R.string.onboarding_title), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
            item(key = "management") { ManagementPreview() }
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
    reachable: Boolean?,
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
    // The active card and every card while a selection is in flight are
    // disabled, so a tap can neither re-select the active server nor start a
    // second selection.
    val rowEnabled = !isActive && !isSelecting
    val badges = buildList {
        if (isActive) add(ServerBadge(stringResource(R.string.profiles_chip_active), highlighted = true))
        when (credentialKind) {
            ProfileCredentialKind.DEVICE_CREDENTIAL -> add(ServerBadge(stringResource(R.string.profiles_chip_paired)))
            ProfileCredentialKind.API_KEY -> add(ServerBadge(stringResource(R.string.profiles_chip_api_key)))
            // A failed kind query shows no credential badge; the removal
            // path still treats it as a device profile.
            null -> Unit
        }
    }
    ServerProfileCard(
        name = profile.profileName,
        address = address,
        active = isActive,
        badges = badges,
        onClick = onClick,
        enabled = rowEnabled,
        modifier = Modifier.testTag("serverRow:${profile.id}"),
        status = {
            when {
                !isActive -> StatusChip(stringResource(R.string.profiles_status_not_connected), StatusKind.Neutral)
                reachable == true -> StatusChip(stringResource(R.string.home_server_reachable), StatusKind.Ok)
                reachable == false -> StatusChip(stringResource(R.string.home_status_unreachable), StatusKind.Error)
                else -> Unit
            }
        },
        actions = {
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    enabled = !isSelecting,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("serverActions:${profile.id}")
                ) {
                    Icon(
                        imageVector = OrsikIcons.MoreVert,
                        contentDescription = stringResource(R.string.profiles_actions),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
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
    )
}

/**
 * "Verwaltung": the future server tools as disabled, dashed tiles. Nothing
 * here does anything yet; no live data is shown.
 */
@Composable
private fun ManagementPreview() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 14.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(start = 4.dp, bottom = 2.dp)
        ) {
            Text(
                text = stringResource(R.string.profiles_management),
                style = OrsikType.sectionTitle,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() }
            )
            PreviewBadge()
        }
        val tiles = listOf(
            Triple("monitoring", R.string.profiles_tile_monitoring, OrsikIcons.Activity),
            Triple("docker", R.string.home_quick_docker, OrsikIcons.Container),
            Triple("terminal", R.string.home_quick_terminal, OrsikIcons.Terminal),
            Triple("files", R.string.home_quick_files, OrsikIcons.Folder)
        )
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (key, label, icon) ->
                    PreviewTile(
                        label = stringResource(label),
                        icon = icon,
                        stacked = false,
                        modifier = Modifier
                            .weight(1f)
                            .testTag(MANAGEMENT_TILE_PREFIX + key)
                    )
                }
            }
        }
    }
}

internal const val PROFILES_SCREEN_TAG = "profiles_screen"
internal const val MANAGEMENT_TILE_PREFIX = "server_tool_"

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
