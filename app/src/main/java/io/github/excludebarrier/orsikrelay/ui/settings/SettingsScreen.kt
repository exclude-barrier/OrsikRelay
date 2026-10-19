package io.github.excludebarrier.orsikrelay.ui.settings

import android.content.Context
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.language.SupportedLanguages
import io.github.excludebarrier.orsikrelay.domain.settings.ChatTextSize
import io.github.excludebarrier.orsikrelay.security.applock.AppAuthenticator
import io.github.excludebarrier.orsikrelay.security.applock.AppLockController
import io.github.excludebarrier.orsikrelay.ui.theme.ThemeMode
import io.github.excludebarrier.orsikrelay.ui.theme.ThemeModeController

/**
 * Settings screen with language, appearance, chat text size, notifications
 * and privacy.
 *
 * Each section is a headline plus one rounded [Card] of [SettingsRow]s, as in
 * the Android 16 settings. Language options come exclusively from
 * [SupportedLanguages]; "System" is the leading option and means the device
 * language. Appearance is a single-choice segmented control, like the chat
 * text size. The screen header is a [SettingsTopBar] with a back arrow.
 *
 * [sdkInt] is injectable so the API-31 rule for the Material You switch can
 * be exercised without an older device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    languageController: LanguageController,
    themeController: ThemeModeController,
    chatTextSizeController: ChatTextSizeController,
    screenProtectionController: ScreenProtectionController,
    appLockController: AppLockController,
    materialYouController: MaterialYouController,
    appAuthenticator: AppAuthenticator?,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    sdkInt: Int = Build.VERSION.SDK_INT,
    openNotificationSettings: (Context) -> Unit = { openAppNotificationSettings(it) }
) {
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SettingsTopBar(
                title = stringResource(R.string.settings_title),
                onBack = onBack
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SectionTitle(stringResource(R.string.settings_language_section))
            SettingsCard {
                SettingsRow(
                    leadingIcon = { SettingsVectorIcon(R.drawable.ic_language) }
                ) {
                    LanguageDropdown(languageController = languageController)
                }
            }

            SectionTitle(stringResource(R.string.settings_appearance_section))
            SettingsCard {
                SettingsRow(
                    leadingIcon = { SettingsVectorIcon(R.drawable.ic_palette) }
                ) {
                    ThemeModeSegments(themeController = themeController)
                }
                if (isMaterialYouAvailable(sdkInt)) {
                    SettingsDivider()
                    val materialYou = materialYouController.enabled
                    SettingsRow(
                        modifier = Modifier
                            .testTag("settings_material_you")
                            .toggleable(
                                value = materialYou,
                                role = Role.Switch,
                                onValueChange = { materialYouController.setEnabled(it) }
                            ),
                        leadingIcon = { SettingsVectorIcon(R.drawable.ic_wallpaper) },
                        trailing = {
                            Switch(
                                checked = materialYou,
                                onCheckedChange = null,
                                modifier = Modifier.padding(start = 12.dp)
                            )
                        }
                    ) {
                        Text(
                            text = stringResource(R.string.settings_material_you_label),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = stringResource(R.string.settings_material_you_description),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            SectionTitle(stringResource(R.string.settings_chat_section))
            SettingsCard {
                SettingsRow(
                    leadingIcon = { SettingsVectorIcon(R.drawable.ic_format_size) }
                ) {
                    Text(
                        text = stringResource(R.string.settings_chat_text_size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 8.dp)
                    )
                    ChatTextSizeSegments(controller = chatTextSizeController)
                }
            }

            val context = LocalContext.current
            SectionTitle(stringResource(R.string.settings_notifications_label))
            SettingsCard {
                SettingsRow(
                    modifier = Modifier
                        .testTag("settings_notifications")
                        .clickable(
                            role = Role.Button,
                            onClick = { openNotificationSettings(context) }
                        ),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Notifications,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailing = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                ) {
                    Text(
                        text = stringResource(R.string.settings_notifications_row_label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.settings_notifications_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            SectionTitle(stringResource(R.string.settings_privacy_section))
            SettingsCard {
                val screenProtection = screenProtectionController.enabled
                SettingsRow(
                    modifier = Modifier
                        .testTag("settings_screen_protection")
                        .toggleable(
                            value = screenProtection,
                            role = Role.Switch,
                            onValueChange = { screenProtectionController.setEnabled(it) }
                        ),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailing = {
                        Switch(
                            checked = screenProtection,
                            onCheckedChange = null,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                ) {
                    Text(
                        text = stringResource(R.string.settings_screen_protection_label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.settings_screen_protection_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                SettingsDivider()

                val appLockEnabled = appLockController.enabled
                val canUseAppLock = appAuthenticator?.canAuthenticate() == true
                var appLockAuthPending by remember { mutableStateOf(false) }
                SettingsRow(
                    modifier = Modifier
                        .testTag("settings_app_lock")
                        .toggleable(
                            value = appLockEnabled,
                            enabled = canUseAppLock && !appLockAuthPending,
                            role = Role.Switch,
                            onValueChange = { wantsEnabled ->
                                val authenticator = appAuthenticator ?: return@toggleable
                                appLockAuthPending = true
                                authenticator.authenticate { success ->
                                    appLockAuthPending = false
                                    if (success) appLockController.setEnabled(wantsEnabled)
                                }
                            }
                        ),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Face,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailing = {
                        Switch(
                            checked = appLockEnabled,
                            onCheckedChange = null,
                            enabled = canUseAppLock && !appLockAuthPending,
                            modifier = Modifier.padding(start = 12.dp)
                        )
                    }
                ) {
                    Text(
                        text = stringResource(R.string.settings_app_lock_label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = stringResource(R.string.settings_app_lock_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (!canUseAppLock) {
                    Text(
                        text = stringResource(R.string.settings_app_lock_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 56.dp, end = 16.dp, bottom = 12.dp)
                            .testTag("settings_app_lock_unavailable")
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(languageController: LanguageController) {
    var expanded by remember { mutableStateOf(false) }
    val systemLabel = stringResource(R.string.settings_language_system)
    val currentLabel = languageController.selectedTag
        ?.let { tag -> SupportedLanguages.all.firstOrNull { it.tag == tag }?.displayName }
        ?: systemLabel

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = Modifier
            .fillMaxWidth()
            .testTag("settings_language_box")
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            singleLine = true,
            label = { Text(stringResource(R.string.settings_language_field)) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                .fillMaxWidth()
                .testTag("settings_language_field")
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text(systemLabel) },
                onClick = {
                    expanded = false
                    languageController.select(null)
                },
                modifier = Modifier.testTag("settings_language_system")
            )
            SupportedLanguages.all.forEach { language ->
                DropdownMenuItem(
                    text = { Text(language.displayName) },
                    onClick = {
                        expanded = false
                        languageController.select(language.tag)
                    },
                    modifier = Modifier.testTag("settings_language_${language.tag}")
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThemeModeSegments(themeController: ThemeModeController) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    ) {
        ThemeMode.entries.forEachIndexed { index, mode ->
            SegmentedButton(
                selected = themeController.mode == mode,
                onClick = { themeController.select(mode) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = ThemeMode.entries.size
                ),
                icon = {},
                modifier = Modifier
                    .weight(1f)
                    .testTag("settings_theme_${mode.name}")
            ) {
                Text(text = themeModeLabel(mode))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatTextSizeSegments(controller: ChatTextSizeController) {
    SingleChoiceSegmentedButtonRow(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
    ) {
        ChatTextSize.entries.forEachIndexed { index, size ->
            SegmentedButton(
                selected = controller.size == size,
                onClick = { controller.select(size) },
                shape = SegmentedButtonDefaults.itemShape(
                    index = index,
                    count = ChatTextSize.entries.size
                ),
                icon = {},
                modifier = Modifier
                    .weight(1f)
                    .testTag("settings_chat_text_${size.name.lowercase()}")
            ) {
                Text(text = chatTextSizeLabel(size))
            }
        }
    }
}

@Composable
private fun chatTextSizeLabel(size: ChatTextSize): String = when (size) {
    ChatTextSize.Small -> stringResource(R.string.settings_chat_text_small)
    ChatTextSize.Normal -> stringResource(R.string.settings_chat_text_normal)
    ChatTextSize.Large -> stringResource(R.string.settings_chat_text_large)
}

@Composable
private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.System -> stringResource(R.string.theme_mode_system)
    ThemeMode.Light -> stringResource(R.string.theme_mode_light)
    ThemeMode.Dark -> stringResource(R.string.theme_mode_dark)
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
    )
}

@Composable
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(content = content)
    }
}

@Composable
internal fun SettingsRow(
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIcon != null) {
            Box(
                modifier = Modifier.size(24.dp),
                contentAlignment = Alignment.Center
            ) {
                leadingIcon()
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .then(
                    if (leadingIcon != null) Modifier.padding(start = 16.dp) else Modifier
                ),
            content = content
        )
        trailing()
    }
}

@Composable
internal fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 56.dp),
        color = MaterialTheme.colorScheme.outlineVariant
    )
}

@Composable
private fun SettingsVectorIcon(@DrawableRes drawable: Int) {
    Icon(
        painter = painterResource(drawable),
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant
    )
}
