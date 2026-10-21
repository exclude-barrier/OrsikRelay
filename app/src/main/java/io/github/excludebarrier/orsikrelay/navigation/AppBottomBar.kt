package io.github.excludebarrier.orsikrelay.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.excludebarrier.orsikrelay.R
import io.github.excludebarrier.orsikrelay.ui.profiles.DnsIcon

/** Test tag of one bottom-bar item: `nav_tab_<route>`. */
internal fun bottomTabTag(tab: AppScreen): String = "nav_tab_" + routeOf(tab)

/**
 * The app's 64 dp bottom navigation: Home, Agents (the session list),
 * Server and Activity. Activity carries a badge while a run of the active
 * server waits for an approval.
 */
@Composable
internal fun AppBottomBar(
    current: AppScreen,
    approvalPending: Boolean,
    onSelect: (AppScreen) -> Unit
) {
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    NavigationBar(modifier = Modifier.height(64.dp + bottomInset)) {
        BottomTabs.forEach { tab ->
            val label = stringResource(tab.labelRes())
            val showBadge = tab == AppScreen.Activity && approvalPending
            val description = if (showBadge) stringResource(R.string.nav_activity_approval) else label
            NavigationBarItem(
                selected = tab == current,
                onClick = { onSelect(tab) },
                icon = {
                    BadgedBox(badge = { if (showBadge) Badge() }) {
                        Icon(tab.icon(), contentDescription = null)
                    }
                },
                label = { Text(label) },
                // The theme's secondary is a dark container colour, so the
                // default selected label would be unreadable.
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.primary,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer
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
    AppScreen.Home -> Icons.Filled.Home
    AppScreen.Sessions -> Icons.Filled.Face
    AppScreen.Server -> DnsIcon
    else -> Icons.Filled.Notifications
}
