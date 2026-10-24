package io.github.excludebarrier.orsikrelay.ui.files

import androidx.compose.ui.platform.LocalConfiguration
import android.content.ClipData
import android.content.ClipDescription
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PersistableBundle
import android.text.format.Formatter
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.domain.files.FileEntry
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikTheme
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/** Icon and container colours of a [FileType] (design v2 file list). */
internal data class FileTypeStyle(val icon: ImageVector, val container: Color, val content: Color)

@Composable
@ReadOnlyComposable
internal fun styleOf(type: FileType): FileTypeStyle {
    val colors = MaterialTheme.colorScheme
    return when (type) {
        FileType.Folder -> FileTypeStyle(OrsikIcons.Folder, colors.primaryContainer, colors.primary)
        FileType.Image -> FileTypeStyle(OrsikIcons.Image, OrsikTheme.statusColors.okContainer, OrsikTheme.statusColors.okText)
        FileType.Text -> FileTypeStyle(OrsikIcons.FileText, colors.surfaceContainerHighest, colors.onSurface)
        FileType.Binary -> FileTypeStyle(OrsikIcons.FileBinary, colors.surfaceContainer, colors.onSurfaceVariant)
        FileType.Other -> FileTypeStyle(OrsikIcons.Link, colors.surfaceContainer, colors.onSurfaceVariant)
    }
}

@Composable
internal fun FileTypeIcon(type: FileType, modifier: Modifier = Modifier, size: Int = 40) {
    val style = styleOf(type)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.3f).dp))
            .background(style.container)
    ) {
        Icon(style.icon, contentDescription = null, tint = style.content, modifier = Modifier.size((size / 2).dp))
    }
}

/** "1,8 kB · Heute, 13:07" (localised); folders without a size. */
@Composable
internal fun entryMeta(entry: FileEntry): String {
    val context = LocalContext.current
    val parts = listOfNotNull(
        entry.sizeBytes?.let { Formatter.formatShortFileSize(context, it) },
        entry.modifiedAtSeconds?.let { modifiedLabel(it) }
    )
    return parts.joinToString(" · ").ifEmpty {
        stringResource(if (fileType(entry) == FileType.Other) R.string.files_kind_other else R.string.files_kind_folder)
    }
}

@Composable
internal fun modifiedLabel(epochSeconds: Long): String {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    return formatModified(
        epochSeconds = epochSeconds,
        locale = LocalConfiguration.current.locales[0],
        zone = zone,
        today = LocalDate.now(zone),
        todayLabel = stringResource(R.string.files_today),
        yesterdayLabel = stringResource(R.string.files_yesterday)
    )
}

/** A dashed rounded outline (empty states and the image frame). */
internal fun Modifier.dashedOutline(color: Color, radius: Float = 48f): Modifier = drawBehind {
    drawRoundRect(
        color = color,
        cornerRadius = CornerRadius(radius, radius),
        style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
    )
}

/** One row of the file action sheet; a disabled row carries a "Bald" badge. */
internal data class FileAction(
    val icon: ImageVector,
    val label: String,
    val detail: String? = null,
    val testTag: String,
    val onClick: (() -> Unit)?
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FileActionSheet(
    type: FileType,
    title: String,
    meta: String?,
    actions: List<FileAction>,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        modifier = Modifier.testTag(FilesTags.SHEET)
    ) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            ) {
                FileTypeIcon(type, size = 52)
                Column {
                    Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    meta?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
            HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            actions.forEach { action -> FileActionRow(action) }
        }
    }
}

@Composable
private fun FileActionRow(action: FileAction) {
    val enabled = action.onClick != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = action.onClick!!) else Modifier)
            .padding(horizontal = 24.dp, vertical = 8.dp)
            .testTag(action.testTag)
    ) {
        Icon(
            action.icon, contentDescription = null,
            modifier = Modifier.size(24.dp).alpha(if (enabled) 1f else 0.5f)
        )
        Spacer(Modifier.width(24.dp))
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else 0.5f)) {
            Text(action.label, style = MaterialTheme.typography.titleMedium)
            action.detail?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!enabled) SoonBadge()
    }
}

@Composable
internal fun SoonBadge(text: String = stringResource(R.string.files_soon)) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(horizontal = 10.dp, vertical = 3.dp)
    )
}

/** A skeleton of two list rows while a folder loads. */
@Composable
internal fun FileListSkeleton(modifier: Modifier = Modifier) {
    val block = MaterialTheme.colorScheme.surfaceContainer
    Column(verticalArrangement = Arrangement.spacedBy(2.dp), modifier = modifier.testTag(FilesTags.SKELETON)) {
        repeat(3) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .padding(14.dp)
            ) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(block))
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.width(160.dp).height(12.dp).clip(RoundedCornerShape(6.dp)).background(block))
                    Box(Modifier.width(96.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(block))
                }
            }
        }
    }
}

/** A card with a 1 dp outline (text source, binary info rows). */
internal fun Modifier.outlinedCard(color: Color, border: Color): Modifier =
    clip(RoundedCornerShape(22.dp)).background(color).border(BorderStroke(1.dp, border), RoundedCornerShape(22.dp))

/** Marks a copied path as sensitive (Android 13+ hides it in the clipboard preview). */
internal fun sensitivePlainText(text: String): ClipData =
    ClipData.newPlainText(null, text).apply {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
        }
    }

/**
 * Hands [bytes] to another app through the system share sheet. The copy
 * lives only in this app's cache folder "shared" (replaced on every share)
 * and is offered read-only through the app's FileProvider.
 */
internal fun shareBytes(context: Context, name: String, bytes: ByteArray, mimeType: String): Boolean = try {
    val folder = File(context.cacheDir, "shared").apply {
        deleteRecursively()
        mkdirs()
    }
    val safeName = name.map { if (it == '/' || it.isISOControl()) '_' else it }.joinToString("").ifEmpty { "file" }
    val file = File(folder, safeName).apply { writeBytes(bytes) }
    val uri: Uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: Exception) {
    false
}

internal fun writeToUri(context: Context, uri: Uri, bytes: ByteArray): Boolean = try {
    context.contentResolver.openOutputStream(uri, "w")?.use { it.write(bytes) } != null
} catch (_: Exception) {
    false
}
