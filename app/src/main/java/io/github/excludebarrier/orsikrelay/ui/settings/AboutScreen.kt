package io.github.excludebarrier.orsikrelay.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.ui.components.OrsikLargeHeader
import io.github.excludebarrier.orsikrelay.domain.crash.CrashReport
import io.github.excludebarrier.orsikrelay.platform.crash.CrashReporter
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.rememberSecureUriHandler

/** The only place that carries the public project URL. */
internal const val PROJECT_SOURCE_URL = "https://github.com/exclude-barrier/OrsikRelay"

/**
 * About screen with the app version, license, source-code link and a
 * "report a bug" row that opens a prefilled GitHub issue (app version,
 * Android version and device model only).
 *
 * Same large header (design v2) and the same cards/rows as the settings
 * screen; the version comes from `BuildConfig.VERSION_NAME`. The source row
 * opens the public project page through the same fail-closed HTTPS-only
 * handler as markdown links.
 *
 * [onAllowedLinkOpen] is a test seam that receives an allowed (https) URL
 * instead of launching a real intent.
 */
@Composable
fun AboutScreen(
    versionName: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onAllowedLinkOpen: ((String) -> Unit)? = null
) {
    val uriHandler = rememberSecureUriHandler(
        context = LocalContext.current,
        onAllowedLinkOpen = onAllowedLinkOpen
    )
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("about_screen")
    ) {
        OrsikLargeHeader(title = stringResource(R.string.settings_about_section), onBack = onBack)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            SettingsCard {
                SettingsRow(
                    trailing = {
                        Text(
                            text = versionName,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                ) {
                    Text(
                        text = stringResource(R.string.settings_version_label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                // No leading icon in these rows, so the divider starts at the text.
                HorizontalDivider(
                    modifier = Modifier.padding(start = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant
                )
                SettingsRow(
                    trailing = {
                        Text(
                            text = stringResource(R.string.settings_license_value),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                ) {
                    Text(
                        text = stringResource(R.string.settings_license_label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            SettingsCard {
                SettingsRow(
                    modifier = Modifier
                        .clickable(
                            role = Role.Button,
                            onClick = { uriHandler.openUri(PROJECT_SOURCE_URL) }
                        )
                        .testTag("about_source_code"),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Filled.Build,
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
                        text = stringResource(R.string.settings_source_label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = PROJECT_SOURCE_URL.removePrefix("https://"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                SettingsDivider()
                SettingsRow(
                    modifier = Modifier
                        .clickable(
                            role = Role.Button,
                            onClick = {
                                uriHandler.openUri(
                                    CrashReport.issueUrl(
                                        projectUrl = PROJECT_SOURCE_URL,
                                        title = "",
                                        report = CrashReporter.environment()
                                    )
                                )
                            }
                        )
                        .testTag("about_report_bug"),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Warning,
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
                        text = stringResource(R.string.settings_report_bug_label),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
    }
}
