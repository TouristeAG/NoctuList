package com.eventmanager.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Business
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eventmanager.app.data.remote.BackendType
import com.eventmanager.app.data.remote.FirebaseConfiguredOrg
import com.eventmanager.app.data.remote.FirebaseOrgAbbreviation
import com.eventmanager.app.data.remote.FIREBASE_ORG_ALL_SENTINEL
import com.eventmanager.app.data.remote.isFirebaseOrgAllSentinel
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.firebase_org_all
import com.eventmanager.app.resources.firebase_org_switcher_cd
import com.eventmanager.app.resources.firebase_org_switching
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import org.jetbrains.compose.resources.stringResource

enum class FirebaseOrgSwitcherPlacement {
    TopBarBeforeSync,
    TopBarTitleEnd,
    PosHeader,
    DashboardClockRow,
    AdminSideNavVertical,
    AdminSideNavHorizontal,
    PosVenueStyle,
    ScannerDarkTopEnd,
    WelcomeTopEnd,
    BilleterieContent,
}

/** True when the org switcher actually draws (Firebase + at least two configured orgs). */
fun isFirebaseOrgSwitcherVisible(viewModel: EventManagerViewModel): Boolean =
    viewModel.getActiveBackendType() == BackendType.FIREBASE &&
        viewModel.getFirebaseConfiguredOrgs().size > 1

private fun FirebaseOrgSwitcherPlacement.allowsAllOrgsOptionByDefault(): Boolean = when (this) {
    FirebaseOrgSwitcherPlacement.WelcomeTopEnd,
    FirebaseOrgSwitcherPlacement.TopBarBeforeSync,
    FirebaseOrgSwitcherPlacement.TopBarTitleEnd,
    FirebaseOrgSwitcherPlacement.PosVenueStyle,
    FirebaseOrgSwitcherPlacement.ScannerDarkTopEnd,
    FirebaseOrgSwitcherPlacement.BilleterieContent,
    -> true
    FirebaseOrgSwitcherPlacement.DashboardClockRow,
    FirebaseOrgSwitcherPlacement.AdminSideNavVertical,
    FirebaseOrgSwitcherPlacement.AdminSideNavHorizontal,
    FirebaseOrgSwitcherPlacement.PosHeader,
    -> false
}

