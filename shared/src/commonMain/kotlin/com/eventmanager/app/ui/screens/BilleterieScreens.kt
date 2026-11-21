package com.eventmanager.app.ui.screens

import com.eventmanager.app.ui.components.KeepScreenAwakeToggle
import com.eventmanager.app.ui.components.KeepAwakeSpace
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import org.jetbrains.compose.resources.stringResource

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.repository.EventManagerRepository
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.ui.utils.GuestListDefaultZoneId
import com.eventmanager.app.ui.utils.isTablet
import com.eventmanager.app.ui.utils.rememberGuestListEffectiveToday
import com.eventmanager.app.ui.components.FirebaseOrgSwitcher
import com.eventmanager.app.ui.components.FirebaseOrgSwitcherPlacement
import com.eventmanager.app.ui.components.PeopleCounter
import com.eventmanager.app.ui.components.StatCardV2
import com.eventmanager.app.ui.components.SyncStatusPill
import com.eventmanager.app.ui.components.BackgroundAnimationStyle
import com.eventmanager.app.ui.components.DashboardClockCard
import com.eventmanager.app.ui.components.SendAnnouncementButton
import com.eventmanager.app.ui.components.billeterieBackgroundAwareContainerColor
import com.eventmanager.app.ui.components.billeterieBackgroundAwareTopAppBarColors
import com.eventmanager.app.ui.components.posBackgroundAwareContainerColor
import com.eventmanager.app.ui.components.posBackgroundAwareTopAppBarColors
import com.eventmanager.app.ui.components.rememberBilleterieBackgroundAnimationStyle
import com.eventmanager.app.ui.components.rememberPosBackgroundAnimationStyle
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BilleterieHomeScreen(
    guests: List<Guest>,
    repository: EventManagerRepository,
    viewModel: EventManagerViewModel,
    dashboardScrollState: ScrollState,
    onBack: () -> Unit,
    onOpenGuestList: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenPos: () -> Unit = {},
    onOpenSettings: () -> Unit
) {
    val platformContext = LocalPlatformContext.current
    val settingsManager = remember { settingsManagerFor(platformContext) }
    val isPhone = !isTablet()
    val isPeopleCounterVisible = settingsManager.isPeopleCounterVisible()
    val isClockVisible = settingsManager.isBilleterieClockVisible()
    val dateChangeOffsetHours = remember { settingsManager.getDateChangeOffsetHours() }
    val guestListZone = GuestListDefaultZoneId
    val guestListEffectiveToday = rememberGuestListEffectiveToday(
        zone = guestListZone,
        offsetHours = dateChangeOffsetHours
    )
    val announcementsSendEnabled by viewModel.announcementsBilleterieSendEnabled.collectAsState()

    LaunchedEffect(isPeopleCounterVisible) {
        if (isPeopleCounterVisible) {
            viewModel.refreshVenuesForPeopleCounterQuietly()
        }
    }

    val (permanentGuestCount, temporaryGuestCount) = remember(guests, guestListEffectiveToday) {
        var permanent = 0
        var temporary = 0
        guests.forEach { guest ->
            when {
                guest.isTemporaryGuest -> {
                    val ts = guest.temporaryEventDate ?: return@forEach
                    val eventDate = java.time.Instant.ofEpochMilli(ts)
                        .atZone(guestListZone)
                        .toLocalDate()
                    if (eventDate == guestListEffectiveToday) temporary++
                }
                guest.isVolunteerBenefit -> { }
                else -> permanent++
            }
        }
        permanent to temporary
    }

    val volunteersOnList = remember(guests) {
        guests.count { it.isVolunteerBenefit }
    }

    val totalWithoutInvites = permanentGuestCount + temporaryGuestCount + volunteersOnList

    val billeterieBackgroundStyle = rememberBilleterieBackgroundAnimationStyle(settingsManager)
    val billeterieBackgroundEnabled = BackgroundAnimationStyle.isEnabled(billeterieBackgroundStyle)

    Scaffold(
        containerColor = billeterieBackgroundAwareContainerColor(settingsManager),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.ticket_check_title), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.setup_back))
                    }
                },
                actions = {
                    if (!isPhone) {
                        FirebaseOrgSwitcher(
                            viewModel = viewModel,
                            placement = FirebaseOrgSwitcherPlacement.TopBarBeforeSync,
                        )
                    }
                    KeepScreenAwakeToggle(KeepAwakeSpace.Billeterie)
                    SyncStatusPill(viewModel = viewModel)
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(Res.string.settings_title))
                    }
                },
                colors = if (billeterieBackgroundEnabled) {
                    billeterieBackgroundAwareTopAppBarColors()
                } else {
                    TopAppBarDefaults.topAppBarColors()
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(dashboardScrollState)
                .padding(if (isPhone) 16.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(
                when {
                    isPeopleCounterVisible && isPhone -> 8.dp
                    isPeopleCounterVisible -> 10.dp
                    isPhone -> 12.dp
                    else -> 16.dp
                }
            )
        ) {
            if (isClockVisible) {
                DashboardClockCard(
                    settingsManager = settingsManager,
                    isPhone = isPhone,
                    trailingContent = if (isPhone) {
                        {
                            FirebaseOrgSwitcher(
                                viewModel = viewModel,
                                placement = FirebaseOrgSwitcherPlacement.DashboardClockRow,
                                allowAllOrgsOption = true,
                            )
                        }
                    } else {
                        null
                    },
                )
            } else if (isPhone) {
                FirebaseOrgSwitcher(
                    viewModel = viewModel,
                    placement = FirebaseOrgSwitcherPlacement.BilleterieContent,
                )
            }

            val compactStats = isPeopleCounterVisible
            val statSpacing = if (compactStats) {
                if (isPhone) 8.dp else 10.dp
            } else {
                if (isPhone) 12.dp else 16.dp
            }

            Column(verticalArrangement = Arrangement.spacedBy(statSpacing)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(statSpacing)
                ) {
                    StatCardV2(
                        title = stringResource(Res.string.billeterie_stat_permanent_guests),
                        value = permanentGuestCount.toString(),
                        icon = Icons.Default.Person,
                        modifier = Modifier.weight(1f),
                        isPhone = isPhone,
                        compact = compactStats,
                    )
                    StatCardV2(
                        title = stringResource(Res.string.billeterie_stat_temporary_guests_today),
                        value = temporaryGuestCount.toString(),
                        icon = Icons.Default.Event,
                        modifier = Modifier.weight(1f),
                        isPhone = isPhone,
                        compact = compactStats,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(statSpacing)
                ) {
                    StatCardV2(
                        title = stringResource(Res.string.billeterie_stat_volunteers_on_list),
                        value = volunteersOnList.toString(),
                        icon = Icons.Default.Group,
                        modifier = Modifier.weight(1f),
                        isPhone = isPhone,
                        compact = compactStats,
                    )
                    StatCardV2(
                        title = stringResource(Res.string.billeterie_stat_total_without_invites),
                        value = totalWithoutInvites.toString(),
                        icon = Icons.Default.Star,
                        modifier = Modifier.weight(1f),
                        isPhone = isPhone,
                        compact = compactStats,
                    )
                }
            }

            Spacer(modifier = Modifier.height(if (compactStats) 4.dp else 8.dp))

            Button(
                onClick = onOpenGuestList,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                Icon(Icons.Default.List, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.billeterie_button_guest_list), fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(
                onClick = onOpenScanner,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.billeterie_button_scanner), fontWeight = FontWeight.SemiBold)
            }

            OutlinedButton(
                onClick = onOpenPos,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                Icon(Icons.Default.PointOfSale, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.pos_welcome_button), fontWeight = FontWeight.SemiBold)
            }

            if (isPeopleCounterVisible) {
                PeopleCounter(isPhone = isPhone, viewModel = viewModel)
            }

            if (announcementsSendEnabled) {
                SendAnnouncementButton(
                    isPhone = isPhone,
                    onClick = { viewModel.openSendAnnouncementDialog() },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BilleterieSettingsScreen(
    viewModel: EventManagerViewModel,
    onBack: () -> Unit,
    onFactoryResetComplete: () -> Unit = {},
) {
    val platformContext = LocalPlatformContext.current
    val settingsManager = remember(platformContext) { settingsManagerFor(platformContext) }
    val billeterieBackgroundStyle = rememberBilleterieBackgroundAnimationStyle(settingsManager)
    val billeterieBackgroundEnabled = BackgroundAnimationStyle.isEnabled(billeterieBackgroundStyle)

    Scaffold(
        containerColor = billeterieBackgroundAwareContainerColor(settingsManager),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.setup_back))
                    }
                },
                colors = if (billeterieBackgroundEnabled) {
                    billeterieBackgroundAwareTopAppBarColors()
                } else {
                    TopAppBarDefaults.topAppBarColors()
                },
            )
        }
    ) { padding ->
        SettingsScreen(
            viewModel = viewModel,
            variant = SettingsScreenVariant.BilleterieBasic,
            onFactoryResetComplete = onFactoryResetComplete,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PosSettingsScreen(
    viewModel: EventManagerViewModel,
    onBack: () -> Unit,
    onFactoryResetComplete: () -> Unit = {},
) {
    val platformContext = LocalPlatformContext.current
    val settingsManager = remember(platformContext) { settingsManagerFor(platformContext) }
    val posBackgroundStyle = rememberPosBackgroundAnimationStyle(settingsManager)
    val posBackgroundEnabled = BackgroundAnimationStyle.isEnabled(posBackgroundStyle)

    Scaffold(
        containerColor = posBackgroundAwareContainerColor(settingsManager),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(Res.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.setup_back))
                    }
                },
                colors = if (posBackgroundEnabled) {
                    posBackgroundAwareTopAppBarColors()
                } else {
                    TopAppBarDefaults.topAppBarColors()
                },
            )
        }
    ) { padding ->
        SettingsScreen(
            viewModel = viewModel,
            variant = SettingsScreenVariant.PosBasic,
            onFactoryResetComplete = onFactoryResetComplete,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        )
    }
}
