package io.github.excludebarrier.orsikrelay.ui.crash

import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.DialogProperties
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.crash.CrashReport
import io.github.excludebarrier.orsikrelay.platform.crash.CrashReporter
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.rememberSecureUriHandler
import io.github.excludebarrier.orsikrelay.ui.settings.PROJECT_SOURCE_URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Offers the report of the last crash once after the next start: report it
 * as a prefilled GitHub issue, share it through the system share sheet, or
 * discard it. Each choice deletes the stored report. Back closes the dialog
 * for now and keeps the report for the next start.
 */
@Composable
fun CrashReportPrompt() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = rememberSecureUriHandler(context)
    var report by rememberSaveable { mutableStateOf<String?>(null) }
    var postponed by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (report == null && !postponed) {
            report = withContext(Dispatchers.IO) { CrashReporter.pending(context) }
        }
    }
    val current = report
    if (current == null || postponed) return
    fun done() {
        report = null
        scope.launch(Dispatchers.IO) { CrashReporter.clear(context) }
    }
    CrashReportDialog(
        onReport = {
            uriHandler.openUri(
                CrashReport.issueUrl(PROJECT_SOURCE_URL, CrashReport.crashTitle(current), current)
            )
            done()
        },
        onShare = {
            shareReport(context, current)
            done()
        },
        onDiscard = { done() },
        onPostpone = { postponed = true }
    )
}

@Composable
internal fun CrashReportDialog(
    onReport: () -> Unit,
    onShare: () -> Unit,
    onDiscard: () -> Unit,
    onPostpone: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onPostpone,
        properties = DialogProperties(dismissOnClickOutside = false),
        modifier = Modifier.testTag("crash_report_dialog"),
        title = { Text(stringResource(R.string.crash_dialog_title)) },
        text = { Text(stringResource(R.string.crash_dialog_text)) },
        confirmButton = {
            TextButton(onClick = onReport) { Text(stringResource(R.string.crash_dialog_report)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDiscard) { Text(stringResource(R.string.crash_dialog_discard)) }
                TextButton(onClick = onShare) { Text(stringResource(R.string.crash_dialog_share)) }
            }
        }
    )
}

private fun shareReport(context: Context, report: String) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("text/plain")
        .putExtra(Intent.EXTRA_SUBJECT, CrashReport.crashTitle(report))
        .putExtra(Intent.EXTRA_TEXT, report)
    val chooser = Intent.createChooser(send, context.getString(R.string.crash_share_title))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(chooser) }
}
