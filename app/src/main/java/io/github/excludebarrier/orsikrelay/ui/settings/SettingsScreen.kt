package io.github.excludebarrier.orsikrelay.ui.settings

import io.github.excludebarrier.orsikrelay.BuildConfig
import io.github.excludebarrier.orsikrelay.domain.crash.CrashReport
import io.github.excludebarrier.orsikrelay.platform.crash.CrashReporter
import io.github.excludebarrier.orsikrelay.ui.chat.markdown.rememberSecureUriHandler
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.IconButton
import androidx.compose.ui.text.style.TextOverflow
import android.content.Context
import android.os.Build
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import io.github.excludebarrier.orsikrelay.ui.components.ConnectedButtonGroup
import io.github.excludebarrier.orsikrelay.ui.components.GroupGap
import io.github.excludebarrier.orsikrelay.ui.components.GroupPosition
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import io.github.excludebarrier.orsikrelay.ui.components.shape
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikType
import io.github.excludebarrier.orsikrelay.domain.language.SupportedLanguages
import io.github.excludebarrier.orsikrelay.domain.settings.ChatTextSize
import io.github.excludebarrier.orsikrelay.security.applock.AppAuthenticator
import io.github.excludebarrier.orsikrelay.security.applock.AppLockController
import io.github.excludebarrier.orsikrelay.ui.theme.ThemeMode
import io.github.excludebarrier.orsikrelay.ui.theme.ThemeModeController

