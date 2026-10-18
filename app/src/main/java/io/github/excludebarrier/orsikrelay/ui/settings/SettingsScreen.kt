package io.github.excludebarrier.orsikrelay.ui.settings

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.language.SupportedLanguages
import io.github.excludebarrier.orsikrelay.domain.settings.ChatTextSize
import io.github.excludebarrier.orsikrelay.ui.theme.ThemeMode
import io.github.excludebarrier.orsikrelay.ui.theme.ThemeModeController

/**
 * Settings screen with language, appearance, chat text size, notifications
 * and privacy.
 *
 * Language options come exclusively from [SupportedLanguages]; "System" is
 * the leading option and means the device language. The language choice is a
 * single dropdown, appearance stays a three-mode radio selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    languageController: LanguageController,
    themeController: ThemeModeController,
    chatTextSizeController: ChatTextSizeController,
    screenProtectionController: ScreenProtectionController,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    openNotificationSettings: (Context) -> Unit = { openAppNotificationSettings(it) }
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen")
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text(text = stringResource(R.string.settings_back))
            }
            Spacer(modifier = Modifier.weight(1f))
        }
        Text(
            text = stringResource(R.string.settings_title),
            style = MaterialTheme.typography.headlineMedium
        )

        SectionTitle(stringResource(R.string.settings_language_section))
        LanguageDropdown(languageController = languageController)

        SectionTitle(stringResource(R.string.settings_appearance_section))
        ThemeMode.entries.forEach { mode ->
            RadioOptionRow(
                label = themeModeLabel(mode),
                selected = themeController.mode == mode,
                testTag = "settings_theme_${mode.name}",
                onClick = { themeController.select(mode) }
            )
        }

        SectionTitle(stringResource(R.string.settings_chat_section))
        Text(
            text = stringResource(R.string.settings_chat_text_size),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        ChatTextSizeSegments(controller = chatTextSizeController)

        val context = LocalContext.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
                .heightIn(min = 48.dp)
                .testTag("settings_notifications")
                .clickable(
                    role = Role.Button,
                    onClick = { openNotificationSettings(context) }
                )
                .padding(horizontal = 8.dp, vertical = 12.dp)
        ) {
            Column {
                Text(
                    text = stringResource(R.string.settings_notifications_label),
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
        val screenProtection = screenProtectionController.enabled
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("settings_screen_protection")
                .toggleable(
                    value = screenProtection,
                    role = Role.Switch,
                    onValueChange = { screenProtectionController.setEnabled(it) }
                )
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
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
            Switch(
                checked = screenProtection,
                onCheckedChange = null,
                modifier = Modifier.padding(start = 12.dp)
            )
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
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 24.dp, bottom = 8.dp)
    )
}

@Composable
private fun RadioOptionRow(
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .testTag(testTag)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = null)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 12.dp)
        )
    }
}

@Composable
private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.System -> stringResource(R.string.theme_mode_system)
    ThemeMode.Light -> stringResource(R.string.theme_mode_light)
    ThemeMode.Dark -> stringResource(R.string.theme_mode_dark)
}
