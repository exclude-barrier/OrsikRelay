package io.github.excludebarrier.orsikrelay.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.language.SupportedLanguages
import io.github.excludebarrier.orsikrelay.ui.theme.ThemeMode
import io.github.excludebarrier.orsikrelay.ui.theme.ThemeModeController

/**
 * Settings screen with language, appearance and app information.
 *
 * Language options come exclusively from [SupportedLanguages]; "System" is
 * the leading option and means the device language. Appearance is the same
 * three-mode selection that used to live in the session-list header.
 */
@Composable
fun SettingsScreen(
    languageController: LanguageController,
    themeController: ThemeModeController,
    versionName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
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
        RadioOptionRow(
            label = stringResource(R.string.settings_language_system),
            selected = languageController.selectedTag == null,
            testTag = "settings_language_system",
            onClick = { languageController.select(null) }
        )
        SupportedLanguages.all.forEach { language ->
            RadioOptionRow(
                label = language.displayName,
                selected = languageController.selectedTag == language.tag,
                testTag = "settings_language_${language.tag}",
                onClick = { languageController.select(language.tag) }
            )
        }

        SectionTitle(stringResource(R.string.settings_appearance_section))
        ThemeMode.entries.forEach { mode ->
            RadioOptionRow(
                label = themeModeLabel(mode),
                selected = themeController.mode == mode,
                testTag = "settings_theme_${mode.name}",
                onClick = { themeController.select(mode) }
            )
        }

        SectionTitle(stringResource(R.string.settings_about_section))
        HorizontalDivider()
        InfoRow(
            label = stringResource(R.string.settings_version_label),
            value = versionName
        )
        InfoRow(
            label = stringResource(R.string.settings_license_label),
            value = stringResource(R.string.settings_license_value)
        )
    }
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
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.weight(1f))
        Text(text = value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun themeModeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.System -> stringResource(R.string.theme_mode_system)
    ThemeMode.Light -> stringResource(R.string.theme_mode_light)
    ThemeMode.Dark -> stringResource(R.string.theme_mode_dark)
}
