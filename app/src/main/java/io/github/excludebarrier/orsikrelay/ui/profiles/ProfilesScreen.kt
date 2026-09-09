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
 * Server profile management screen (Mission 010).
 *
 * - Lists the stored profiles exactly as [ProfileAdmin.listProfiles]
 *   reports them (name plus base URL); no network request is made here.
 * - Deletion goes through the verified [ProfileAdmin.deleteProfile] path
 *   (credential, metadata, drafts) and requires an explicit confirmation
 *   dialog; tapping outside cancels.
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
    onBack: () -> Unit,
    onProfileDeleted: (profileId: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()
    var profiles by remember { mutableStateOf<List<ServerProfile>?>(null) }
    var deleteTarget by remember { mutableStateOf<ServerProfile?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var deleteFailed by remember { mutableStateOf(false) }

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
                                onClick = {
                                    if (deleteTarget == null) {
                                        deleteTarget = profile
                                        deleteFailed = false
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
}

@Composable
private fun ProfileRow(
    profile: ServerProfile,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
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
    }
}
