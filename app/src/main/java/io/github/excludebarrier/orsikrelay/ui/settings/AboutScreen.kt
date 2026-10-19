package io.github.excludebarrier.orsikrelay.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
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
 * Same [SettingsTopBar] header and layout as the settings screen; the version
 * comes from `BuildConfig.VERSION_NAME`. The source row opens the public
 * project page through the same fail-closed HTTPS-only handler as markdown
 * links.
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
    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .testTag("about_screen"),
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            SettingsTopBar(
                title = stringResource(R.string.settings_about_section),
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
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(top = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_version_label),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = versionName,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_license_label),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = stringResource(R.string.settings_license_value),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(
                        role = Role.Button,
                        onClick = { uriHandler.openUri(PROJECT_SOURCE_URL) }
                    )
                    .testTag("about_source_code"),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_source_label),
                    style = MaterialTheme.typography.bodyLarge
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = PROJECT_SOURCE_URL.removePrefix("https://"),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
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
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.settings_report_bug_label),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
    }
}
