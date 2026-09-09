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
import io.github.excludebarrier.orsikrelay.domain.profile.ProfileAdmin
import io.github.excludebarrier.orsikrelay.domain.profile.ServerProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Server management screen (Mission 010, extended in Missions 011/012).
 *
 * - Lists the stored servers exactly as [ProfileAdmin.listProfiles]
 *   reports them (name plus base URL); no network request is made here.
 * - The currently active server is marked; the unambiguous action
 *   [R.string.profiles_select_active] switches the active server via
 *   [onServerSelected]. Selection is pure metadata — it reads no
 *   credential and makes no request on this path.
 * - (Mission 012) The selection is PERSISTED first
 *   ([ProfileAdmin.selectActiveServer]) and the safe navigation switch
 *   ([onServerSelected]) is triggered only after the persist succeeded.
 *   A failed or in-flight persist never triggers the switch, so a failed
 *   selection is never applied optimistically. While a selection is in
 *   flight the button is disabled so a double tap cannot start a second
 *   one.
 * - Deletion stays a separate, confirmed action (tap the row to open the
 *   dialog) through the verified [ProfileAdmin.deleteProfile] path
 *   (credential, metadata, drafts); tapping outside cancels.
 * - While a deletion is in flight, the confirm button is disabled so a
 *   double tap can never start a second deletion; the dialog stays open.
 * - A failure is reported with a fixed, generic German message that
 *   contains no profile ID, URL or exception detail; the dialog stays
 *   open for a manual retry.
 *
 * [admin] is the test seam; production injects [io.github.excludebarrier.orsikrelay.data.profile.ServerProfileAdmin].
 */
@Composable
fun ProfilesScreen(
    admin: ProfileAdmin,
    activeProfileId: String? = null,
    onBack: () -> Unit,
    onProfileDeleted: (profileId: String) -> Unit = {},
    onServerSelected: (profileId: String) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf<List<ServerProfile>?>(null) }
    var deleteTarget by remember { mutableStateOf<ServerProfile?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var deleteFailed by remember { mutableStateOf(false) }
    // Mission 012: exactly one selection may be in flight; the button is
    // disabled while it runs and the navigation switch is triggered only
    // after the persist has succeeded.
    var selecting by remember { mutableStateOf<String?>(null) }

    fun loadProfiles() {
        coroutineScope.launch {
            val list = try {
                withContext(Dispatchers.IO) { admin.listProfiles() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                null
            }
            if (list != null) {
                profiles = list
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

    // Mission 012: persist first, navigate second. The safe navigation
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
            null -> Text(
                text = stringResource(R.string.profiles_loading),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 24.dp)
            )

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
                                onClick = {
                                    if (deleteTarget == null) {
                                        deleteTarget = profile
                                        deleteFailed = false
                                    }
                                },
                                onUseAsActive = { confirmSelect(profile) }
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
}

@Composable
private fun ProfileRow(
    profile: ServerProfile,
    isActive: Boolean,
    isSelecting: Boolean,
    onClick: () -> Unit,
    onUseAsActive: () -> Unit
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
                // Separate, unambiguous action (Mission 011): tapping the row
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
        }
    }
}