/**
 * Settings screen of design v2: a large title under the back arrow and
 * grouped cards under primary labels - Allgemein (language), Darstellung
 * (design, Material You), Chat (text size), Benachrichtigungen and
 * Sicherheit (app lock, screen protection). Rows of one group are separate
 * blocks with 24 dp outer and 6 dp inner corners. Design and text size are
 * connected button groups.
 *
 * Language options come exclusively from [SupportedLanguages]; "System" is
 * the leading option and means the device language. Every controller is
 * wired exactly as before; this screen only changes the look.
 *
 * [sdkInt] is injectable so the API-31 rule for the Material You switch can
 * be exercised without an older device.
 */
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
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
    sdkInt: Int = Build.VERSION.SDK_INT,
    openNotificationSettings: (Context) -> Unit = { openAppNotificationSettings(it) },
    versionName: String = BuildConfig.VERSION_NAME,
    /** Test seam: receives an allowed (https) URL instead of an intent. */
    onAllowedLinkOpen: ((String) -> Unit)? = null
) {
    val uriHandler = rememberSecureUriHandler(context = LocalContext.current, onAllowedLinkOpen = onAllowedLinkOpen)
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("settings_screen")
    ) {
        SettingsHeader(title = stringResource(R.string.settings_title), onBack = onBack)
        Column(
            verticalArrangement = Arrangement.spacedBy(GroupGap),
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
        ) {
            GroupLabel(stringResource(R.string.settings_group_general))
            LanguageRow(languageController = languageController)

            GroupLabel(stringResource(R.string.settings_appearance_section))
            val materialYouAvailable = isMaterialYouAvailable(sdkInt)
            SettingsBlock(
                position = if (materialYouAvailable) GroupPosition.First else GroupPosition.Single,
                icon = OrsikIcons.Palette,
                title = stringResource(R.string.settings_theme_label)
            ) {
                ConnectedButtonGroup(
                    options = ThemeMode.entries,
                    selected = themeController.mode,
                    label = { themeModeLabel(it) },
                    onSelect = { themeController.select(it) },
                    testTag = { "settings_theme_${it.name}" },
                    minHeight = SEGMENT_HEIGHT
                )
            }
            if (materialYouAvailable) {
                val materialYou = materialYouController.enabled
                SettingsBlock(
                    position = GroupPosition.Last,
                    icon = OrsikIcons.Wallpaper,
                    title = stringResource(R.string.settings_material_you_label),
                    description = stringResource(R.string.settings_material_you_description),
                    modifier = Modifier
                        .testTag("settings_material_you")
                        .toggleable(
                            value = materialYou,
                            role = Role.Switch,
                            onValueChange = { materialYouController.setEnabled(it) }
                        ),
                    trailing = { Switch(checked = materialYou, onCheckedChange = null) }
                )
            }

            GroupLabel(stringResource(R.string.settings_chat_section))
            SettingsBlock(
                position = GroupPosition.Single,
                icon = OrsikIcons.TextSize,
                title = stringResource(R.string.settings_chat_text_size)
            ) {
                ConnectedButtonGroup(
                    options = ChatTextSize.entries,
                    selected = chatTextSizeController.size,
                    label = { chatTextSizeLabel(it) },
                    onSelect = { chatTextSizeController.select(it) },
                    testTag = { "settings_chat_text_${it.name.lowercase()}" },
                    minHeight = SEGMENT_HEIGHT
                )
            }

            val context = LocalContext.current
            GroupLabel(stringResource(R.string.settings_notifications_label))
            SettingsBlock(
                position = GroupPosition.Single,
                icon = Icons.Outlined.Notifications,
                title = stringResource(R.string.settings_notifications_row_label),
                description = stringResource(R.string.settings_notifications_description),
                modifier = Modifier
                    .testTag("settings_notifications")
                    .clickable(
                        role = Role.Button,
                        onClick = { openNotificationSettings(context) }
                    ),
                trailing = {
                    Icon(
                        imageVector = OrsikIcons.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }
            )

            GroupLabel(stringResource(R.string.settings_privacy_section))
            val appLockEnabled = appLockController.enabled
            val canUseAppLock = appAuthenticator?.canAuthenticate() == true
            var appLockAuthPending by remember { mutableStateOf(false) }
            SettingsBlock(
                position = GroupPosition.First,
                icon = OrsikIcons.Lock,
                title = stringResource(R.string.settings_app_lock_label),
                description = stringResource(R.string.settings_app_lock_description),
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
                trailing = {
                    Switch(
                        checked = appLockEnabled,
                        onCheckedChange = null,
                        enabled = canUseAppLock && !appLockAuthPending
                    )
                }
            ) {
                if (!canUseAppLock) {
                    Text(
                        text = stringResource(R.string.settings_app_lock_unavailable),
                        style = OrsikType.meta,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 38.dp)
                            .testTag("settings_app_lock_unavailable")
                    )
                }
            }
            val screenProtection = screenProtectionController.enabled || appLockEnabled
            SettingsBlock(
                position = GroupPosition.Last,
                icon = OrsikIcons.Eye,
                title = stringResource(R.string.settings_screen_protection_label),
                description = stringResource(
                    if (appLockEnabled) R.string.settings_screen_protection_app_lock_description
                    else R.string.settings_screen_protection_description
                ),
                modifier = Modifier
                    .testTag("settings_screen_protection")
                    .toggleable(
                        value = screenProtection,
                        enabled = !appLockEnabled,
                        role = Role.Switch,
                        onValueChange = { screenProtectionController.setEnabled(it) }
                    ),
                trailing = { Switch(checked = screenProtection, onCheckedChange = null, enabled = !appLockEnabled) }
            )

            GroupLabel(stringResource(R.string.settings_info_section))
            SettingsBlock(
                position = GroupPosition.First,
                icon = OrsikIcons.Relay,
                title = stringResource(R.string.settings_about_section),
                modifier = Modifier
                    .testTag("settings_about")
                    .clickable(role = Role.Button, onClick = onOpenAbout),
                trailing = { TrailingValue(versionName, chevron = true) }
            )
            SettingsBlock(
                position = GroupPosition.Middle,
                icon = OrsikIcons.FileText,
                title = stringResource(R.string.settings_license_label),
                modifier = Modifier.testTag("settings_license"),
                trailing = { TrailingValue(stringResource(R.string.settings_license_value)) }
            )
            SettingsBlock(
                position = GroupPosition.Middle,
                icon = OrsikIcons.Code,
                title = stringResource(R.string.settings_source_label),
                description = PROJECT_SOURCE_URL.removePrefix("https://"),
                modifier = Modifier
                    .testTag("settings_source_code")
                    .clickable(role = Role.Button, onClick = { uriHandler.openUri(PROJECT_SOURCE_URL) }),
                trailing = { TrailingValue(null, chevron = true) }
            )
            SettingsBlock(
                position = GroupPosition.Last,
                icon = Icons.Outlined.Warning,
                title = stringResource(R.string.settings_report_bug_label),
                modifier = Modifier
                    .testTag("settings_report_bug")
                    .clickable(
                        role = Role.Button,
                        onClick = {
                            uriHandler.openUri(
                                CrashReport.issueUrl(projectUrl = PROJECT_SOURCE_URL, title = "", report = CrashReporter.environment())
                            )
                        }
                    ),
                trailing = { TrailingValue(null, chevron = true) }
            )
        }
    }
}