@Composable
fun FirebaseOrgSwitcher(
    viewModel: EventManagerViewModel,
    placement: FirebaseOrgSwitcherPlacement,
    modifier: Modifier = Modifier,
    allowAllOrgsOption: Boolean? = null,
    onAdminRequireReauth: (() -> Unit)? = null,
) {
    if (viewModel.getActiveBackendType() != BackendType.FIREBASE) return
    val configuredOrgs = viewModel.getFirebaseConfiguredOrgs()
    if (configuredOrgs.size <= 1) return

    val activeOrgId = viewModel.getActiveFirebaseOrgId()
    val switching by viewModel.firebaseOrgSwitching.collectAsState()
    val isAdminPlacement = placement == FirebaseOrgSwitcherPlacement.AdminSideNavVertical ||
        placement == FirebaseOrgSwitcherPlacement.AdminSideNavHorizontal ||
        (placement == FirebaseOrgSwitcherPlacement.DashboardClockRow &&
            allowAllOrgsOption != true)
    val showAllOption = allowAllOrgsOption ?: placement.allowsAllOrgsOptionByDefault()
    val effectiveActiveOrgId = if (isAdminPlacement && isFirebaseOrgAllSentinel(activeOrgId)) {
        viewModel.getFirebaseLastSingleOrgId().trim().takeIf { lastOrg ->
            configuredOrgs.any { it.orgId == lastOrg }
        } ?: configuredOrgs.first().orgId
    } else {
        activeOrgId
    }
    val onSelectSingleOrg = if (isAdminPlacement && onAdminRequireReauth != null) {
        { orgId: String -> viewModel.switchFirebaseOrgFromAdmin(orgId, onAdminRequireReauth) }
    } else {
        viewModel::switchFirebaseOrgAsync
    }
    val onEnterAllOrgs = viewModel::enterAllOrgsModeAsync

    when (placement) {
        FirebaseOrgSwitcherPlacement.AdminSideNavVertical -> AdminSideNavOrgSwitcher(
            configuredOrgs = configuredOrgs,
            activeOrgId = effectiveActiveOrgId,
            expanded = false,
            switching = switching,
            onOrgSelected = onSelectSingleOrg,
            modifier = modifier,
        )
        FirebaseOrgSwitcherPlacement.AdminSideNavHorizontal -> AdminSideNavOrgSwitcher(
            configuredOrgs = configuredOrgs,
            activeOrgId = effectiveActiveOrgId,
            expanded = true,
            switching = switching,
            onOrgSelected = onSelectSingleOrg,
            modifier = modifier,
        )
        FirebaseOrgSwitcherPlacement.PosVenueStyle -> PosStyleOrgSwitcher(
            configuredOrgs = configuredOrgs,
            activeOrgId = activeOrgId,
            switching = switching,
            showAllOption = showAllOption,
            onOrgSelected = onSelectSingleOrg,
            onEnterAllOrgs = onEnterAllOrgs,
            modifier = modifier,
        )
        FirebaseOrgSwitcherPlacement.WelcomeTopEnd -> CompactOrgSwitcherChip(
            configuredOrgs = configuredOrgs,
            activeOrgId = activeOrgId,
            switching = switching,
            showAllOption = showAllOption,
            onOrgSelected = onSelectSingleOrg,
            onEnterAllOrgs = onEnterAllOrgs,
            compact = true,
            modifier = modifier,
        )
        FirebaseOrgSwitcherPlacement.ScannerDarkTopEnd -> ScannerDarkOrgSwitcher(
            configuredOrgs = configuredOrgs,
            activeOrgId = activeOrgId,
            switching = switching,
            showAllOption = showAllOption,
            onOrgSelected = onSelectSingleOrg,
            onEnterAllOrgs = onEnterAllOrgs,
            modifier = modifier,
        )
        FirebaseOrgSwitcherPlacement.DashboardClockRow -> CompactOrgSwitcherChip(
            configuredOrgs = configuredOrgs,
            activeOrgId = if (showAllOption) activeOrgId else effectiveActiveOrgId,
            switching = switching,
            showAllOption = showAllOption,
            onOrgSelected = onSelectSingleOrg,
            onEnterAllOrgs = onEnterAllOrgs,
            compact = false,
            modifier = modifier,
        )
        FirebaseOrgSwitcherPlacement.BilleterieContent -> CompactOrgSwitcherChip(
            configuredOrgs = configuredOrgs,
            activeOrgId = activeOrgId,
            switching = switching,
            showAllOption = showAllOption,
            onOrgSelected = onSelectSingleOrg,
            onEnterAllOrgs = onEnterAllOrgs,
            compact = false,
            modifier = modifier.fillMaxWidth(),
        )
        else -> CompactOrgSwitcherChip(
            configuredOrgs = configuredOrgs,
            activeOrgId = activeOrgId,
            switching = switching,
            showAllOption = showAllOption,
            onOrgSelected = onSelectSingleOrg,
            onEnterAllOrgs = onEnterAllOrgs,
            compact = placement == FirebaseOrgSwitcherPlacement.TopBarBeforeSync ||
                placement == FirebaseOrgSwitcherPlacement.TopBarTitleEnd,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CompactOrgSwitcherChip(
    configuredOrgs: List<FirebaseConfiguredOrg>,
    activeOrgId: String,
    switching: Boolean,
    showAllOption: Boolean = false,
    onOrgSelected: (String) -> Unit,
    onEnterAllOrgs: () -> Unit = {},
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isAllActive = isFirebaseOrgAllSentinel(activeOrgId)
    val active = configuredOrgs.firstOrNull { it.orgId == activeOrgId } ?: configuredOrgs.first()
    val activeColor = if (isAllActive) {
        MaterialTheme.colorScheme.outline
    } else {
        Color(active.colorArgb)
    }
    val activeLabel = if (isAllActive) {
        stringResource(Res.string.firebase_org_all)
    } else {
        active.orgId
    }

    Box(modifier = modifier) {
        Surface(
            onClick = { if (!switching) menuExpanded = true },
            shape = RoundedCornerShape(20.dp),
            color = activeColor.copy(alpha = 0.18f),
            tonalElevation = 0.dp,
            modifier = Modifier.widthIn(max = if (compact) 180.dp else 280.dp),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(activeColor),
                )
                if (switching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                    Text(
                        stringResource(Res.string.firebase_org_switching),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                } else {
                    Text(
                        activeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Icon(
                        Icons.Default.ArrowDropDown,
                        contentDescription = stringResource(Res.string.firebase_org_switcher_cd),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        OrgDropdownMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            configuredOrgs = configuredOrgs,
            activeOrgId = activeOrgId,
            showAllOption = showAllOption,
            onOrgSelected = {
                menuExpanded = false
                onOrgSelected(it)
            },
            onEnterAllOrgs = {
                menuExpanded = false
                onEnterAllOrgs()
            },
        )
    }
}

@Composable
private fun ScannerDarkOrgSwitcher(
    configuredOrgs: List<FirebaseConfiguredOrg>,
    activeOrgId: String,
    switching: Boolean,
    showAllOption: Boolean = false,
    onOrgSelected: (String) -> Unit,
    onEnterAllOrgs: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val isAllActive = isFirebaseOrgAllSentinel(activeOrgId)
    val active = configuredOrgs.firstOrNull { it.orgId == activeOrgId } ?: configuredOrgs.first()
    val activeColor = if (isAllActive) Color.White.copy(alpha = 0.7f) else Color(active.colorArgb)
    val singleAbbrev = remember(active.orgId) { FirebaseOrgAbbreviation.abbreviate(active.orgId) }
    val allAbbrev = stringResource(Res.string.firebase_org_all).take(3)
    val abbrev = if (isAllActive) allAbbrev else singleAbbrev

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(ScannerCardDark)
                .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(10.dp))
                .clickable(enabled = !switching) { menuExpanded = true }
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (switching) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = Color.White,
                )
            } else {
                if (isAllActive) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        configuredOrgs.take(3).forEach { org ->
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(org.colorArgb)),
                            )
                        }
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(activeColor),
                    )
                }
                Text(
                    abbrev,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = stringResource(Res.string.firebase_org_switcher_cd),
                    modifier = Modifier.size(18.dp),
                    tint = Color.White.copy(alpha = 0.85f),
                )
            }
        }
        OrgDropdownMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            configuredOrgs = configuredOrgs,
            activeOrgId = activeOrgId,
            showAllOption = showAllOption,
            onOrgSelected = {
                menuExpanded = false
                onOrgSelected(it)
            },
            onEnterAllOrgs = {
                menuExpanded = false
                onEnterAllOrgs()
            },
        )
    }
}

