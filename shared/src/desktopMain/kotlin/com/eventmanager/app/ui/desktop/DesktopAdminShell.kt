package com.eventmanager.app.ui.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.components.FirebaseOrgSwitcher
import com.eventmanager.app.ui.components.FirebaseOrgSwitcherPlacement
import com.eventmanager.app.ui.components.SyncStatusPill
import com.eventmanager.app.ui.navigation.AdminTab
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopAdminShell(
    navLayout: AdminNavLayout,
    navRailExpanded: Boolean,
    onNavRailExpandedChange: (Boolean) -> Unit,
    selectedTab: Int,
    onTabSelected: (AdminTab) -> Unit,
    onBack: () -> Unit,
    onSync: () -> Unit,
    onTouchSession: () -> Unit,
    onClearOverlays: () -> Unit,
    viewModel: EventManagerViewModel? = null,
    onAdminRequireReauth: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable (PaddingValues) -> Unit,
) {
    val topBar: @Composable () -> Unit = {
        TopAppBar(
            title = { Text(stringResource(Res.string.noctulist_admin_title)) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = stringResource(Res.string.settings_logout))
                }
            },
            actions = {
                if (viewModel != null) {
                    SyncStatusPill(
                        viewModel = viewModel,
                        modifier = Modifier.padding(end = 12.dp),
                        onSync = {
                            onTouchSession()
                            onSync()
                        },
                    )
                }
            }
        )
    }

    val bottomBar: @Composable () -> Unit = {
        if (navLayout == AdminNavLayout.BOTTOM) {
            DesktopAdminBottomNav(
                selectedTab = selectedTab,
                onTabSelected = { tab ->
                    onTouchSession()
                    onClearOverlays()
                    onTabSelected(tab)
                }
            )
        }
    }

    @Composable
    fun SideRail(onStart: Boolean) {
        if (navLayout == AdminNavLayout.BOTTOM) return
        DesktopAdminSideNav(
            selectedTab = selectedTab,
            expanded = navRailExpanded,
            onExpandedChange = onNavRailExpandedChange,
            onTabSelected = { tab ->
                onTouchSession()
                onClearOverlays()
                onTabSelected(tab)
            },
            alignEnd = !onStart,
            viewModel = viewModel,
            onAdminRequireReauth = onAdminRequireReauth,
            modifier = Modifier.fillMaxHeight()
        )
    }

    when (navLayout) {
        AdminNavLayout.BOTTOM -> {
            Scaffold(
                modifier = modifier.fillMaxSize(),
                topBar = topBar,
                bottomBar = bottomBar
            ) { padding -> content(padding) }
        }
        AdminNavLayout.LEFT -> {
            Row(modifier.fillMaxSize()) {
                SideRail(onStart = true)
                Scaffold(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    topBar = topBar
                ) { padding -> content(padding) }
            }
        }
        AdminNavLayout.RIGHT -> {
            Row(modifier.fillMaxSize()) {
                Scaffold(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    topBar = topBar
                ) { padding -> content(padding) }
                SideRail(onStart = false)
            }
        }
    }
}

@Composable
private fun DesktopAdminBottomNav(
    selectedTab: Int,
    onTabSelected: (AdminTab) -> Unit,
) {
    NavigationBar {
        AdminTab.entries.forEach { tab ->
            NavigationBarItem(
                selected = selectedTab == tab.index,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        adminTabIcon(tab),
                        contentDescription = adminTabLabel(tab)
                    )
                },
                label = { Text(adminTabLabel(tab)) }
            )
        }
    }
}

@Composable
private fun DesktopAdminSideNav(
    selectedTab: Int,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    onTabSelected: (AdminTab) -> Unit,
    alignEnd: Boolean,
    viewModel: EventManagerViewModel? = null,
    onAdminRequireReauth: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val railWidth = if (expanded) 220.dp else 72.dp
    Surface(
        modifier = modifier.width(railWidth),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(vertical = 8.dp),
            horizontalAlignment = if (expanded) Alignment.Start else Alignment.CenterHorizontally,
        ) {
            IconButton(
                onClick = { onExpandedChange(!expanded) },
                modifier = Modifier.padding(horizontal = if (expanded) 8.dp else 0.dp)
            ) {
                Icon(
                    imageVector = when {
                        expanded && alignEnd -> Icons.Default.ChevronRight
                        expanded && !alignEnd -> Icons.Default.ChevronLeft
                        !expanded && alignEnd -> Icons.Default.ChevronLeft
                        else -> Icons.Default.ChevronRight
                    },
                    contentDescription = if (expanded) {
                        stringResource(Res.string.desktop_admin_nav_collapse)
                    } else {
                        stringResource(Res.string.desktop_admin_nav_expand)
                    }
                )
            }

            HorizontalDivider(
                modifier = Modifier.padding(vertical = 4.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            AdminTab.entries.forEach { tab ->
                DesktopAdminSideNavItem(
                    selected = selectedTab == tab.index,
                    expanded = expanded,
                    icon = adminTabIcon(tab),
                    label = adminTabLabel(tab),
                    onClick = { onTabSelected(tab) }
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            if (viewModel != null) {
                HorizontalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )
                FirebaseOrgSwitcher(
                    viewModel = viewModel,
                    placement = if (expanded) {
                        FirebaseOrgSwitcherPlacement.AdminSideNavHorizontal
                    } else {
                        FirebaseOrgSwitcherPlacement.AdminSideNavVertical
                    },
                    modifier = Modifier.fillMaxWidth(),
                    onAdminRequireReauth = onAdminRequireReauth,
                )
            }
        }
    }
}

@Composable
private fun DesktopAdminSideNavItem(
    selected: Boolean,
    expanded: Boolean,
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
) {
    val containerColor = if (selected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerLow
    }
    val contentColor = if (selected) {
        MaterialTheme.colorScheme.onSecondaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .padding(horizontal = if (expanded) 8.dp else 4.dp, vertical = 2.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .clickable(onClick = onClick)
            .padding(
                horizontal = if (expanded) 16.dp else 0.dp,
                vertical = 12.dp
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (expanded) Arrangement.Start else Arrangement.Center
    ) {
        Icon(icon, contentDescription = label, tint = contentColor, modifier = Modifier.size(24.dp))
        if (expanded) {
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                color = contentColor,
                maxLines = 1
            )
        }
    }
}

@Composable
private fun adminTabIcon(tab: AdminTab): ImageVector = when (tab) {
    AdminTab.Dashboard -> Icons.Default.Home
    AdminTab.Guests -> Icons.Default.Person
    AdminTab.Volunteers -> Icons.Default.Group
    AdminTab.Shifts -> Icons.Default.Event
    AdminTab.Benefits -> Icons.Default.Star
    AdminTab.Settings -> Icons.Default.Settings
}

@Composable
private fun adminTabLabel(tab: AdminTab): String = when (tab) {
    AdminTab.Dashboard -> stringResource(Res.string.nav_dashboard)
    AdminTab.Guests -> stringResource(Res.string.nav_guests)
    AdminTab.Volunteers -> stringResource(Res.string.nav_volunteers)
    AdminTab.Shifts -> stringResource(Res.string.nav_shifts)
    AdminTab.Benefits -> stringResource(Res.string.nav_benefits)
    AdminTab.Settings -> stringResource(Res.string.nav_settings)
}
