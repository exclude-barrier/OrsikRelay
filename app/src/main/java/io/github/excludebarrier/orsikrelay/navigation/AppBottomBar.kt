package io.github.excludebarrier.orsikrelay.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.ui.components.OrsikIcons
import io.github.excludebarrier.orsikrelay.ui.theme.OrsikTheme

/** Test tag of one bottom-bar item: `nav_tab_<route>`. */
internal fun bottomTabTag(tab: AppScreen): String = "nav_tab_" + routeOf(tab)

/** Test tag of the approval dot on the Activity tab. */
internal const val ACTIVITY_BADGE_TAG = "nav_activity_badge"

/**
 * The app's 64 dp bottom navigation: Home, Agents (the session list),
 * Server and Activity. Activity carries an amber dot while a run of the
 * active server waits for an approval. On Home the composer dock above
 * draws the top edge; every other tab gets a hairline.
 */
@Composable
internal fun AppBottomBar(
    current: AppScreen,
    approvalPending: Boolean,
    onSelect: (AppScreen) -> Unit
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val barColor = MaterialTheme.colorScheme.surfaceContainerLow
    val line = MaterialTheme.colorScheme.outlineVariant
    val topLine = current != AppScreen.Home
    NavigationBar(
        containerColor = barColor,
        tonalElevation = 0.dp,
        modifier = Modifier
            .height(64.dp + bottomInset)
            .drawBehind {
                if (topLine) drawLine(line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
            }
    ) {
        BottomTabs.forEach { tab ->
            val label = stringResource(tab.labelRes())
            val selected = tab == current
            val showBadge = tab == AppScreen.Activity && approvalPending
            val description = if (showBadge) stringResource(R.string.nav_activity_approval) else label
            NavigationBarItem(
                selected = selected,
                onClick = { onSelect(tab) },
                icon = {
                    BadgedBox(
                        badge = {
                            if (showBadge) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(barColor)
                                        .padding(2.dp)
                                        .clip(CircleShape)
                                        .background(OrsikTheme.statusColors.warn)
                                        .testTag(ACTIVITY_BADGE_TAG)
                                )
                            }
                        }
                    ) {
                        Icon(tab.icon(), contentDescription = null, modifier = Modifier.size(22.dp))
                    }
                },
                label = {
                    Text(label, fontSize = 12.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.primary,
                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier
                    .semantics { contentDescription = description }
                    .testTag(bottomTabTag(tab))
            )
        }
    }
}

private fun AppScreen.labelRes(): Int = when (this) {
    AppScreen.Home -> R.string.nav_home
    AppScreen.Sessions -> R.string.nav_agents
    AppScreen.Server -> R.string.nav_server
    else -> R.string.nav_activity
}

private fun AppScreen.icon(): ImageVector = when (this) {
    AppScreen.Home -> OrsikIcons.Home
    AppScreen.Sessions -> OrsikIcons.Agents
    AppScreen.Server -> OrsikIcons.Server
    else -> OrsikIcons.Activity
}