@Composable
private fun OrgDropdownMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    configuredOrgs: List<FirebaseConfiguredOrg>,
    activeOrgId: String,
    showAllOption: Boolean = false,
    onOrgSelected: (String) -> Unit,
    onEnterAllOrgs: () -> Unit = {},
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        if (showAllOption && configuredOrgs.size >= 2) {
            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            configuredOrgs.take(4).forEach { org ->
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(Color(org.colorArgb)),
                                )
                            }
                        }
                        Text(
                            stringResource(Res.string.firebase_org_all),
                            fontWeight = if (isFirebaseOrgAllSentinel(activeOrgId)) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                },
                onClick = onEnterAllOrgs,
            )
            HorizontalDivider()
        }
        configuredOrgs.forEach { org ->
            val color = Color(org.colorArgb)
            DropdownMenuItem(
                text = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .clip(CircleShape)
                                .background(color),
                        )
                        Text(
                            org.orgId,
                            fontWeight = if (org.orgId == activeOrgId) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                },
                onClick = { onOrgSelected(org.orgId) },
            )
        }
    }
}

@Composable
private fun PosStyleOrgSwitcher(
    configuredOrgs: List<FirebaseConfiguredOrg>,
    activeOrgId: String,
    switching: Boolean,
    showAllOption: Boolean = false,
    onOrgSelected: (String) -> Unit,
    onEnterAllOrgs: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val isHovered by interactionSource.collectIsHoveredAsState()
    val isAllActive = isFirebaseOrgAllSentinel(activeOrgId)
    val active = configuredOrgs.firstOrNull { it.orgId == activeOrgId } ?: configuredOrgs.first()
    val orgAccent = if (isAllActive) MaterialTheme.colorScheme.primary else Color(active.colorArgb)
    val singleAbbrev = remember(active.orgId) { FirebaseOrgAbbreviation.abbreviate(active.orgId) }
    val allAbbrev = stringResource(Res.string.firebase_org_all).take(3)
    val abbrev = if (isAllActive) allAbbrev else singleAbbrev
    val labelColor = when {
        menuExpanded || isHovered -> MaterialTheme.colorScheme.onSecondaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val buttonColor = when {
        menuExpanded || isHovered -> MaterialTheme.colorScheme.secondaryContainer
        isAllActive -> MaterialTheme.colorScheme.secondaryContainer
        else -> orgAccent.copy(alpha = 0.88f)
    }
    val iconTint = when {
        menuExpanded || isHovered -> MaterialTheme.colorScheme.onSecondaryContainer
        isAllActive -> MaterialTheme.colorScheme.onSecondaryContainer
        orgAccent.luminance() > 0.55f -> MaterialTheme.colorScheme.onSurface
        else -> Color.White
    }
    val labelStyle = MaterialTheme.typography.labelSmall.copy(
        fontSize = 13.sp,
        lineHeight = 14.sp,
        fontWeight = FontWeight.Bold,
    )

    Box(modifier = modifier) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .hoverable(interactionSource)
                .clickable(enabled = !switching) { menuExpanded = true },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            abbrev.forEach { char ->
                Text(
                    text = char.toString(),
                    style = labelStyle,
                    color = labelColor,
                    fontWeight = if (isAllActive) FontWeight.Medium else FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(4.dp))
            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                if (switching) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                } else {
                    Surface(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(40.dp),
                        shape = CircleShape,
                        color = buttonColor,
                        interactionSource = interactionSource,
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Default.Business,
                                contentDescription = stringResource(Res.string.firebase_org_switcher_cd),
                                tint = iconTint,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    }
                }
            }
        }
        OrgDropdownMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            configuredOrgs = configuredOrgs,
            activeOrgId = activeOrgId,
            showAllOption = showAllOption,
            onOrgSelected = {
                menuExpanded = false
                onOrgSelected(it)
            },
            onEnterAllOrgs = {
                menuExpanded = false
                onEnterAllOrgs()
            },
        )
    }
}