/**
 * Back arrow and the 28 sp page title. Local until the shared header carries
 * the page size, so this screen does not depend on that change.
 */
@Composable
private fun SettingsHeader(title: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .padding(start = 4.dp, top = 6.dp)
                .testTag("settings_back")
        ) {
            Icon(OrsikIcons.ArrowBack, contentDescription = stringResource(R.string.settings_back))
        }
        Text(
            text = title,
            fontSize = 28.sp,
            lineHeight = 34.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(start = 20.dp, end = 16.dp, bottom = 4.dp)
                .semantics { heading() }
        )
    }
}

/** A value and/or chevron at the end of a settings row. */
@Composable
private fun TrailingValue(value: String?, chevron: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (value != null) {
            Text(text = value, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (chevron) {
            Icon(
                imageVector = OrsikIcons.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(start = 2.dp)
                    .size(18.dp)
            )
        }
    }
}

/** Height of the segmented choices (design, text size, language). */
private val SEGMENT_HEIGHT = 38.dp

/** A 13 sp primary group label above a block of settings. */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = OrsikType.groupLabel,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = 8.dp, top = 18.dp, bottom = 8.dp)
            .semantics { heading() }
    )
}

/**
 * One settings block of a group: icon, title, optional description and a
 * trailing control in the first line; [content] (a button group, a hint)
 * below. The toggle/click semantics in [modifier] cover that first line
 * only, so [content] keeps its own semantics (a hint stays readable on its
 * own, a button group keeps its own buttons).
 */
@Composable
private fun SettingsBlock(
    position: GroupPosition,
    icon: ImageVector,
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null
) {
    val shape = position.shape(outer = 24.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .padding(start = 16.dp, end = 14.dp, top = 9.dp, bottom = if (content != null) 4.dp else 9.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 14.dp, end = if (trailing != null) 12.dp else 0.dp)
            ) {
                Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                if (description != null) {
                    Text(text = description, style = OrsikType.meta, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            trailing?.invoke()
        }
        if (content != null) {
            Column(modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp), content = content)
        }
    }
}

/**
 * The language choice as segments: "System" (the device language) first,
 * then every entry of [SupportedLanguages] by name.
 */
@Composable
private fun LanguageRow(languageController: LanguageController) {
    val systemLabel = stringResource(R.string.settings_language_system)
    val options: List<String?> = listOf<String?>(null) + SupportedLanguages.all.sortedBy { it.displayName }.map { it.tag }
    Box(modifier = Modifier.testTag("settings_language_box")) {
        SettingsBlock(
            position = GroupPosition.Single,
            icon = OrsikIcons.Globe,
            title = stringResource(R.string.settings_language_field),
            modifier = Modifier.testTag("settings_language_field")
        ) {
            ConnectedButtonGroup(
                options = options,
                selected = languageController.selectedTag?.takeIf { tag -> SupportedLanguages.all.any { it.tag == tag } },
                label = { tag -> tag?.let { t -> SupportedLanguages.all.first { it.tag == t }.displayName } ?: systemLabel },
                onSelect = { languageController.select(it) },
                testTag = { tag -> "settings_language_${tag ?: "system"}" },
                minHeight = SEGMENT_HEIGHT
            )
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
internal fun SettingsCard(content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
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

