package com.eventmanager.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eventmanager.app.data.models.*
import com.eventmanager.app.data.repository.EventManagerRepository
import com.eventmanager.app.data.sync.GoogleSheetsService
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.data.update.UpdateCheckResult
import com.eventmanager.app.data.sync.DateFormatUtils
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.platform.PlatformBackHandler
import com.eventmanager.app.platform.createAppStorage
import com.eventmanager.app.platform.createDatabase
import com.eventmanager.app.platform.elapsedRealtimeMs
import com.eventmanager.app.platform.openDateSettings
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.components.*
import com.eventmanager.app.ui.components.BackgroundAnimationStyle
import com.eventmanager.app.ui.desktop.AdminNavLayout
import com.eventmanager.app.ui.platform.AppAppearanceState
import com.eventmanager.app.ui.desktop.DesktopAdminShell
import com.eventmanager.app.ui.desktop.DesktopDramaticSpaceEntrance
import com.eventmanager.app.ui.desktop.DesktopNavigationHooks
import com.eventmanager.app.ui.desktop.DesktopPopupWarmup
import com.eventmanager.app.ui.desktop.DesktopSpaceEntrance
import com.eventmanager.app.ui.desktop.LocalDesktopNavigation
import com.eventmanager.app.ui.navigation.AdminTab
import com.eventmanager.app.ui.navigation.BilleterieSection
import com.eventmanager.app.ui.screens.*
import com.eventmanager.app.ui.transitions.DeferredUntilSpaceEntranceSettled
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.stringResource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
actual fun AppRootContent(
    platformContext: com.eventmanager.app.platform.PlatformContext,
    onThemeModeChanged: (String) -> Unit
) {
    CompositionLocalProvider(LocalPlatformContext provides platformContext) {
        DesktopPopupWarmup()
        val settingsManager = remember(platformContext) { SettingsManager(createAppStorage(platformContext)) }
        remember(platformContext, settingsManager) {
            com.eventmanager.app.data.sync.installInstitutionLogoBridge(platformContext, settingsManager)
        }
        val skipStartupSync = remember { settingsManager.consumeSkipNextStartupSync() }
        val openWelcomeAfterSetup = remember { settingsManager.consumeOpenWelcomeAfterSetup() }
        val uiRefreshNonce by AppAppearanceState::refreshNonce
        val backgroundAnimationStyle = uiRefreshNonce.let { settingsManager.getBackgroundAnimationStyle() }
        val backgroundAnimationOpacity = uiRefreshNonce.let { settingsManager.getBackgroundAnimationOpacity() }
        val billeterieBackgroundAnimationStyle = uiRefreshNonce.let { settingsManager.getBilleterieBackgroundAnimationStyle() }
        val billeterieBackgroundAnimationOpacity = uiRefreshNonce.let { settingsManager.getBilleterieBackgroundAnimationOpacity() }
        val pageAnimationsEnabled = settingsManager.isPageAnimationsEnabled()

        var adminNavLayout by remember {
            mutableStateOf(AdminNavLayout.fromString(settingsManager.getDesktopAdminNavLayout()))
        }
        var adminNavRailExpanded by remember {
            mutableStateOf(settingsManager.isDesktopAdminNavRailExpanded())
        }
        fun refreshAdminNavPreferences() {
            adminNavLayout = AdminNavLayout.fromString(settingsManager.getDesktopAdminNavLayout())
            adminNavRailExpanded = settingsManager.isDesktopAdminNavRailExpanded()
        }

        val nav = LocalDesktopNavigation.current
            ?: error("DesktopNavigationHolder must be provided above AppRootContent on desktop")

        var showWelcome by nav::showWelcome
        var showSetupWizard by nav::showSetupWizard
        var showAdminAuth by nav::showAdminAuth
        var showAdminOrgPicker by remember { mutableStateOf(false) }
        val adminOrgPickerScope = rememberCoroutineScope()
        var showTicketCheck by nav::showTicketCheck
        var showPos by nav::showPos
        var selectedTab by nav::selectedTab
        var previousTab by nav::previousTab
        var showJobTypeManagement by nav::showJobTypeManagement
        var showVenueManagement by nav::showVenueManagement
        var showSalesSheetItemManagement by nav::showSalesSheetItemManagement
        var showPosAccountingReport by nav::showPosAccountingReport
        var showQRScanner by nav::showQRScanner
        var showVolunteerBenefits by remember { mutableStateOf<Volunteer?>(null) }
        var showScannedGuestDetail by remember { mutableStateOf<Guest?>(null) }
        var searchFocusTick by remember { mutableIntStateOf(0) }
        var lastAdminInteraction by remember { mutableLongStateOf(elapsedRealtimeMs()) }
        LaunchedEffect(openWelcomeAfterSetup) {
            if (openWelcomeAfterSetup) {
                showWelcome = true
                showAdminAuth = false
                showTicketCheck = false
                showPos = false
                nav.showAdminSetup = false
            }
        }

        fun touchAdminSession() {
            lastAdminInteraction = elapsedRealtimeMs()
        }

        if (showSetupWizard) {
            val wizardScope = rememberCoroutineScope()
            var wizardAuthEmail by remember {
                mutableStateOf(settingsManager.getFirebaseAuthEmail().ifBlank { null })
            }
            var wizardSignInFeedback by remember { mutableStateOf<String?>(null) }
            SetupWizardScreen(
                platformContext = platformContext,
                onSetupComplete = {
                    settingsManager.setSetupWizardCompleted(true)
                    showSetupWizard = false
                    showWelcome = true
                    showAdminAuth = false
                    showTicketCheck = false
                    showPos = false
                    nav.showAdminSetup = false
                    onThemeModeChanged(settingsManager.getThemeMode())
                },
                onThemeModeChanged = onThemeModeChanged,
                firebaseAuthEmail = wizardAuthEmail,
                firebaseSignInFeedback = wizardSignInFeedback,
                onRequestFirebaseSignIn = {
                    wizardScope.launch {
                        wizardSignInFeedback = null
                        when (val result = com.eventmanager.app.data.remote
                            .createFirebaseAuthService(platformContext)
                            .signInWithGoogle()
                        ) {
                            is com.eventmanager.app.data.remote.FirebaseAuthResult.Success -> {
                                wizardSignInFeedback = null
                                wizardAuthEmail = result.email
                                settingsManager.setFirebaseAuthEmail(result.email.orEmpty())
                            }
                            is com.eventmanager.app.data.remote.FirebaseAuthResult.Error -> {
                                wizardSignInFeedback = result.message
                            }
                        }
                    }
                },
            )
            return@CompositionLocalProvider
        }

        var databaseReady by remember { mutableStateOf(false) }
        var startupReady by remember { mutableStateOf(false) }
        var startupStep by remember { mutableStateOf(StartupSplashStep.Opening) }
        LaunchedEffect(platformContext) {
            withContext(Dispatchers.IO) { createDatabase(platformContext) }
            databaseReady = true
        }

        if (!databaseReady || !startupReady) {
            AppStartupSplash(step = if (databaseReady) startupStep else StartupSplashStep.Opening)
            if (!databaseReady) return@CompositionLocalProvider
        }

        val db = remember { createDatabase(platformContext) }
        val repository = remember(db) {
            EventManagerRepository(
                db.guestDao(), db.volunteerDao(), db.jobDao(),
                db.jobTypeConfigDao(), db.venueDao(), db.salesSheetItemDao(),
                db.accountTransferDao(), db.guestFormDao()
            )
        }
        val sheets = remember { GoogleSheetsService(platformContext) }
        val viewModel: EventManagerViewModel = viewModel {
            EventManagerViewModel(
                repository,
                sheets,
                platformContext,
                pendingRemoteWriteDao = db.pendingRemoteWriteDao(),
            )
        }

        var showAdminSetup by nav::showAdminSetup
        var adminCheckDone by nav::adminCheckDone
        var adminPrecheckComplete by remember { mutableStateOf(false) }
        var adminPrecheckSucceeded by remember { mutableStateOf(false) }

        LaunchedEffect(databaseReady) {
            if (!databaseReady) return@LaunchedEffect
            val minSplashMs = 800L
            val splashStart = elapsedRealtimeMs()
            try {
                if (openWelcomeAfterSetup) {
                    startupStep = StartupSplashStep.Preparing
                    viewModel.warmupWorkspacesAfterGate()
                    showAdminSetup = false
                    adminPrecheckSucceeded = true
                } else if (skipStartupSync) {
                    startupStep = StartupSplashStep.Preparing
                    viewModel.warmupWorkspacesAfterGate()
                    showAdminSetup = viewModel.evaluateLocalAdminSetupNeed()
                    adminPrecheckSucceeded = true
                } else {
                    startupStep = StartupSplashStep.Syncing
                    val gate = viewModel.evaluateAdminSetupGate()
                    adminPrecheckSucceeded = gate.syncSucceeded
                    showAdminSetup = gate.shouldOfferFirstAdminSetup
                    startupStep = StartupSplashStep.Preparing
                    viewModel.warmupWorkspacesAfterGate()
                }
            } catch (_: Exception) {
                adminPrecheckSucceeded = false
            }
            val remaining = minSplashMs - (elapsedRealtimeMs() - splashStart)
            if (remaining > 0) delay(remaining)
            adminPrecheckComplete = true
            adminCheckDone = true
            startupReady = true
        }

        if (!startupReady) {
            return@CompositionLocalProvider
        }

        val followScope = rememberCoroutineScope()

        Box(Modifier.fillMaxSize()) {
        when {
            showAdminSetup -> {
                val adminSetupVenues by viewModel.venues.collectAsState()
                AdminSetupScreen(
                    platformContext = platformContext,
                    venues = adminSetupVenues,
                    onCreateAdminGuest = { guest, cb -> viewModel.createAdminGuest(guest, onResult = cb) },
                    onCreateAdminVolunteer = { vol, cb -> viewModel.createAdminVolunteer(vol, onResult = cb) },
                    onAssignNfcUid = { adminType, entityId, uid ->
                        viewModel.assignNfcUidToAdmin(
                            isGuest = adminType == AdminType.GUEST,
                            entityId = entityId,
                            uid = uid
                        )
                    },
                    onComplete = { showAdminSetup = false },
                    onSkip = { showAdminSetup = false }
                )
            }
            showWelcome -> {
                DesktopDramaticSpaceEntrance(
                    enabled = pageAnimationsEnabled,
                    space = DesktopSpaceEntrance.Welcome,
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        AppBackgroundAnimation(
                            style = backgroundAnimationStyle,
                            opacity = backgroundAnimationOpacity,
                            settingsManager = settingsManager,
                            isDesktop = true,
                        )
                        DesktopWelcomeScreen(
                            viewModel = viewModel,
                            onAdminSelected = {
                                if (viewModel.isFirebaseAllOrgsMode()) {
                                    showAdminOrgPicker = true
                                } else {
                                    viewModel.prepareForAdminAuthentication()
                                    showWelcome = false
                                    showAdminAuth = true
                                }
                            },
                            onTicketCheckSelected = {
                                showWelcome = false
                                showTicketCheck = true
                            },
                            onPosSelected = {
                                showWelcome = false
                                showPos = true
                            },
                            showAdminAccessSyncIndicator = !adminPrecheckComplete
                        )
                    }
                }
            }
            showAdminAuth -> {
                DesktopDramaticSpaceEntrance(
                    enabled = pageAnimationsEnabled,
                    space = DesktopSpaceEntrance.Admin,
                ) {
                    AdminAuthRoute(
                        platformContext = platformContext,
                        viewModel = viewModel,
                        onAuthSuccess = {
                            viewModel.onAdminAuthSuccess()
                            showAdminAuth = false
                        },
                        onBack = {
                            if (viewModel.isAdminOrgReauthPending()) {
                                viewModel.cancelAdminOrgSwitchReauth {
                                    showAdminAuth = false
                                }
                            } else {
                                showAdminAuth = false
                                showWelcome = true
                            }
                        },
                    )
                }
            }
            else -> {
                val guests by viewModel.guests.collectAsState()
                val volunteers by viewModel.volunteers.collectAsState()
                val jobs by viewModel.jobs.collectAsState()
                val jobTypeConfigs by viewModel.jobTypeConfigs.collectAsState()
                val venues by viewModel.venues.collectAsState()
                val syncError by viewModel.syncError.collectAsState()
                val showSyncErrorDialog by viewModel.showSyncErrorDialog.collectAsState()
                val isSyncing by viewModel.isSyncing.collectAsState()
                val pendingAnnouncements by viewModel.pendingAnnouncements.collectAsState()
                val showSendAnnouncementDialog by viewModel.showSendAnnouncementDialog.collectAsState()
                val isAnnouncementSending by viewModel.isAnnouncementSending.collectAsState()
                val updateCheckResult by viewModel.updateCheckState.collectAsState()

                var showDeviceTimeErrorDialog by remember { mutableStateOf(false) }
                var showUpdateDialog by remember { mutableStateOf(false) }
                var hasCheckedUpdate by remember { mutableStateOf(false) }

                val adminSurfaceActive = !showWelcome && !showAdminAuth && !showTicketCheck && !showPos
                val endAdminSession by rememberUpdatedState {
                    showWelcome = true
                    showAdminAuth = false
                    showTicketCheck = false
                    showPos = false
                    selectedTab = AdminTab.Dashboard.index
                    showJobTypeManagement = false
                    showVenueManagement = false
                    showSalesSheetItemManagement = false
                    showPosAccountingReport = false
                }

                LaunchedEffect(adminSurfaceActive) {
                    viewModel.setAdminPanelActive(adminSurfaceActive)
                    if (!adminSurfaceActive || !viewModel.isFirebaseAllOrgsMode()) return@LaunchedEffect
                    val configured = viewModel.getFirebaseConfiguredOrgs()
                    val lastOrg = viewModel.getFirebaseLastSingleOrgId()
                    when {
                        lastOrg.isNotBlank() && configured.any { it.orgId == lastOrg } ->
                            viewModel.enterSingleOrgMode(lastOrg)
                        configured.size == 1 ->
                            viewModel.enterSingleOrgMode(configured.first().orgId)
                        configured.size >= 2 ->
                            showAdminOrgPicker = true
                    }
                }

                LaunchedEffect(adminSurfaceActive) {
                    if (!adminSurfaceActive) return@LaunchedEffect
                    while (adminSurfaceActive) {
                        delay(15_000)
                        if (elapsedRealtimeMs() - lastAdminInteraction >= ADMIN_SESSION_IDLE_TIMEOUT_MS) {
                            endAdminSession()
                            break
                        }
                    }
                }

                LaunchedEffect(Unit) {
                    if (!hasCheckedUpdate) {
                        hasCheckedUpdate = true
                        viewModel.checkForAppUpdates()
                    }
                }
                LaunchedEffect(updateCheckResult) {
                    if (updateCheckResult is UpdateCheckResult.UpdateAvailable) showUpdateDialog = true
                }
                LaunchedEffect(syncError) {
                    if (syncError != null && isDeviceTimeError(syncError)) {
                        showDeviceTimeErrorDialog = true
                    }
                }

                DisposableEffect(showAdminAuth, showTicketCheck, showPos, selectedTab, showQRScanner, showSyncErrorDialog) {
                    val inAdmin = !showAdminAuth && !showTicketCheck && !showPos
                    DesktopNavigationHooks.openSettingsTab = if (inAdmin) {
                        { selectedTab = AdminTab.Settings.index; touchAdminSession() }
                    } else null
                    DesktopNavigationHooks.focusListSearch = if (inAdmin && selectedTab in listOf(AdminTab.Guests.index, AdminTab.Volunteers.index)) {
                        { searchFocusTick++ }
                    } else null
                    DesktopNavigationHooks.dismissOverlay = {
                        if (showQRScanner) showQRScanner = false
                        if (showSyncErrorDialog) viewModel.dismissSyncErrorDialog()
                        showVolunteerBenefits = null
                        showScannedGuestDetail = null
                    }
                    DesktopNavigationHooks.cycleAdminTab = if (inAdmin) {
                        { forward ->
                            showJobTypeManagement = false
                            showVenueManagement = false
                            showSalesSheetItemManagement = false
                            showPosAccountingReport = false
                            if (showQRScanner) showQRScanner = false
                            if (showSyncErrorDialog) viewModel.dismissSyncErrorDialog()
                            showVolunteerBenefits = null
                            showScannedGuestDetail = null
                            val tabs = AdminTab.entries
                            val currentIdx = tabs.indexOf(AdminTab.fromIndex(selectedTab))
                            val nextIdx = if (forward) {
                                (currentIdx + 1) % tabs.size
                            } else {
                                (currentIdx - 1 + tabs.size) % tabs.size
                            }
                            previousTab = selectedTab
                            selectedTab = tabs[nextIdx].index
                            touchAdminSession()
                        }
                    } else null
                    onDispose {
                        DesktopNavigationHooks.openSettingsTab = null
                        DesktopNavigationHooks.focusListSearch = null
                        DesktopNavigationHooks.dismissOverlay = null
                        DesktopNavigationHooks.cycleAdminTab = null
                    }
                }

                if (showPos) {
                    DesktopDramaticSpaceEntrance(
                        enabled = pageAnimationsEnabled,
                        space = DesktopSpaceEntrance.Pos,
                    ) {
                        if (!skipStartupSync) {
                            DeferredUntilSpaceEntranceSettled {
                                withContext(Dispatchers.IO) { viewModel.performAutomaticFullSyncIfNeeded() }
                            }
                        }
                        val salesItems by viewModel.salesSheetItems.collectAsState()
                        PosFlow(
                            viewModel = viewModel,
                            salesItems = salesItems,
                            volunteers = volunteers,
                            guests = guests,
                            onBack = { showPos = false; showWelcome = true },
                            onFactoryResetComplete = {
                                showPos = false
                                showWelcome = false
                                showTicketCheck = false
                                showSetupWizard = true
                            },
                        )
                    }
                } else if (showTicketCheck) {
                    DesktopDramaticSpaceEntrance(
                        enabled = pageAnimationsEnabled,
                        space = DesktopSpaceEntrance.Billeterie,
                    ) {
                        if (!skipStartupSync) {
                            DeferredUntilSpaceEntranceSettled {
                                withContext(Dispatchers.IO) { viewModel.performAutomaticFullSyncIfNeeded() }
                            }
                        }
                        DesktopBilleterieFlow(
                            viewModel = viewModel,
                            guests = guests,
                            volunteers = volunteers,
                            jobs = jobs,
                            jobTypeConfigs = jobTypeConfigs,
                            settingsManager = settingsManager,
                            backgroundAnimationStyle = billeterieBackgroundAnimationStyle,
                            backgroundAnimationOpacity = billeterieBackgroundAnimationOpacity,
                            onExit = {
                                showTicketCheck = false
                                showWelcome = true
                            },
                            onFactoryResetComplete = {
                                showTicketCheck = false
                                showWelcome = false
                                showSetupWizard = true
                            },
                        )
                    }
                } else {
                    DesktopDramaticSpaceEntrance(
                        enabled = pageAnimationsEnabled,
                        space = DesktopSpaceEntrance.Admin,
                    ) {
                    if (!skipStartupSync) {
                        DeferredUntilSpaceEntranceSettled {
                            withContext(Dispatchers.IO) { viewModel.performAutomaticFullSyncIfNeeded() }
                        }
                    }
                    LaunchedEffect(selectedTab) {
                        if (selectedTab == previousTab) return@LaunchedEffect
                        delay(250)
                        when (AdminTab.fromIndex(selectedTab)) {
                            AdminTab.Dashboard -> viewModel.syncGuestsWithTargetedUpdates()
                            AdminTab.Guests -> viewModel.syncGuestsWithTargetedUpdates()
                            AdminTab.Volunteers -> viewModel.syncVolunteersWithTargetedUpdates()
                            AdminTab.Shifts -> viewModel.syncJobsWithTargetedUpdates()
                            AdminTab.Benefits -> {
                                viewModel.syncJobsWithTargetedUpdates()
                                delay(50)
                                viewModel.syncVolunteersWithTargetedUpdates()
                                delay(50)
                                viewModel.syncJobTypesWithTargetedUpdates()
                            }
                            AdminTab.Settings -> {
                                viewModel.syncJobTypesWithTargetedUpdates()
                                delay(50)
                                viewModel.syncVenuesWithTargetedUpdates()
                            }
                        }
                        previousTab = selectedTab
                    }

                    DesktopAdminShell(
                        navLayout = adminNavLayout,
                        navRailExpanded = adminNavRailExpanded,
                        onNavRailExpandedChange = { expanded ->
                            adminNavRailExpanded = expanded
                            settingsManager.setDesktopAdminNavRailExpanded(expanded)
                        },
                        selectedTab = selectedTab,
                        onTabSelected = { tab -> selectedTab = tab.index },
                        onBack = { endAdminSession() },
                        onSync = { viewModel.performDifferentialFullSync() },
                        onTouchSession = { touchAdminSession() },
                        onClearOverlays = {
                            showJobTypeManagement = false
                            showVenueManagement = false
                            showSalesSheetItemManagement = false
                            showPosAccountingReport = false
                        },
                        viewModel = viewModel,
                        onAdminRequireReauth = { showAdminAuth = true },
                        modifier = Modifier.fillMaxSize()
                    ) { padding ->
                        Box(
                            Modifier
                                .padding(padding)
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.surface)
                        ) {
                            AppBackgroundAnimation(
                                style = backgroundAnimationStyle,
                                opacity = backgroundAnimationOpacity,
                                settingsManager = settingsManager,
                                isDesktop = true,
                            )
                            when {
                                showJobTypeManagement -> DesktopJobTypeManagement(viewModel) { showJobTypeManagement = false }
                                showVenueManagement -> DesktopVenueManagement(viewModel) { showVenueManagement = false }
                                showSalesSheetItemManagement -> DesktopSalesSheetManagement(viewModel) { showSalesSheetItemManagement = false }
                                showPosAccountingReport -> {
                                    val accountTransfers by viewModel.accountTransfers.collectAsState()
                                    val salesSheetItems by viewModel.salesSheetItems.collectAsState()
                                    val venues by viewModel.venues.collectAsState()
                                    PosAccountingReportScreen(
                                        transfers = accountTransfers,
                                        salesItems = salesSheetItems,
                                        venues = venues,
                                        settingsManager = settingsManager,
                                        isPhone = false,
                                        onBack = { showPosAccountingReport = false },
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                else -> when (AdminTab.fromIndex(selectedTab)) {
                                    AdminTab.Dashboard -> DashboardScreen(
                                        guests = guests,
                                        volunteers = volunteers,
                                        jobs = jobs,
                                        venues = venues,
                                        jobTypeConfigs = jobTypeConfigs,
                                        viewModel = viewModel,
                                        isPhone = false,
                                        onLogout = {
                                            touchAdminSession()
                                            showWelcome = true
                                            showAdminAuth = false
                                            selectedTab = AdminTab.Dashboard.index
                                        },
                                        onOpenPosReport = { showPosAccountingReport = true },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    AdminTab.Guests -> DesktopGuestListWithViewModel(viewModel, searchFocusTick)
                                    AdminTab.Volunteers -> VolunteerScreen(
                                        volunteers = volunteers,
                                        volunteerJobs = jobs,
                                        venues = venues,
                                        jobTypeConfigs = jobTypeConfigs,
                                        onConfirmFutureEntry = { job, invites -> viewModel.markBenefitAsUsed(job, invites) },
                                        scrollBehavior = settingsManager.getScrollBehavior(),
                                        viewModel = viewModel,
                                        onAddVolunteer = { volunteer, photo -> viewModel.addVolunteer(volunteer, photo) },
                                        onUpdateVolunteer = { viewModel.updateVolunteer(it) },
                                        onDeleteVolunteer = { volunteer, deleteShifts ->
                                            viewModel.deleteVolunteer(volunteer, deleteShifts)
                                        }
                                    )
                                    AdminTab.Shifts -> JobTrackingScreen(
                                        jobs = jobs,
                                        volunteers = volunteers,
                                        jobTypeConfigs = jobTypeConfigs,
                                        venues = venues,
                                        scrollBehavior = settingsManager.getScrollBehavior(),
                                        onAddJob = { viewModel.addJob(it) },
                                        onUpdateJob = { viewModel.updateJob(it) },
                                        onDeleteJob = { viewModel.deleteJob(it) }
                                    )
                                    AdminTab.Benefits -> BenefitsScreen(
                                        volunteers = volunteers,
                                        jobs = jobs,
                                        jobTypeConfigs = jobTypeConfigs,
                                        scrollBehavior = settingsManager.getScrollBehavior()
                                    )
                                    AdminTab.Settings -> SettingsScreen(
                                        viewModel = viewModel,
                                        onNavigateToJobTypeManagement = {
                                            viewModel.syncJobTypesOnly()
                                            showJobTypeManagement = true
                                        },
                                        onNavigateToVenueManagement = {
                                            viewModel.syncVenuesWithTargetedUpdates()
                                            showVenueManagement = true
                                        },
                                        onNavigateToSalesSheetItemManagement = {
                                            viewModel.syncSalesSheetItemsWithTargetedUpdates()
                                            showSalesSheetItemManagement = true
                                        },
                                        onDesktopAdminNavLayoutChanged = { refreshAdminNavPreferences() },
                                        onFactoryResetComplete = {
                                            showJobTypeManagement = false
                                            showVenueManagement = false
                                            showSalesSheetItemManagement = false
                                            showPosAccountingReport = false
                                            showQRScanner = false
                                            showAdminAuth = false
                                            showTicketCheck = false
                                            showPos = false
                                            showWelcome = false
                                            selectedTab = AdminTab.Dashboard.index
                                            showSetupWizard = true
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                            }

                            FloatingActionButton(
                                onClick = { touchAdminSession(); showQRScanner = true },
                                modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
                            ) {
                                Icon(Icons.Default.QrCodeScanner, contentDescription = null)
                            }
                        }
                    }
                    }
                }

                if (showQRScanner) {
                    QRScannerDialog(
                        platformContext = platformContext,
                        onDismiss = { showQRScanner = false },
                        onMatchFound = { match ->
                            when (match) {
                                is ScannerMatch.VolunteerMatch -> {
                                    showVolunteerBenefits = match.volunteer
                                    showScannedGuestDetail = null
                                }
                                is ScannerMatch.GuestMatch -> {
                                    showScannedGuestDetail = match.guest
                                    showVolunteerBenefits = null
                                }
                            }
                            showQRScanner = false
                        },
                        volunteers = volunteers,
                        guests = guests
                    )
                }

                showVolunteerBenefits?.let { volunteer ->
                    val offsetHours = remember { settingsManager.getDateChangeOffsetHours() }
                    val benefitStatus = remember(volunteer.id, jobs, jobTypeConfigs, offsetHours) {
                        BenefitCalculator.calculateVolunteerBenefitStatus(volunteer, jobs, jobTypeConfigs, offsetHours = offsetHours)
                    }
                    val volunteerJobs = remember(volunteer.id, jobs) { jobs.filter { it.volunteerId == volunteer.id } }
                    Dialog(onDismissRequest = { showVolunteerBenefits = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        VolunteerBenefitsPanel(
                            modifier = Modifier.fillMaxWidth(0.9f).heightIn(max = 720.dp),
                            volunteer = volunteer,
                            volunteerBenefitStatus = benefitStatus,
                            volunteerJobs = volunteerJobs,
                            venues = venues,
                            jobTypeConfigs = jobTypeConfigs,
                            onClose = { showVolunteerBenefits = null },
                            onConfirmEntry = { job, invites -> viewModel.markBenefitAsUsed(job, invites) },
                            onAssignNfcUid = { updated, uid ->
                                viewModel.updateVolunteer(updated.copy(nfcCardUid = uid, lastModified = System.currentTimeMillis()))
                                showVolunteerBenefits = updated.copy(nfcCardUid = uid)
                            },
                            viewModel = viewModel
                        )
                    }
                }

                showScannedGuestDetail?.let { guest ->
                    Dialog(onDismissRequest = { showScannedGuestDetail = null }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                        GuestDetailPanel(
                            modifier = Modifier.fillMaxWidth(0.9f).heightIn(max = 720.dp),
                            guest = guest,
                            venues = venues,
                            onEdit = { updated ->
                                viewModel.updateGuest(updated)
                                showScannedGuestDetail = updated
                            },
                            onAssignNfcUid = { updatedGuest, uid ->
                                val withUid = updatedGuest.copy(nfcCardUid = uid, lastModified = System.currentTimeMillis())
                                viewModel.updateGuest(withUid)
                                showScannedGuestDetail = withUid
                            },
                            onDelete = { toDelete ->
                                viewModel.deleteGuest(toDelete)
                                showScannedGuestDetail = null
                            },
                            onClose = { showScannedGuestDetail = null },
                            viewModel = viewModel
                        )
                    }
                }

                SyncErrorDialog(
                    isVisible = showSyncErrorDialog && !showDeviceTimeErrorDialog,
                    onDismiss = { viewModel.dismissSyncErrorDialog() },
                    onRetry = { viewModel.performFullSync() },
                    errorMessage = syncError.orEmpty(),
                    onDontTellTodayChanged = { suppress ->
                        if (suppress) viewModel.setSyncErrorSuppressedToday()
                        viewModel.dismissSyncErrorDialog()
                    },
                    isSyncing = isSyncing
                )

                DeviceTimeErrorDialog(
                    isVisible = showDeviceTimeErrorDialog,
                    onDismiss = {
                        showDeviceTimeErrorDialog = false
                        viewModel.dismissSyncErrorDialog()
                    },
                    onOpenSettings = { openDateSettings(platformContext) },
                    onDontTellTodayChanged = { suppress ->
                        if (suppress) viewModel.setSyncErrorSuppressedToday()
                    }
                )

                if (showSendAnnouncementDialog) {
                    SendAnnouncementDialog(
                        venues = venues,
                        isSending = isAnnouncementSending,
                        onDismiss = { viewModel.closeSendAnnouncementDialog() },
                        onSend = { targetVenueIds, title, message ->
                            viewModel.sendAnnouncement(targetVenueIds, title, message)
                        }
                    )
                }

                pendingAnnouncements.firstOrNull()?.let { announcement ->
                    AnnouncementPopup(
                        announcement = announcement,
                        onDismiss = { viewModel.dismissCurrentAnnouncement() }
                    )
                }

                val downloadState by viewModel.updateDownloadState.collectAsState()

                AppUpdateFlowDialog(
                    visible = showUpdateDialog && updateCheckResult is UpdateCheckResult.UpdateAvailable,
                    updateResult = updateCheckResult,
                    downloadState = downloadState,
                    fallbackStoreUrl = settingsManager.getUpdateStoreUrl(),
                    onDismiss = { showUpdateDialog = false },
                    onDownload = { url -> viewModel.downloadUpdate(url) },
                    onInstall = { path ->
                        viewModel.installUpdate(path)
                        showUpdateDialog = false
                    },
                )
            }
        }

        if (showAdminOrgPicker) {
            com.eventmanager.app.ui.components.AdminOrgPickerDialog(
                configuredOrgs = viewModel.getFirebaseConfiguredOrgs(),
                viewModel = viewModel,
                onOrgSelected = { orgId ->
                    showAdminOrgPicker = false
                    adminOrgPickerScope.launch {
                        viewModel.enterSingleOrgMode(orgId)
                        viewModel.prepareForAdminAuthentication()
                        showWelcome = false
                        showAdminAuth = true
                    }
                },
                onDismiss = { showAdminOrgPicker = false },
            )
        }

        com.eventmanager.app.ui.components.BackendMigrationUiHost(
            viewModel = viewModel,
            settingsManager = settingsManager,
            platformContext = platformContext,
            onRequestFirebaseSignIn = { onResult ->
                followScope.launch {
                    val result = com.eventmanager.app.data.remote
                        .createFirebaseAuthService(platformContext)
                        .signInWithGoogle()
                    when (result) {
                        is com.eventmanager.app.data.remote.FirebaseAuthResult.Success -> {
                            settingsManager.setFirebaseAuthEmail(result.email.orEmpty())
                        }
                        is com.eventmanager.app.data.remote.FirebaseAuthResult.Error -> Unit
                    }
                    onResult(result)
                }
            },
        )

        GuestFormArrivalToastHost(viewModel = viewModel)
        }
    }
}

@Composable
private fun DesktopWelcomeScreen(
    viewModel: EventManagerViewModel,
    onAdminSelected: () -> Unit,
    onTicketCheckSelected: () -> Unit,
    onPosSelected: () -> Unit,
    showAdminAccessSyncIndicator: Boolean
) {
    val appName = stringResource(Res.string.app_name)

    Box(modifier = Modifier.fillMaxSize()) {
        FirebaseOrgSwitcher(
            viewModel = viewModel,
            placement = FirebaseOrgSwitcherPlacement.WelcomeTopEnd,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(20.dp),
        )

        WideWelcomeContent(
            appName = appName,
            onAdminSelected = onAdminSelected,
            onTicketCheckSelected = onTicketCheckSelected,
            onPosSelected = onPosSelected,
            hoverEnabled = true,
            syncSlot = {
                DesktopWelcomeAdminSyncSlot(
                    syncing = showAdminAccessSyncIndicator,
                    modifier = Modifier
                        .widthIn(max = 560.dp)
                        .fillMaxWidth(),
                )
            },
        )
    }
}

@Composable
private fun DesktopWelcomeAdminSyncSlot(
    syncing: Boolean,
    modifier: Modifier = Modifier,
) {
    var bannerVisible by remember { mutableStateOf(syncing) }
    var showSuccess by remember { mutableStateOf(false) }

    LaunchedEffect(syncing) {
        if (syncing) {
            showSuccess = false
            bannerVisible = true
        } else if (bannerVisible) {
            showSuccess = true
            delay(900)
            bannerVisible = false
            delay(420)
            showSuccess = false
        }
    }

    AnimatedVisibility(
        visible = bannerVisible,
        enter = fadeIn(animationSpec = tween(280)) +
            expandVertically(
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
                expandFrom = Alignment.Top,
            ) +
            scaleIn(
                initialScale = 0.88f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioMediumBouncy,
                    stiffness = Spring.StiffnessMediumLow,
                ),
            ),
        exit = fadeOut(animationSpec = tween(280)) +
            shrinkVertically(
                animationSpec = spring(
                    dampingRatio = 0.82f,
                    stiffness = Spring.StiffnessMedium,
                ),
                shrinkTowards = Alignment.Top,
            ) +
            scaleOut(
                targetScale = 0.82f,
                animationSpec = tween(320),
            ) +
            slideOutVertically(
                animationSpec = tween(320),
                targetOffsetY = { -it / 3 },
            ),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            DesktopWelcomeAdminSyncBanner(
                showSuccess = showSuccess,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun DesktopWelcomeAdminSyncBanner(
    showSuccess: Boolean,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(18.dp)

    val wobble = rememberInfiniteTransition(label = "welcomeSyncWobble")
    val successScale by animateFloatAsState(
        targetValue = if (showSuccess) 1f else 0.7f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "welcomeSyncSuccessScale",
    )

    Box(
        modifier = modifier
            .shadow(
                elevation = 2.dp,
                shape = shape,
                clip = false,
                ambientColor = colorScheme.primary.copy(alpha = 0.08f),
                spotColor = colorScheme.primary.copy(alpha = 0.12f),
            )
            .clip(shape)
            .background(
                if (showSuccess) {
                    colorScheme.tertiaryContainer.copy(alpha = 0.92f)
                } else {
                    colorScheme.surfaceContainerLow
                },
            )
            .border(
                width = 1.dp,
                color = if (showSuccess) {
                    colorScheme.tertiary.copy(alpha = 0.35f)
                } else {
                    colorScheme.outlineVariant.copy(alpha = 0.4f)
                },
                shape = shape,
            ),
    ) {
        AnimatedContent(
            targetState = showSuccess,
            transitionSpec = {
                (
                    fadeIn(tween(220)) +
                        scaleIn(
                            initialScale = 0.86f,
                            animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
                        ) +
                        slideInVertically { it / 4 }
                    ) togetherWith (
                    fadeOut(tween(160)) +
                        scaleOut(targetScale = 0.92f) +
                        slideOutVertically { -it / 5 }
                    ) using SizeTransform(clip = false)
            },
            label = "welcomeSyncBannerContent",
        ) { success ->
            Row(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (success) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .scale(successScale)
                            .clip(CircleShape)
                            .background(colorScheme.tertiary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = colorScheme.tertiary,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Text(
                        text = stringResource(Res.string.welcome_admin_sync_ready),
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.6.sp,
                        ),
                        color = colorScheme.onTertiaryContainer,
                    )
                } else {
                    val pulseScale by wobble.animateFloat(
                        initialValue = 0.92f,
                        targetValue = 1.08f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(700),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "welcomeSyncPulseScale",
                    )
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(22.dp)
                            .scale(pulseScale),
                        strokeWidth = 2.5.dp,
                        color = colorScheme.primary,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = stringResource(Res.string.admin_auth_syncing_title),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.SemiBold,
                                letterSpacing = 0.4.sp,
                            ),
                        )
                        Text(
                            text = stringResource(Res.string.admin_precheck_sync_loading),
                            style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 0.2.sp),
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopBilleterieFlow(
    viewModel: EventManagerViewModel,
    guests: List<Guest>,
    volunteers: List<Volunteer>,
    jobs: List<Job>,
    jobTypeConfigs: List<JobTypeConfig>,
    settingsManager: SettingsManager,
    backgroundAnimationStyle: String,
    backgroundAnimationOpacity: Float,
    onExit: () -> Unit,
    onFactoryResetComplete: () -> Unit,
) {
    val nav = LocalDesktopNavigation.current
        ?: error("DesktopNavigationHolder must be provided above AppRootContent on desktop")
    var section by nav::billeterieSection
    var showBilleterieSettings by nav::showBilleterieSettings
    var scannerReturnSection by rememberSaveable { mutableStateOf(BilleterieSection.Home.name) }

    DeferredUntilSpaceEntranceSettled {
        viewModel.updateSyncInterval()
        viewModel.syncGuestsWithTargetedUpdates()
        if (settingsManager.isPeopleCounterVisible()) {
            viewModel.refreshVenuesForPeopleCounterQuietly()
        }
    }

    PlatformBackHandler(enabled = true) {
        when {
            showBilleterieSettings -> showBilleterieSettings = false
            section == BilleterieSection.Scanner.name -> section = scannerReturnSection
            section == BilleterieSection.GuestList.name -> section = BilleterieSection.Home.name
            section == BilleterieSection.Pos.name -> performPosFlowExit(viewModel) {
                section = BilleterieSection.Home.name
            }
            else -> onExit()
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
        AppBackgroundAnimation(
            style = backgroundAnimationStyle,
            opacity = backgroundAnimationOpacity,
            settingsManager = settingsManager,
            isDesktop = true,
        )
        when (section) {
            BilleterieSection.Home.name -> {
                if (showBilleterieSettings) {
                    BilleterieSettingsScreen(
                        viewModel = viewModel,
                        onBack = { showBilleterieSettings = false },
                        onFactoryResetComplete = onFactoryResetComplete,
                    )
                } else {
                    DesktopBilleterieHomeScreen(
                        guests = guests,
                        viewModel = viewModel,
                        onBack = onExit,
                        onOpenGuestList = { section = BilleterieSection.GuestList.name },
                        onOpenScanner = {
                            scannerReturnSection = BilleterieSection.Home.name
                            section = BilleterieSection.Scanner.name
                        },
                        onOpenPos = { section = BilleterieSection.Pos.name },
                        onOpenSettings = { showBilleterieSettings = true },
                    )
                }
            }
            BilleterieSection.Scanner.name -> {
                BilleterieScannerScreen(
                    volunteers = volunteers,
                    guests = guests,
                    jobs = jobs,
                    jobTypeConfigs = jobTypeConfigs,
                    onBack = { section = scannerReturnSection },
                    onConfirmEntry = { job, invites -> viewModel.markBenefitAsUsed(job, invites) },
                    viewModel = viewModel,
                )
            }
            BilleterieSection.Pos.name -> {
                val salesItems by viewModel.salesSheetItems.collectAsState()
                PosFlow(
                    viewModel = viewModel,
                    salesItems = salesItems,
                    volunteers = volunteers,
                    guests = guests,
                    onBack = { section = BilleterieSection.Home.name },
                    onFactoryResetComplete = onFactoryResetComplete,
                )
            }
            else -> {
                Scaffold(
                    containerColor = if (BackgroundAnimationStyle.isEnabled(backgroundAnimationStyle)) {
                        Color.Transparent
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
                    topBar = {
                        TopAppBar(
                            title = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text(stringResource(Res.string.nav_guests))
                                    FirebaseOrgSwitcher(
                                        viewModel = viewModel,
                                        placement = FirebaseOrgSwitcherPlacement.TopBarTitleEnd,
                                    )
                                }
                            },
                            navigationIcon = {
                                IconButton(onClick = { section = BilleterieSection.Home.name }) {
                                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.setup_back))
                                }
                            }
                        )
                    }
                ) { padding ->
                    Box(Modifier.padding(padding).fillMaxSize()) {
                        DesktopGuestListWithViewModel(viewModel, readOnly = true)
                        FloatingActionButton(
                            onClick = {
                                scannerReturnSection = BilleterieSection.GuestList.name
                                section = BilleterieSection.Scanner.name
                            },
                            modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)
                        ) {
                            Icon(
                                Icons.Default.QrCodeScanner,
                                contentDescription = stringResource(Res.string.billeterie_button_scanner)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopGuestListWithViewModel(
    viewModel: EventManagerViewModel,
    searchFocusTick: Int = 0,
    readOnly: Boolean = false
) {
    val guests by viewModel.guests.collectAsState()
    val volunteers by viewModel.volunteers.collectAsState()
    val jobs by viewModel.jobs.collectAsState()
    val jobTypeConfigs by viewModel.jobTypeConfigs.collectAsState()
    val venues by viewModel.venues.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val settingsManager = settingsManagerFor(LocalPlatformContext.current)
    val scope = rememberCoroutineScope()

    GuestListScreen(
        guests = guests,
        volunteers = volunteers,
        jobs = jobs,
        jobTypeConfigs = jobTypeConfigs,
        venues = venues,
        isSyncing = isSyncing,
        lastSyncTime = settingsManager.getLastSyncTime(),
        scrollBehavior = settingsManager.getScrollBehavior(),
        readOnly = readOnly,
        onAddGuest = { guest, photo -> scope.launch { viewModel.addGuest(guest, photo) } },
        onAddTemporaryGuests = { scope.launch { viewModel.addTemporaryGuestBatch(it) } },
        onUpdateGuest = { scope.launch { viewModel.updateGuest(it) } },
        onUpdateVolunteer = { scope.launch { viewModel.updateVolunteer(it) } },
        onDeleteGuest = { scope.launch { viewModel.deleteGuest(it) } },
        onRefreshTemporaryGuests = { viewModel.refreshTemporaryGuests() },
        onConfirmEntry = { job, invites -> scope.launch { viewModel.markBenefitAsUsed(job, invites) } },
        searchFocusTick = searchFocusTick,
        viewModel = viewModel
    )
}

@Composable
private fun DesktopJobTypeManagement(viewModel: EventManagerViewModel, onBack: () -> Unit) {
    val jobTypeConfigs by viewModel.jobTypeConfigs.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { viewModel.syncJobTypesWithTargetedUpdates() }
    JobTypeManagementScreen(
        jobTypeConfigs = jobTypeConfigs,
        onAddJobTypeConfig = { scope.launch { viewModel.addJobTypeConfig(it) } },
        onUpdateJobTypeConfig = { scope.launch { viewModel.updateJobTypeConfig(it) } },
        onDeleteJobTypeConfig = { scope.launch { viewModel.deleteJobTypeConfig(it) } },
        onUpdateJobTypeConfigStatus = { id, active ->
            scope.launch {
                jobTypeConfigs.find { it.id == id }?.let { viewModel.updateJobTypeConfig(it.copy(isActive = active)) }
            }
        },
        onBack = onBack
    )
}

@Composable
private fun DesktopVenueManagement(viewModel: EventManagerViewModel, onBack: () -> Unit) {
    val venues by viewModel.venues.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { viewModel.syncVenuesWithTargetedUpdates() }
    VenueManagementScreen(
        venues = venues,
        onAddVenue = { scope.launch { viewModel.addVenue(it) } },
        onUpdateVenue = { scope.launch { viewModel.updateVenue(it) } },
        onDeleteVenue = { scope.launch { viewModel.deleteVenue(it) } },
        onUpdateVenueStatus = { id, active ->
            scope.launch {
                venues.find { it.id == id }?.let { viewModel.updateVenue(it.copy(isActive = active)) }
            }
        },
        onBack = onBack
    )
}

@Composable
private fun DesktopSalesSheetManagement(viewModel: EventManagerViewModel, onBack: () -> Unit) {
    val items by viewModel.salesSheetItems.collectAsState()
    val venues by viewModel.venues.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { viewModel.syncSalesSheetItemsWithTargetedUpdates() }
    SalesSheetItemManagementScreen(
        items = items,
        venues = venues,
        onAddItem = { scope.launch { viewModel.addSalesSheetItem(it) } },
        onUpdateItem = { scope.launch { viewModel.updateSalesSheetItem(it) } },
        onDeleteItem = { scope.launch { viewModel.deleteSalesSheetItem(it) } },
        onUpdateItemStatus = { id, active -> viewModel.updateSalesSheetItemStatus(id, active) },
        onBack = onBack,
        viewModel = viewModel
    )
}