@Composable
private fun AdminSideNavOrgSwitcher(
    configuredOrgs: List<FirebaseConfiguredOrg>,
    activeOrgId: String,
    expanded: Boolean,
    switching: Boolean,
    onOrgSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    val active = configuredOrgs.firstOrNull { it.orgId == activeOrgId } ?: configuredOrgs.first()
    val activeColor = Color(active.colorArgb)

    Box(
        modifier = modifier.padding(horizontal = if (expanded) 8.dp else 4.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = expanded,
            transitionSpec = {
                (fadeIn(animationSpec = tween(220)) togetherWith fadeOut(animationSpec = tween(180)))
                    .using(SizeTransform(clip = false))
            },
            label = "adminOrgSwitcher",
        ) { isExpanded ->
            if (isExpanded) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(activeColor.copy(alpha = 0.16f))
                        .clickable(enabled = !switching) { menuExpanded = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OrgSwitcherContentHorizontal(active, switching)
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .clickable(enabled = !switching) { menuExpanded = true },
                    contentAlignment = Alignment.Center,
                ) {
                    if (switching) {
                        CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Box(
                            modifier = Modifier
                                .size(26.dp)
                                .clip(CircleShape)
                                .background(activeColor)
                                .border(
                                    width = 1.5.dp,
                                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f),
                                    shape = CircleShape,
                                ),
                        )
                    }
                }
            }
        }

        OrgDropdownMenu(
            expanded = menuExpanded,
            onDismiss = { menuExpanded = false },
            configuredOrgs = configuredOrgs,
            activeOrgId = activeOrgId,
            onOrgSelected = {
                menuExpanded = false
                onOrgSelected(it)
            },
        )
    }
}

@Composable
private fun RowScope.OrgSwitcherContentHorizontal(
    active: FirebaseConfiguredOrg,
    switching: Boolean,
) {
    val activeColor = Color(active.colorArgb)
    if (switching) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
        return
    }
    Box(
        modifier = Modifier
            .size(12.dp)
            .clip(CircleShape)
            .background(activeColor),
    )
    Text(
        active.orgId,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
    Icon(Icons.Default.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp))
}

/** Shared dark card tone for scanner org switcher (matches [BilleterieScannerUi]). */
private val ScannerCardDark = Color(0xFF1E1E1E)
