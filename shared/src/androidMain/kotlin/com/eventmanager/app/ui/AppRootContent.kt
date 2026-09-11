package com.eventmanager.app.ui

import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.platform.createAppStorage
import com.eventmanager.app.platform.createDatabase
import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import android.os.Vibrator
import android.os.VibrationEffect
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.activity.compose.BackHandler
import java.util.*
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material3.*
import com.eventmanager.app.ui.components.QRScannerDialog
import com.eventmanager.app.ui.components.ScannerMatch
import com.eventmanager.app.ui.components.VolunteerBenefitsPanel
import com.eventmanager.app.ui.components.GuestDetailPanel
import com.eventmanager.app.ui.components.PeopleCounter
import com.eventmanager.app.ui.components.PosAccountingReportEntryCard
import com.eventmanager.app.platform.getAdminSessionHost
import com.eventmanager.app.ui.ADMIN_SESSION_IDLE_TIMEOUT_MS
import com.eventmanager.app.ui.components.SendAnnouncementButton
import com.eventmanager.app.ui.components.SendAnnouncementDialog
import com.eventmanager.app.ui.components.AnnouncementPopup
import com.eventmanager.app.ui.components.BackendMigrationUiHost
import com.eventmanager.app.ui.components.GuestFormArrivalToastHost
import com.eventmanager.app.ui.scaling.ResolutionScaler
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.data.utils.GuestListOccupancy
import com.eventmanager.app.data.utils.VolunteerActivityManager
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.SizeTransform
import androidx.compose.foundation.layout.BoxWithConstraints
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eventmanager.app.data.database.EventManagerDatabase
import com.eventmanager.app.data.repository.EventManagerRepository
import com.eventmanager.app.data.sync.DateFormatUtils
import com.eventmanager.app.data.sync.GoogleSheetsService
import com.eventmanager.app.ui.screens.BenefitsScreen
import com.eventmanager.app.ui.screens.GuestListScreen
import com.eventmanager.app.ui.screens.JobTrackingScreen
import com.eventmanager.app.ui.screens.LogoutCard
import com.eventmanager.app.ui.screens.JobTypeManagementScreen
import com.eventmanager.app.ui.screens.SettingsScreen
import com.eventmanager.app.ui.screens.SetupWizardScreen
import com.eventmanager.app.ui.screens.AdminAuthRoute
import com.eventmanager.app.ui.screens.AdminStartupSyncBanner
import com.eventmanager.app.ui.screens.AdminSetupScreen
import com.eventmanager.app.ui.screens.AdminType
import com.eventmanager.app.ui.screens.BilleterieHomeScreen
import com.eventmanager.app.ui.screens.BilleterieScannerScreen
import com.eventmanager.app.ui.screens.BilleterieSettingsScreen
import com.eventmanager.app.ui.screens.PosAccountingReportScreen
import com.eventmanager.app.ui.screens.PosFlow
import com.eventmanager.app.ui.screens.WideBilleterieHomeScreen
import com.eventmanager.app.ui.screens.WideWelcomeContent
import com.eventmanager.app.ui.screens.performPosFlowExit
import com.eventmanager.app.ui.screens.SalesSheetItemManagementScreen
import com.eventmanager.app.ui.screens.VenueManagementScreen
import com.eventmanager.app.ui.screens.GuestFormManagementScreen
import com.eventmanager.app.ui.screens.VolunteerScreen
import com.eventmanager.app.ui.theme.EventManagerTheme
import com.eventmanager.app.ui.theme.ThemeMode
import com.eventmanager.app.ui.transitions.DeferredUntilSpaceEntranceSettled
import com.eventmanager.app.ui.transitions.DramaticSpaceEntrance
import com.eventmanager.app.ui.transitions.SpaceEntrance
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.platform.createPlatformContext
import com.eventmanager.app.ui.utils.*
import com.eventmanager.app.ui.components.AppBackgroundAnimation
import com.eventmanager.app.ui.components.BackgroundAnimationStyle
import com.eventmanager.app.ui.components.FirebaseOrgSwitcher
import com.eventmanager.app.ui.components.FirebaseOrgSwitcherPlacement
import com.eventmanager.app.ui.components.isFirebaseOrgSwitcherVisible
import com.eventmanager.app.ui.components.WelcomeForegroundPanel
import com.eventmanager.app.ui.components.WelcomeSecondaryButton
import com.eventmanager.app.ui.components.DashboardClockCard
import com.eventmanager.app.ui.components.SnowAnimation
import com.eventmanager.app.ui.components.FireworksAnimation
import com.eventmanager.app.ui.components.ValentineAnimation
import com.eventmanager.app.ui.components.WorkersDayAnimation
import com.eventmanager.app.ui.components.PrideAnimation
import com.eventmanager.app.ui.components.BeerAnimation
import com.eventmanager.app.R
import androidx.compose.ui.text.style.TextAlign
import com.eventmanager.app.ui.components.AppStartupSplash
import com.eventmanager.app.ui.components.StartupSplashStep
import com.eventmanager.app.ui.components.StatsGraphsPanel
import com.eventmanager.app.platform.elapsedRealtimeMs
import java.util.Calendar
import com.eventmanager.app.ui.components.SyncErrorDialog
import android.content.Intent
import android.provider.Settings
import com.eventmanager.app.ui.components.DeviceTimeErrorDialog
import com.eventmanager.app.ui.components.SyncStatusDialog
import com.eventmanager.app.ui.components.SyncStatusPill
import com.eventmanager.app.utils.ImageUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.graphics.ImageBitmap
import com.eventmanager.app.data.update.UpdateCheckResult
import com.eventmanager.app.data.update.DownloadState
import androidx.compose.runtime.LaunchedEffect
import android.net.Uri
import android.content.BroadcastReceiver
import android.content.IntentFilter
import android.os.SystemClock
import android.view.MotionEvent
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Composable
actual fun AppRootContent(
    platformContext: PlatformContext,
    onThemeModeChanged: (String) -> Unit
) {
    val appContext = platformContext.androidContext
    val settingsManager = remember { SettingsManager(createAppStorage(platformContext)) }
    remember(platformContext, settingsManager) {
        com.eventmanager.app.data.sync.installInstitutionLogoBridge(platformContext, settingsManager)
    }
    val skipStartupSync = remember { settingsManager.consumeSkipNextStartupSync() }
    val openWelcomeAfterSetup = remember { settingsManager.consumeOpenWelcomeAfterSetup() }
    val uiRefreshNonce by com.eventmanager.app.ui.platform.AppAppearanceState::refreshNonce
    val backgroundAnimationStyle = uiRefreshNonce.let { settingsManager.getBackgroundAnimationStyle() }
    val backgroundAnimationOpacity = uiRefreshNonce.let { settingsManager.getBackgroundAnimationOpacity() }
    val billeterieBackgroundAnimationStyle = uiRefreshNonce.let { settingsManager.getBilleterieBackgroundAnimationStyle() }
    val billeterieBackgroundAnimationOpacity = uiRefreshNonce.let { settingsManager.getBilleterieBackgroundAnimationOpacity() }

    // Use rememberSaveable to persist state across configuration changes
    // When Google Sheets is not configured, setup runs first; then the welcome screen on every launch.
    var showWelcome by rememberSaveable { mutableStateOf(true) }
    var showSetupWizard by rememberSaveable { mutableStateOf(settingsManager.shouldShowSetupWizard()) }
    var showAdminAuth by rememberSaveable { mutableStateOf(false) }
    var showAdminOrgPicker by rememberSaveable { mutableStateOf(false) }
    val adminOrgPickerScope = rememberCoroutineScope()
    var showTicketCheck by rememberSaveable { mutableStateOf(false) }
    var showPos by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(openWelcomeAfterSetup) {
        if (openWelcomeAfterSetup) {
            showWelcome = true
            showAdminAuth = false
            showAdminOrgPicker = false
            showTicketCheck = false
            showPos = false
        }
    }
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    var previousTab by rememberSaveable { mutableStateOf(0) }
    val pageAnimationsEnabled = settingsManager.isPageAnimationsEnabled()
    var showJobTypeManagement by rememberSaveable { mutableStateOf(false) }
    var showVenueManagement by rememberSaveable { mutableStateOf(false) }
    var showSalesSheetItemManagement by rememberSaveable { mutableStateOf(false) }
    var showGuestFormManagement by rememberSaveable { mutableStateOf(false) }
    var showPosAccountingReport by rememberSaveable { mutableStateOf(false) }
    var showQRScanner by rememberSaveable { mutableStateOf(false) }
    var showVolunteerBenefits: Volunteer? by remember { mutableStateOf(null) }
    var showScannedGuestDetail: Guest? by remember { mutableStateOf(null) }
    
    // Haptic feedback for page navigation - very subtle vibration
    val vibrator = remember { ContextCompat.getSystemService(appContext, Vibrator::class.java) }

    // Update check state - only show dialog if update is available
    var showUpdateDialog by remember { mutableStateOf(false) }
    var hasCheckedUpdate by remember { mutableStateOf(false) }
    
    if (showSetupWizard) {
        val wizardAuthScope = rememberCoroutineScope()
        var wizardAuthEmail by remember {
            mutableStateOf(
                com.eventmanager.app.data.sync.settingsManagerFor(platformContext)
                    .getFirebaseAuthEmail().ifBlank { null }
            )
        }
        val wizardFirebaseAuth = remember {
            com.eventmanager.app.data.remote.createFirebaseAuthService(platformContext)
                as? com.eventmanager.app.data.remote.AndroidFirebaseAuthService
        }
        var wizardAuthFeedback by remember { mutableStateOf<String?>(null) }
        val wizardAuthLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
            contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
        ) { activityResult ->
            wizardAuthScope.launch {
                val auth = wizardFirebaseAuth ?: return@launch
                when (val result = auth.completeSignInFromIntent(activityResult.data)) {
                    is com.eventmanager.app.data.remote.FirebaseAuthResult.Success -> {
                        wizardAuthEmail = result.email
                        wizardAuthFeedback = null
                        com.eventmanager.app.data.sync.settingsManagerFor(platformContext)
                            .setFirebaseAuthEmail(result.email.orEmpty())
                    }
                    is com.eventmanager.app.data.remote.FirebaseAuthResult.Error ->
                        wizardAuthFeedback = result.message
                }
            }
        }
        SetupWizardScreen(
            platformContext = platformContext,
            onSetupComplete = {
                showSetupWizard = false
                showWelcome = true
                showAdminAuth = false
                showAdminOrgPicker = false
                showTicketCheck = false
                showPos = false
                // Resolution scale is applied in attachBaseContext; recreate so layout size matches prefs.
                (appContext as? Activity)?.recreate()
            },
            onThemeModeChanged = onThemeModeChanged,
            firebaseAuthEmail = wizardAuthEmail,
            firebaseSignInFeedback = wizardAuthFeedback,
            onRequestFirebaseSignIn = {
                wizardAuthFeedback = null
                wizardFirebaseAuth?.let { wizardAuthLauncher.launch(it.getSignInIntent()) }
            },
        )
    } else {
        var database by remember { mutableStateOf<EventManagerDatabase?>(null) }
        LaunchedEffect(Unit) {
            database = withContext(Dispatchers.IO) {
                createDatabase(platformContext)
            }
        }

        if (database == null) {
            AppStartupSplash(step = StartupSplashStep.Opening)
        } else {
            val db = database!!
            val repository = remember(db) {
                EventManagerRepository(
                    db.guestDao(),
                    db.volunteerDao(),
                    db.jobDao(),
                    db.jobTypeConfigDao(),
                    db.venueDao(),
                    db.salesSheetItemDao(),
                    db.accountTransferDao(),
                    db.guestFormDao()
                )
            }
            val googleSheetsService = remember { GoogleSheetsService(platformContext) }
            val viewModel: EventManagerViewModel = viewModel {
                EventManagerViewModel(
                    repository,
                    googleSheetsService,
                    platformContext,
                    pendingRemoteWriteDao = db.pendingRemoteWriteDao(),
                )
            }

            // ── First-admin setup gate ──────────────────────────────────────
            var showAdminSetup by rememberSaveable(openWelcomeAfterSetup) { mutableStateOf(false) }
            var adminCheckDone by rememberSaveable { mutableStateOf(false) }
            var adminPrecheckComplete by remember { mutableStateOf(false) }
            var adminPrecheckSucceeded by remember { mutableStateOf(false) }
            var startupReady by remember { mutableStateOf(false) }
            var startupStep by remember { mutableStateOf(StartupSplashStep.Syncing) }

            LaunchedEffect(db) {
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
                AppStartupSplash(step = startupStep)
                return
            }

            val appSettingsManager = remember(platformContext) {
                settingsManagerFor(platformContext)
            }
            var followAuthEmail by remember {
                mutableStateOf(appSettingsManager.getFirebaseAuthEmail().ifBlank { null })
            }
            val followFirebaseAuth = remember {
                com.eventmanager.app.data.remote.createFirebaseAuthService(platformContext)
                    as? com.eventmanager.app.data.remote.AndroidFirebaseAuthService
            }
            val followAuthScope = rememberCoroutineScope()
            var pendingFollowSignInResult by remember {
                mutableStateOf<((com.eventmanager.app.data.remote.FirebaseAuthResult) -> Unit)?>(null)
            }
            val followAuthLauncher = androidx.activity.compose.rememberLauncherForActivityResult(
                contract = androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()
            ) { activityResult ->
                followAuthScope.launch {
                    val auth = followFirebaseAuth
                    val result = if (auth == null) {
                        com.eventmanager.app.data.remote.FirebaseAuthResult.Error("Firebase Auth unavailable")
                    } else {
                        auth.completeSignInFromIntent(activityResult.data)
                    }
                    when (result) {
                        is com.eventmanager.app.data.remote.FirebaseAuthResult.Success -> {
                            followAuthEmail = result.email
                            appSettingsManager.setFirebaseAuthEmail(result.email.orEmpty())
                        }
                        is com.eventmanager.app.data.remote.FirebaseAuthResult.Error -> Unit
                    }
                    pendingFollowSignInResult?.invoke(result)
                    pendingFollowSignInResult = null
                }
            }

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
                DramaticSpaceEntrance(
                    enabled = pageAnimationsEnabled,
                    space = SpaceEntrance.Welcome,
                ) {
                    WelcomeScreen(
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
                        showAdminAccessSyncIndicator = !adminPrecheckComplete,
                        viewModel = viewModel,
                    )
                }
                }
                showAdminAuth -> {
                    DramaticSpaceEntrance(
                        enabled = pageAnimationsEnabled,
                        space = SpaceEntrance.Admin,
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

        val updateCheckResult by viewModel.updateCheckState.collectAsState()
        val updateDownloadState by viewModel.updateDownloadState.collectAsState()

        LaunchedEffect(Unit) {
            if (!hasCheckedUpdate) {
                hasCheckedUpdate = true
                viewModel.checkForAppUpdates()
            }
        }
        LaunchedEffect(updateCheckResult) {
            if (updateCheckResult is UpdateCheckResult.UpdateAvailable) {
                showUpdateDialog = true
            }
        }

        val context = LocalContext.current
        
        // Properly collect StateFlow values to avoid null pointer exceptions on Android 7
        val guests by viewModel.guests.collectAsState()
        val volunteers by viewModel.volunteers.collectAsState()
        val jobs by viewModel.jobs.collectAsState()
        val jobTypeConfigs by viewModel.jobTypeConfigs.collectAsState()
        val venues by viewModel.venues.collectAsState()
        
        // Collect sync error state
        val syncError by viewModel.syncError.collectAsState()
        val showSyncErrorDialog by viewModel.showSyncErrorDialog.collectAsState()
        val isSyncing by viewModel.isSyncing.collectAsState()
        
        // Collect sync status state
        val syncStatusMessage by viewModel.syncStatusMessage.collectAsState()
        val showSyncStatusDialog by viewModel.showSyncStatusDialog.collectAsState()

        // Collect announcement state
        val pendingAnnouncements by viewModel.pendingAnnouncements.collectAsState()
        val showSendAnnouncementDialog by viewModel.showSendAnnouncementDialog.collectAsState()
        val isAnnouncementSending by viewModel.isAnnouncementSending.collectAsState()

        // Admin session: auto-return to welcome after idle timeout or after screen was turned off (sleep).
        val adminSurfaceActive = !showWelcome && !showAdminAuth && !showTicketCheck && !showPos
        val endAdminSession by rememberUpdatedState {
            showWelcome = true
            showAdminAuth = false
            showTicketCheck = false
            showPos = false
            selectedTab = 0
            showJobTypeManagement = false
            showVenueManagement = false
            showSalesSheetItemManagement = false
            showGuestFormManagement = false
            showPosAccountingReport = false
        }
        val adminSessionHost = getAdminSessionHost(platformContext)
        DisposableEffect(adminSurfaceActive, adminSessionHost) {
            val host = adminSessionHost ?: return@DisposableEffect onDispose { }
            if (adminSurfaceActive) {
                host.adminSessionWatchdog.monitoring = true
                host.adminSessionWatchdog.lastInteractionElapsedMs.set(SystemClock.elapsedRealtime())
                host.adminSessionAutoLogout = { endAdminSession() }
            } else {
                host.adminSessionWatchdog.stopMonitoring()
                host.adminSessionAutoLogout = null
            }
            onDispose {
                host.adminSessionWatchdog.stopMonitoring()
                host.adminSessionAutoLogout = null
            }
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
        LaunchedEffect(adminSurfaceActive, adminSessionHost) {
            if (!adminSurfaceActive || adminSessionHost == null) return@LaunchedEffect
            val host = adminSessionHost
            while (true) {
                delay(15_000L)
                val idleMs = SystemClock.elapsedRealtime() - host.adminSessionWatchdog.lastInteractionElapsedMs.get()
                if (host.adminSessionWatchdog.consumeLogoutAfterSleepIfPending() ||
                    idleMs >= ADMIN_SESSION_IDLE_TIMEOUT_MS
                ) {
                    endAdminSession()
                    break
                }
            }
        }
        
        // State for device time error
        val showDeviceTimeErrorDialog = remember { mutableStateOf(false) }
        
        // State to track if device was sleeping when sync error occurred
        val wasDeviceSleeping = remember { mutableStateOf(false) }
        val wasInBackground = remember { mutableStateOf(false) }
        val lastResumeUpdateCheckMs = remember { mutableStateOf(0L) }
        
        // Detect app lifecycle changes to track when app resumes from background/sleep
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_PAUSE -> {
                        wasInBackground.value = true
                    }
                    Lifecycle.Event.ON_RESUME -> {
                        // App just resumed from background/sleep
                        if (wasInBackground.value) {
                            println("📱 App resumed from background/sleep")
                            val now = SystemClock.elapsedRealtime()
                            val canCheckUpdate = now - lastResumeUpdateCheckMs.value >= 15_000L
                            if (canCheckUpdate) {
                                lastResumeUpdateCheckMs.value = now
                                viewModel.checkForAppUpdates()
                            }
                            // If there's a sync error when resuming, mark that device was sleeping
                            if (syncError != null) {
                                wasDeviceSleeping.value = true
                            }
                        }
                        wasInBackground.value = false
                    }
                    else -> {}
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
            }
        }
        
        // Reset wasDeviceSleeping when sync error is cleared
        LaunchedEffect(syncError) {
            if (syncError == null) {
                wasDeviceSleeping.value = false
            }
        }
        
        // Detect device time errors
        LaunchedEffect(syncError) {
            if (syncError != null && com.eventmanager.app.ui.components.isDeviceTimeError(syncError)) {
                showDeviceTimeErrorDialog.value = true
            }
        }

        if (showPos) {
            DramaticSpaceEntrance(
                enabled = pageAnimationsEnabled,
                space = SpaceEntrance.Pos,
            ) {
                if (!skipStartupSync) {
                    DeferredUntilSpaceEntranceSettled {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            println("App started - triggering initial download-only full sync...")
                            try {
                                viewModel.performAutomaticFullSyncIfNeeded()
                            } catch (e: Exception) {
                                println("❌ Sync error: ${e.message}")
                                e.printStackTrace()
                            }
                        }
                    }
                }
                val salesItems by viewModel.salesSheetItems.collectAsState()
                PosFlow(
                    viewModel = viewModel,
                    salesItems = salesItems,
                    volunteers = volunteers,
                    guests = guests,
                    onBack = {
                        showPos = false
                        showWelcome = true
                    },
                    onFactoryResetComplete = {
                        showPos = false
                        showWelcome = false
                        showSetupWizard = true
                    },
                )
            }
        } else if (showTicketCheck) {
            DramaticSpaceEntrance(
                enabled = pageAnimationsEnabled,
                space = SpaceEntrance.Billeterie,
            ) {
            if (!skipStartupSync) {
                DeferredUntilSpaceEntranceSettled {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        println("App started - triggering initial download-only full sync...")
                        try {
                            viewModel.performAutomaticFullSyncIfNeeded()
                        } catch (e: Exception) {
                            println("❌ Sync error: ${e.message}")
                            e.printStackTrace()
                        }
                    }
                }
            }
            var billeterieSection by rememberSaveable { mutableStateOf("home") }
            var billeterieScannerReturnSection by rememberSaveable { mutableStateOf("home") }
            var showBilleterieSettings by rememberSaveable { mutableStateOf(false) }
            val billeterieDashboardScrollState = rememberScrollState(0)
            val billeterieListContext = LocalContext.current
            val useWideBilleterieHome = isLargeTablet()

            DeferredUntilSpaceEntranceSettled(key = showTicketCheck) {
                if (!showTicketCheck) return@DeferredUntilSpaceEntranceSettled
                // Same background auto-sync as admin (interval from Settings); ensure loop is running after Billeterie entry.
                viewModel.updateSyncInterval()
                viewModel.syncGuestsWithTargetedUpdates()
                if (settingsManager.isPeopleCounterVisible()) {
                    viewModel.refreshVenuesForPeopleCounterQuietly()
                }
            }

            LaunchedEffect(billeterieSection) {
                if (billeterieSection != "home") {
                    showBilleterieSettings = false
                }
            }

            BackHandler {
                when {
                    showBilleterieSettings -> showBilleterieSettings = false
                    billeterieSection == "scanner" -> billeterieSection = billeterieScannerReturnSection
                    billeterieSection == "guests" -> billeterieSection = "home"
                    billeterieSection == "pos" -> performPosFlowExit(viewModel) {
                        billeterieSection = "home"
                    }
                    else -> {
                        showTicketCheck = false
                        showWelcome = true
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                AppBackgroundAnimation(
                    style = billeterieBackgroundAnimationStyle,
                    opacity = billeterieBackgroundAnimationOpacity,
                    settingsManager = settingsManager,
                )

                when (billeterieSection) {
                    "home" -> {
                        if (showBilleterieSettings) {
                            BilleterieSettingsScreen(
                                viewModel = viewModel,
                                onBack = { showBilleterieSettings = false },
                                onFactoryResetComplete = {
                                    showBilleterieSettings = false
                                    showTicketCheck = false
                                    showWelcome = false
                                    showSetupWizard = true
                                },
                            )
                        } else if (useWideBilleterieHome) {
                            WideBilleterieHomeScreen(
                                guests = guests,
                                viewModel = viewModel,
                                onBack = {
                                    showTicketCheck = false
                                    showWelcome = true
                                },
                                onOpenGuestList = { billeterieSection = "guests" },
                                onOpenScanner = {
                                    billeterieScannerReturnSection = "home"
                                    billeterieSection = "scanner"
                                },
                                onOpenPos = { billeterieSection = "pos" },
                                onOpenSettings = { showBilleterieSettings = true },
                                hoverEnabled = false,
                            )
                        } else {
                            BilleterieHomeScreen(
                                guests = guests,
                                repository = viewModel.repository,
                                viewModel = viewModel,
                                dashboardScrollState = billeterieDashboardScrollState,
                                onBack = {
                                    showTicketCheck = false
                                    showWelcome = true
                                },
                                onOpenGuestList = { billeterieSection = "guests" },
                                onOpenScanner = {
                                    billeterieScannerReturnSection = "home"
                                    billeterieSection = "scanner"
                                },
                                onOpenPos = { billeterieSection = "pos" },
                                onOpenSettings = { showBilleterieSettings = true }
                            )
                        }
                    }
                    "pos" -> {
                        val salesItems by viewModel.salesSheetItems.collectAsState()
                        PosFlow(
                            viewModel = viewModel,
                            salesItems = salesItems,
                            volunteers = volunteers,
                            guests = guests,
                            onBack = { billeterieSection = "home" },
                            onFactoryResetComplete = {
                                showBilleterieSettings = false
                                showTicketCheck = false
                                showWelcome = false
                                showSetupWizard = true
                            },
                        )
                    }
                    "scanner" -> {
                        BilleterieScannerScreen(
                            volunteers = volunteers,
                            guests = guests,
                            jobs = jobs,
                            jobTypeConfigs = jobTypeConfigs,
                            onBack = { billeterieSection = billeterieScannerReturnSection },
                            onConfirmEntry = { job, selectedInvites ->
                                viewModel.markBenefitAsUsed(job, selectedInvites)
                            },
                            viewModel = viewModel,
                        )
                    }
                    else -> {
                        val dockSyncPillToCorner =
                            isFirebaseOrgSwitcherVisible(viewModel) && !isTablet()
                        Scaffold(
                            containerColor = if (BackgroundAnimationStyle.isEnabled(billeterieBackgroundAnimationStyle)) {
                                Color.Transparent
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            topBar = {
                                CenterAlignedTopAppBar(
                                    title = {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                        ) {
                                            Text(
                                                text = billeterieListContext.getString(R.string.nav_guests),
                                                style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f, fill = false),
                                            )
                                            FirebaseOrgSwitcher(
                                                viewModel = viewModel,
                                                placement = FirebaseOrgSwitcherPlacement.TopBarTitleEnd,
                                            )
                                        }
                                    },
                                    navigationIcon = {
                                        IconButton(onClick = { billeterieSection = "home" }) {
                                            Icon(
                                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                                contentDescription = billeterieListContext.getString(R.string.setup_back)
                                            )
                                        }
                                    },
                                    actions = {
                                        if (!dockSyncPillToCorner) {
                                            SyncStatusPill(viewModel = viewModel)
                                        }
                                    },
                                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                        containerColor = Color.Transparent,
                                        scrolledContainerColor = Color.Transparent,
                                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                                        actionIconContentColor = MaterialTheme.colorScheme.onSurface
                                    )
                                )
                            }
                        ) { innerPadding ->
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                            ) {
                                GuestListScreenWithViewModel(viewModel, readOnly = true)
                                FloatingActionButton(
                                    onClick = {
                                        billeterieScannerReturnSection = "guests"
                                        billeterieSection = "scanner"
                                    },
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .padding(16.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.QrCodeScanner,
                                        contentDescription = billeterieListContext.getString(R.string.billeterie_button_scanner)
                                    )
                                }
                                if (dockSyncPillToCorner) {
                                    SyncStatusPill(
                                        viewModel = viewModel,
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(16.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
            }
        } else {
        DramaticSpaceEntrance(
            enabled = pageAnimationsEnabled,
            space = SpaceEntrance.Admin,
        ) {
        if (!skipStartupSync) {
            DeferredUntilSpaceEntranceSettled {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    println("App started - triggering initial download-only full sync...")
                    try {
                        viewModel.performAutomaticFullSyncIfNeeded()
                    } catch (e: Exception) {
                        println("❌ Sync error: ${e.message}")
                        e.printStackTrace()
                    }
                }
            }
        }
        // Defer sync operations on tab switch to allow instant UI response
        // OPTIMIZED: Syncs are triggered 250ms after tab change to ensure animation completes first
        LaunchedEffect(selectedTab) {
            // Skip sync on initial load (handled by initial sync above)
            if (selectedTab == previousTab) return@LaunchedEffect
            
            // Delay sync until after animation completes (180ms animation + buffer)
            // This prevents sync-related state updates from causing jank during transition
            kotlinx.coroutines.delay(250)
            
            println("Tab changed from $previousTab to $selectedTab - triggering deferred targeted sync")
            when (selectedTab) {
                0 -> {
                    viewModel.syncGuestsWithTargetedUpdates() // Enter Dashboard
                    if (settingsManager.isPeopleCounterVisible()) {
                        viewModel.refreshVenuesForPeopleCounterQuietly()
                    }
                }
                1 -> viewModel.syncGuestsWithTargetedUpdates() // Enter Guest List
                2 -> viewModel.syncVolunteersWithTargetedUpdates() // Enter Volunteers
                3 -> viewModel.syncJobsWithTargetedUpdates() // Enter Jobs
                4 -> {
                    // Benefits screen: stagger syncs slightly to reduce concurrent load
                    viewModel.syncJobsWithTargetedUpdates()
                    kotlinx.coroutines.delay(50)
                    viewModel.syncVolunteersWithTargetedUpdates()
                    kotlinx.coroutines.delay(50)
                    viewModel.syncJobTypesWithTargetedUpdates()
                } // Enter Benefits
                5 -> {
                    // Settings screen: keep management data fresh, including announcements on venues
                    viewModel.syncJobTypesWithTargetedUpdates()
                    kotlinx.coroutines.delay(50)
                    viewModel.syncVenuesWithTargetedUpdates()
                } // Enter Settings
            }
        }

        Scaffold(
                bottomBar = {
                    // Modern bottom navigation bar with horizontal scrolling on phones
                    if (!isTablet()) {
                        // Horizontal scrolling navigation for phones
                        val scrollState = rememberScrollState()
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(scrollState)
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = RoundedCornerShape(16.dp)
                                )
                                .padding(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val navContext = LocalContext.current
                            val tabs = listOf(
                                navContext.getString(R.string.nav_dashboard) to Icons.Default.Home,
                                navContext.getString(R.string.nav_guests) to Icons.Default.Person,
                                navContext.getString(R.string.nav_volunteers) to Icons.Default.Group,
                                navContext.getString(R.string.nav_shifts) to Icons.Default.Build,
                                navContext.getString(R.string.nav_benefits) to Icons.Default.Star,
                                navContext.getString(R.string.nav_settings) to Icons.Default.Settings
                            )
                            
                            tabs.forEachIndexed { index, (title, icon) ->
                                Card(
                                    modifier = Modifier
                                        .width(100.dp)
                                        .clickable {
                                            if (selectedTab != index) {
                                                // Close any open settings dialogs when switching tabs
                                                showJobTypeManagement = false
                                                showVenueManagement = false
                                                showSalesSheetItemManagement = false
                                                showGuestFormManagement = false
                                                showPosAccountingReport = false
                                                // Very subtle haptic feedback for page change
                                                performSubtleHaptic(vibrator)
                                            }
                                            previousTab = selectedTab
                                            selectedTab = index
                                        },
                                    shape = RoundedCornerShape(12.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (selectedTab == index)
                                            MaterialTheme.colorScheme.primaryContainer
                                        else
                                            MaterialTheme.colorScheme.surfaceVariant
                                    ),
                                    elevation = CardDefaults.cardElevation(
                                        defaultElevation = if (selectedTab == index) 4.dp else 1.dp
                                    )
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 8.dp, vertical = 8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.Center
                                    ) {
                                        val scale by animateFloatAsState(
                                            targetValue = if (selectedTab == index && pageAnimationsEnabled) 1.1f else 1.0f,
                                            animationSpec = if (pageAnimationsEnabled) spring(stiffness = Spring.StiffnessMedium) else spring(stiffness = Spring.StiffnessHigh),
                                            label = "bottom_icon_scale"
                                        )
                                        Icon(
                                            icon,
                                            contentDescription = title,
                                            modifier = Modifier.size(20.dp).graphicsLayer(scaleX = scale, scaleY = scale),
                                            tint = if (selectedTab == index) 
                                                MaterialTheme.colorScheme.onPrimaryContainer 
                                            else 
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                            color = if (selectedTab == index) 
                                                MaterialTheme.colorScheme.onPrimaryContainer 
                                            else 
                                                MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        // Regular navigation bar for tablets
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            contentColor = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 2.dp)
                                .height(62.dp)
                        ) {
                            val navContextTablet = LocalContext.current
                            val tabs = listOf(
                                navContextTablet.getString(R.string.nav_dashboard) to Icons.Default.Home,
                                navContextTablet.getString(R.string.nav_guests) to Icons.Default.Person,
                                navContextTablet.getString(R.string.nav_volunteers) to Icons.Default.Group,
                                navContextTablet.getString(R.string.nav_shifts) to Icons.Default.Build,
                                navContextTablet.getString(R.string.nav_benefits) to Icons.Default.Star,
                                navContextTablet.getString(R.string.nav_settings) to Icons.Default.Settings
                            )
                            
                            tabs.forEachIndexed { index, (title, icon) ->
                                NavigationBarItem(
                                    selected = selectedTab == index,
                                    onClick = {
                                        if (selectedTab != index) {
                                            // Close any open settings dialogs when switching tabs
                                            showJobTypeManagement = false
                                            showVenueManagement = false
                                            showSalesSheetItemManagement = false
                                            showGuestFormManagement = false
                                            showPosAccountingReport = false
                                            // Very subtle haptic feedback for page change
                                            performSubtleHaptic(vibrator)
                                        }
                                        previousTab = selectedTab
                                        selectedTab = index
                                    },
                                    icon = {
                                        val scale by animateFloatAsState(
                                            targetValue = if (selectedTab == index && pageAnimationsEnabled) 1.1f else 1.0f,
                                            animationSpec = if (pageAnimationsEnabled) spring(stiffness = Spring.StiffnessMedium) else spring(stiffness = Spring.StiffnessHigh),
                                            label = "bottom_icon_scale_tablet"
                                        )
                                        Icon(
                                            icon,
                                            contentDescription = title,
                                            modifier = Modifier.size(20.dp).graphicsLayer(scaleX = scale, scaleY = scale)
                                        )
                                    },
                                    label = {
                                        Text(
                                            text = title,
                                            style = MaterialTheme.typography.labelSmall
                                        )
                                    }
                                )
                            }
                        }
                    }
                }
            ) { innerPadding ->
                // Main content with modern padding, sync widget, and swipe gestures
                val isPhone = !isTablet()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding)
                        .padding(
                            horizontal = if (isPhone) 8.dp else 16.dp, 
                            vertical = if (isPhone) 4.dp else 8.dp
                        )
                ) {
                    // Root admin: system back would otherwise finish the activity; return to welcome instead
                    // (same as dashboard logout). Job/venue management screens register their own BackHandler
                    // after this and take precedence; open dialogs compose later and dismiss first.
                    BackHandler {
                        showWelcome = true
                        showAdminAuth = false
                        showTicketCheck = false
                        showPos = false
                        selectedTab = 0
                        showJobTypeManagement = false
                        showVenueManagement = false
                        showSalesSheetItemManagement = false
                        showGuestFormManagement = false
                        showPosAccountingReport = false
                    }
                    // Animated background
                    AppBackgroundAnimation(
                        style = backgroundAnimationStyle,
                        opacity = backgroundAnimationOpacity,
                        settingsManager = settingsManager,
                    )
                    
                    // Snow Animation (December 22-25)
                    SnowAnimation(
                        enabled = settingsManager.isSeasonalFunEnabled()
                    )
                    
                    // Fireworks Animation (December 31 - January 1)
                    FireworksAnimation(
                        enabled = settingsManager.isSeasonalFunEnabled()
                    )
                    
                    // Valentine's Day Animation (February 14)
                    ValentineAnimation(
                        enabled = settingsManager.isSeasonalFunEnabled()
                    )
                    
                    // Workers' Day Animation (May 1)
                    WorkersDayAnimation(
                        enabled = settingsManager.isSeasonalFunEnabled()
                    )
                    
                    // Pride Animation (June 27)
                    PrideAnimation(
                        enabled = settingsManager.isSeasonalFunEnabled()
                    )
                    
                    // Pride Day Themed Square Overlay (June 28)
                    run {
                        val calendar = java.util.Calendar.getInstance()
                        val month = calendar.get(java.util.Calendar.MONTH)
                        val day = calendar.get(java.util.Calendar.DAY_OF_MONTH)
                        val isPrideDay = month == java.util.Calendar.JUNE && day == 28 && settingsManager.isSeasonalFunEnabled()
                        
                        if (isPrideDay) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(3.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.surface,
                                        shape = RoundedCornerShape(16.dp)
                                    )
                            )
                        }
                    }
                    
                    // Main content
                    // Capture vibrator for use in suspend context
                    val capturedVibrator = vibrator
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(Unit) {
                        var startX = 0f
                        var hasSwiped = false
                        detectDragGestures(
                            onDragStart = { offset ->
                                startX = offset.x
                                hasSwiped = false
                            },
                            onDragEnd = { 
                                // Reset startX after gesture completes
                                startX = 0f
                                hasSwiped = false
                            },
                            onDrag = { change, _ ->
                                val deltaX = change.position.x - startX
                                val threshold = 100f
                                
                                if (!hasSwiped) {
                                    when {
                                        deltaX > threshold -> {
                                        // Swipe right - go to previous tab
                                        if (selectedTab > 0) {
                                                // Close any open settings dialogs when swiping to a different tab
                                                showJobTypeManagement = false
                                                showVenueManagement = false
                                                showSalesSheetItemManagement = false
                                                showGuestFormManagement = false
                                                showPosAccountingReport = false
                                                // Very subtle haptic feedback for page change
                                                performSubtleHaptic(capturedVibrator)
                                                previousTab = selectedTab
                                                selectedTab = selectedTab - 1
                                                hasSwiped = true
                                        }
                                        }
                                        deltaX < -threshold -> {
                                        // Swipe left - go to next tab
                                        if (selectedTab < 5) {
                                                // Close any open settings dialogs when swiping to a different tab
                                                showJobTypeManagement = false
                                                showVenueManagement = false
                                                showSalesSheetItemManagement = false
                                                showGuestFormManagement = false
                                                showPosAccountingReport = false
                                                // Very subtle haptic feedback for page change
                                                performSubtleHaptic(capturedVibrator)
                                                previousTab = selectedTab
                                                selectedTab = selectedTab + 1
                                                hasSwiped = true
                                        }
                                        }
                                    }
                                }
                            }
                        )
                    }
            ) {
                // Tab Content with optimized switching
                // Use key() to maintain screen identity and prevent unnecessary recreation
                val goingLeft = selectedTab > previousTab
                
                // Create a composite screen state for animations
                // Format: "tab:{tabIndex}" for main tabs, "management:jobtype" or "management:venue" for management screens
                val currentScreenState = when {
                    showJobTypeManagement -> "management:jobtype"
                    showVenueManagement -> "management:venue"
                    showSalesSheetItemManagement -> "management:sales-items"
                    showGuestFormManagement -> "management:guest-forms"
                    showPosAccountingReport -> "management:pos-report"
                    else -> "tab:$selectedTab"
                }
                
if (pageAnimationsEnabled) {
                    AnimatedContent(
                        targetState = currentScreenState,
                        transitionSpec = {
                            // OPTIMIZED: Shorter duration (180ms) feels snappier while remaining smooth
                            // Fade starts faster (120ms) so content is visible sooner
                            // Exit offset reduced to minimize visual complexity
                            val slideDuration = 180
                            val fadeDuration = 120
                            when {
                                // Opening management screen - slide in from right
                                targetState.startsWith("management:") && initialState.startsWith("tab:") -> {
                                    val enter = slideInHorizontally(
                                        animationSpec = tween(durationMillis = slideDuration, easing = FastOutSlowInEasing),
                                        initialOffsetX = { fullWidth -> fullWidth }
                                    ) + fadeIn(animationSpec = tween(durationMillis = fadeDuration))
                                    val exit = slideOutHorizontally(
                                        animationSpec = tween(durationMillis = slideDuration, easing = FastOutSlowInEasing),
                                        targetOffsetX = { fullWidth -> -fullWidth / 4 }
                                    ) + fadeOut(animationSpec = tween(durationMillis = fadeDuration))
                                    (enter togetherWith exit).using(SizeTransform(clip = false))
                                }
                                // Closing management screen - slide out to right
                                targetState.startsWith("tab:") && initialState.startsWith("management:") -> {
                                    val enter = slideInHorizontally(
                                        animationSpec = tween(durationMillis = slideDuration, easing = FastOutSlowInEasing),
                                        initialOffsetX = { fullWidth -> -fullWidth / 4 }
                                    ) + fadeIn(animationSpec = tween(durationMillis = fadeDuration))
                                    val exit = slideOutHorizontally(
                                        animationSpec = tween(durationMillis = slideDuration, easing = FastOutSlowInEasing),
                                        targetOffsetX = { fullWidth -> fullWidth }
                                    ) + fadeOut(animationSpec = tween(durationMillis = fadeDuration))
                                    (enter togetherWith exit).using(SizeTransform(clip = false))
                                }
                                // Regular tab transitions - optimized for smoothness
                                else -> {
                                    val enter = slideInHorizontally(
                                        animationSpec = tween(durationMillis = slideDuration, easing = FastOutSlowInEasing),
                                        initialOffsetX = { fullWidth -> if (goingLeft) fullWidth else -fullWidth }
                                    ) + fadeIn(animationSpec = tween(durationMillis = fadeDuration))
                                    val exit = slideOutHorizontally(
                                        animationSpec = tween(durationMillis = slideDuration, easing = FastOutSlowInEasing),
                                        targetOffsetX = { fullWidth -> if (goingLeft) -fullWidth / 4 else fullWidth / 4 }
                                    ) + fadeOut(animationSpec = tween(durationMillis = fadeDuration))
                                    (enter togetherWith exit).using(SizeTransform(clip = false))
                                }
                            }
                        },
                        label = "page_transition"
                    ) { screenState: String ->
                        // Use key() to maintain screen identity across recompositions
                        when {
                            screenState == "management:jobtype" -> key("job_type_management") {
                                JobTypeManagementScreenWithViewModel(viewModel) {
                                    println("Exiting Job Type Management - triggering job types sync")
                                    viewModel.syncJobTypesOnly()
                                    showJobTypeManagement = false
                                }
                            }
                            screenState == "management:venue" -> key("venue_management") {
                                VenueManagementScreenWithViewModel(viewModel) {
                                    println("Exiting Venue Management")
                                    showVenueManagement = false
                                }
                            }
                            screenState == "management:sales-items" -> key("sales_sheet_item_management") {
                                SalesSheetItemManagementScreenWithViewModel(viewModel) {
                                    println("Exiting Sales Sheet Item Management")
                                    showSalesSheetItemManagement = false
                                }
                            }
                            screenState == "management:guest-forms" -> key("guest_form_management") {
                                GuestFormManagementScreenWithViewModel(viewModel) {
                                    showGuestFormManagement = false
                                }
                            }
                            screenState == "management:pos-report" -> key("pos_accounting_report") {
                                val accountTransfers by viewModel.accountTransfers.collectAsState()
                                val salesSheetItems by viewModel.salesSheetItems.collectAsState()
                                val venues by viewModel.venues.collectAsState()
                                PosAccountingReportScreen(
                                    transfers = accountTransfers,
                                    salesItems = salesSheetItems,
                                    venues = venues,
                                    settingsManager = settingsManager,
                                    isPhone = !isTablet(),
                                    onBack = { showPosAccountingReport = false },
                                )
                            }
                            screenState == "tab:0" -> key("dashboard") {
                                DashboardScreenWithViewModel(
                                    viewModel = viewModel,
                                    onAdminRequireReauth = { showAdminAuth = true },
                                    onLogout = {
                                        showWelcome = true
                                        showAdminAuth = false
                                        showTicketCheck = false
                                        showPos = false
                                        selectedTab = 0
                                    },
                                    onOpenPosReport = { showPosAccountingReport = true },
                                )
                            }
                            screenState == "tab:1" -> key("guest_list") { GuestListScreenWithViewModel(viewModel) }
                            screenState == "tab:2" -> key("volunteers") { VolunteerScreenWithViewModel(viewModel) }
                            screenState == "tab:3" -> key("jobs") { JobTrackingScreenWithViewModel(viewModel) }
                            screenState == "tab:4" -> key("benefits") { BenefitsScreenWithViewModel(viewModel) }
                            screenState == "tab:5" -> key("settings") {
                                SettingsScreen(
                                    viewModel = viewModel,
                                    onNavigateToJobTypeManagement = { 
                                        println("Navigating to Job Type Management - triggering job types sync")
                                        viewModel.syncJobTypesOnly()
                                        showJobTypeManagement = true 
                                    },
                                    onNavigateToVenueManagement = { 
                                        println("Navigating to Venue Management")
                                        viewModel.syncVenuesWithTargetedUpdates()
                                        showVenueManagement = true 
                                    },
                                    onNavigateToSalesSheetItemManagement = {
                                        println("Navigating to Sales Sheet Item Management")
                                        viewModel.syncSalesSheetItemsWithTargetedUpdates()
                                        showSalesSheetItemManagement = true
                                    },
                                    onNavigateToGuestFormManagement = {
                                        viewModel.refreshGuestFormsFromRemote()
                                        showGuestFormManagement = true
                                    },
                                    onFactoryResetComplete = {
                                        showJobTypeManagement = false
                                        showVenueManagement = false
                                        showSalesSheetItemManagement = false
                                        showGuestFormManagement = false
                                        showPosAccountingReport = false
                                        showWelcome = false
                                        showAdminAuth = false
                                        showTicketCheck = false
                                        selectedTab = 0
                                        showSetupWizard = true
                                    },
                                )
                            }
                        }
                    }
                } else {
                    // Without animations, use key() for state preservation
                    when {
                        showJobTypeManagement -> key("job_type_management") {
                            JobTypeManagementScreenWithViewModel(viewModel) {
                                println("Exiting Job Type Management - triggering job types sync")
                                viewModel.syncJobTypesOnly()
                                showJobTypeManagement = false
                            }
                        }
                        showVenueManagement -> key("venue_management") {
                            VenueManagementScreenWithViewModel(viewModel) {
                                println("Exiting Venue Management")
                                showVenueManagement = false
                            }
                        }
                        showSalesSheetItemManagement -> key("sales_sheet_item_management") {
                            SalesSheetItemManagementScreenWithViewModel(viewModel) {
                                println("Exiting Sales Sheet Item Management")
                                showSalesSheetItemManagement = false
                            }
                        }
                        showGuestFormManagement -> key("guest_form_management") {
                            GuestFormManagementScreenWithViewModel(viewModel) {
                                showGuestFormManagement = false
                            }
                        }
                        showPosAccountingReport -> key("pos_accounting_report") {
                            val accountTransfers by viewModel.accountTransfers.collectAsState()
                            val salesSheetItems by viewModel.salesSheetItems.collectAsState()
                            val venues by viewModel.venues.collectAsState()
                            PosAccountingReportScreen(
                                transfers = accountTransfers,
                                salesItems = salesSheetItems,
                                venues = venues,
                                settingsManager = settingsManager,
                                isPhone = !isTablet(),
                                onBack = { showPosAccountingReport = false },
                            )
                        }
                        selectedTab == 0 -> key("dashboard") {
                            DashboardScreenWithViewModel(
                                viewModel = viewModel,
                                onAdminRequireReauth = { showAdminAuth = true },
                                onLogout = {
                                    showWelcome = true
                                    showAdminAuth = false
                                    showTicketCheck = false
                                    showPos = false
                                    selectedTab = 0
                                },
                                onOpenPosReport = { showPosAccountingReport = true },
                            )
                        }
                        selectedTab == 1 -> key("guest_list") { GuestListScreenWithViewModel(viewModel) }
                        selectedTab == 2 -> key("volunteers") { VolunteerScreenWithViewModel(viewModel) }
                        selectedTab == 3 -> key("jobs") { JobTrackingScreenWithViewModel(viewModel) }
                        selectedTab == 4 -> key("benefits") { BenefitsScreenWithViewModel(viewModel) }
                        selectedTab == 5 -> key("settings") {
                            SettingsScreen(
                                viewModel = viewModel,
                                onNavigateToJobTypeManagement = { 
                                    println("Navigating to Job Type Management - triggering job types sync")
                                    viewModel.syncJobTypesOnly()
                                    showJobTypeManagement = true 
                                },
                                onNavigateToVenueManagement = { 
                                    println("Navigating to Venue Management")
                                    viewModel.syncVenuesWithTargetedUpdates()
                                    showVenueManagement = true 
                                },
                                onNavigateToSalesSheetItemManagement = {
                                    println("Navigating to Sales Sheet Item Management")
                                    viewModel.syncSalesSheetItemsWithTargetedUpdates()
                                    showSalesSheetItemManagement = true
                                },
                                onNavigateToGuestFormManagement = {
                                    viewModel.refreshGuestFormsFromRemote()
                                    showGuestFormManagement = true
                                },
                                onFactoryResetComplete = {
                                    showJobTypeManagement = false
                                    showVenueManagement = false
                                    showSalesSheetItemManagement = false
                                    showGuestFormManagement = false
                                    showPosAccountingReport = false
                                    showWelcome = false
                                    showAdminAuth = false
                                    showTicketCheck = false
                                    selectedTab = 0
                                    showSetupWizard = true
                                },
                            )
                        }
                    }
                }
                
                    // QR Scanner button in bottom left corner above navigation bar
                    QRScannerButton(
                        onClick = { showQRScanner = true },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(bottom = 8.dp, start = 8.dp)
                    )
                    
                    // Sync status widget in bottom right corner above navigation bar
                    SyncStatusWidget(
                        viewModel = viewModel,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(bottom = 8.dp, end = 8.dp)
                    )
                }
            }
        }
        }
        }

            BackendMigrationUiHost(
                viewModel = viewModel,
                settingsManager = appSettingsManager,
                platformContext = platformContext,
                onRequestFirebaseSignIn = { onResult ->
                    val auth = followFirebaseAuth
                    if (auth == null) {
                        onResult(com.eventmanager.app.data.remote.FirebaseAuthResult.Error("Firebase Auth unavailable"))
                    } else {
                        pendingFollowSignInResult = onResult
                        followAuthLauncher.launch(auth.getSignInIntent())
                    }
                },
            )

            GuestFormArrivalToastHost(viewModel = viewModel)
            
        // QR Scanner Dialog
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
        
        // Volunteer Benefits Panel
        showVolunteerBenefits?.let { volunteer ->
            // Memoize to prevent unnecessary recompositions
            val benefitSettingsManager = remember { settingsManagerFor(appContext) }
            val offsetHours = remember { benefitSettingsManager.getDateChangeOffsetHours() }
            val memoizedBenefitStatus = remember(volunteer.id, jobs, jobTypeConfigs, offsetHours) {
                com.eventmanager.app.data.models.BenefitCalculator.calculateVolunteerBenefitStatus(
                    volunteer = volunteer,
                    jobs = jobs,
                    jobTypeConfigs = jobTypeConfigs,
                    offsetHours = offsetHours
                )
            }
            val memoizedVolunteerJobs = remember(volunteer.id, jobs) {
                jobs.filter { it.volunteerId == volunteer.id }
            }

            val scanFlowTablet = isTablet()
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { showVolunteerBenefits = null },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (scanFlowTablet) Modifier.padding(getTabletDialogScreenEdgeInset())
                            else Modifier
                        )
                ) {
                    VolunteerBenefitsPanel(
                        modifier = Modifier.fillMaxSize(),
                        volunteer = volunteer,
                        volunteerBenefitStatus = memoizedBenefitStatus,
                        volunteerJobs = memoizedVolunteerJobs,
                        venues = venues,
                        jobTypeConfigs = jobTypeConfigs,
                        onClose = { showVolunteerBenefits = null },
                        onConfirmEntry = { job, selectedInvites -> viewModel.markBenefitAsUsed(job, selectedInvites) },
                        onAssignNfcUid = { updatedVolunteer, uid ->
                            viewModel.updateVolunteer(
                                updatedVolunteer.copy(
                                    nfcCardUid = uid,
                                    lastModified = System.currentTimeMillis()
                                )
                            )
                            showVolunteerBenefits = updatedVolunteer.copy(nfcCardUid = uid)
                        },
                        viewModel = viewModel
                    )
                }
            }
        }

        showScannedGuestDetail?.let { guest ->
            val scanFlowTablet = isTablet()
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { showScannedGuestDetail = null },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (scanFlowTablet) Modifier.padding(getTabletDialogScreenEdgeInset())
                            else Modifier
                        )
                ) {
                    GuestDetailPanel(
                        modifier = Modifier.fillMaxSize(),
                        guest = guest,
                        venues = venues,
                        onEdit = { updated ->
                            viewModel.updateGuest(updated)
                            showScannedGuestDetail = updated
                        },
                        onAssignNfcUid = { updatedGuest, uid ->
                            val withUid = updatedGuest.copy(
                                nfcCardUid = uid,
                                lastModified = System.currentTimeMillis()
                            )
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
        }
        
        // Sync Error Dialog
        SyncErrorDialog(
            isVisible = showSyncErrorDialog && !showDeviceTimeErrorDialog.value,
            onDismiss = { viewModel.dismissSyncErrorDialog() },
            onRetry = { viewModel.performFullSync() },
            errorMessage = syncError ?: "",
            onDontTellTodayChanged = { suppress ->
                if (suppress) {
                    viewModel.setSyncErrorSuppressedToday()
                }
                viewModel.dismissSyncErrorDialog()
            },
            isSyncing = isSyncing,
            animationsEnabled = pageAnimationsEnabled,
            wasDeviceSleeping = wasDeviceSleeping.value
        )
        
        // Device Time Error Dialog
        DeviceTimeErrorDialog(
            isVisible = showDeviceTimeErrorDialog.value,
            onDismiss = {
                showDeviceTimeErrorDialog.value = false
                viewModel.dismissSyncErrorDialog()
            },
            onOpenSettings = {
                // Open system settings for date & time
                val intent = Intent(Settings.ACTION_DATE_SETTINGS)
                try {
                    appContext.startActivity(intent)
                } catch (e: Exception) {
                    // Fallback to general settings if date settings not available
                    val fallbackIntent = Intent(Settings.ACTION_SETTINGS)
                    try {
                        appContext.startActivity(fallbackIntent)
                    } catch (ex: Exception) {
                        println("Could not open settings: ${ex.message}")
                    }
                }
            },
            onDontTellTodayChanged = { suppress ->
                if (suppress) {
                    viewModel.setSyncErrorSuppressedToday()
                }
            }
        )
        
        // Sync Status Dialog
        SyncStatusDialog(
            isVisible = showSyncStatusDialog,
            onDismiss = { viewModel.dismissSyncStatusDialog() },
            statusMessage = syncStatusMessage
        )

        // Send Announcement Dialog
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

        // Announcement Receive Popup
        val currentAnnouncement = pendingAnnouncements.firstOrNull()
        if (currentAnnouncement != null) {
            AnnouncementPopup(
                announcement = currentAnnouncement,
                onDismiss = { viewModel.dismissCurrentAnnouncement() }
            )
        }

        if (!showAdminAuth && showUpdateDialog && updateCheckResult is UpdateCheckResult.UpdateAvailable) {
            val manifest = (updateCheckResult as UpdateCheckResult.UpdateAvailable).manifest
            val isRequired = (updateCheckResult as UpdateCheckResult.UpdateAvailable).isRequired
            val currentDownloadState = updateDownloadState

            when (currentDownloadState) {
                is DownloadState.Downloading -> {
                    AlertDialog(
                        onDismissRequest = { },
                        title = {
                            Text(text = appContext.getString(R.string.downloading_update))
                        },
                        text = {
                            Column {
                                LinearProgressIndicator(
                                    progress = { currentDownloadState.progress / 100f },
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = appContext.getString(R.string.download_progress, currentDownloadState.progress),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        },
                        confirmButton = {}
                    )
                }
                is DownloadState.Downloaded -> {
                    AlertDialog(
                        onDismissRequest = if (isRequired) { {} } else { { showUpdateDialog = false } },
                        properties = if (isRequired) {
                            DialogProperties(
                                dismissOnBackPress = false,
                                dismissOnClickOutside = false
                            )
                        } else {
                            DialogProperties()
                        },
                        title = {
                            Text(text = appContext.getString(R.string.download_complete))
                        },
                        text = {
                            Text(text = appContext.getString(R.string.update_available_message))
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                viewModel.installUpdate(currentDownloadState.filePath)
                                showUpdateDialog = false
                            }) {
                                Text(appContext.getString(R.string.install_update))
                            }
                        },
                        dismissButton = if (!isRequired) {
                            {
                                TextButton(onClick = { showUpdateDialog = false }) {
                                    Text(appContext.getString(R.string.later))
                                }
                            }
                        } else null
                    )
                }
                is DownloadState.Error -> {
                    AlertDialog(
                        onDismissRequest = if (isRequired) { {} } else { { showUpdateDialog = false } },
                        title = {
                            Text(text = appContext.getString(R.string.download_error_title))
                        },
                        text = {
                            Text(text = appContext.getString(R.string.download_error_message, currentDownloadState.message))
                        },
                        confirmButton = {
                            TextButton(onClick = { showUpdateDialog = false }) {
                                Text(appContext.getString(R.string.ok))
                            }
                        }
                    )
                }
                else -> {
                    AlertDialog(
                        onDismissRequest = if (isRequired) { {} } else { { showUpdateDialog = false } },
                        title = {
                            Text(text = appContext.getString(R.string.update_available_title, manifest.latestVersionName))
                        },
                        text = {
                            Text(
                                text = manifest.changelogShort
                                    ?: if (isRequired) {
                                        appContext.getString(R.string.update_required_message)
                                    } else {
                                        appContext.getString(R.string.update_available_message)
                                    }
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                val downloadUrl = manifest.downloadUrl
                                if (downloadUrl != null) {
                                    viewModel.downloadUpdate(downloadUrl)
                                } else {
                                    val targetUrl = manifest.storeUrl
                                        ?: settingsManager.getUpdateStoreUrl()
                                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl))
                                    appContext.startActivity(intent)
                                    showUpdateDialog = false
                                }
                            }) {
                                Text(appContext.getString(R.string.update_now))
                            }
                        },
                        dismissButton = {
                            if (!isRequired) {
                                TextButton(onClick = { showUpdateDialog = false }) {
                                    Text(appContext.getString(R.string.later))
                                }
                            }
                        }
                    )
                }
            }
        }
                } // end when else
            } // end when

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
        } // end database ready
    } // end setup wizard else
}

// Sync Status Widget
@Composable
fun SyncStatusWidget(
    viewModel: EventManagerViewModel,
    modifier: Modifier = Modifier,
) {
    com.eventmanager.app.ui.components.SyncStatusPill(
        viewModel = viewModel,
        modifier = modifier,
    )
}

// QR Scanner Button Widget
@Composable
fun QRScannerButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Interaction source for press feedback
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    
    // Animate scale on press
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(100),
        label = "qr_button_scale"
    )
    
    Card(
        modifier = modifier
            .padding(4.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isPressed) 4.dp else 2.dp
        )
    ) {
        Box(
            modifier = Modifier
                .padding(12.dp)
                .scale(scale),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.qrscan_icon),
                contentDescription = "Scan QR Code",
                modifier = Modifier.size(20.dp),
                colorFilter = ColorFilter.tint(
                    MaterialTheme.colorScheme.onSurfaceVariant
                )
            )
        }
    }
}

// Dashboard Screen
@Composable
fun DashboardScreenWithViewModel(
    viewModel: EventManagerViewModel,
    onLogout: () -> Unit = {},
    onOpenPosReport: () -> Unit = {},
    onAdminRequireReauth: (() -> Unit)? = null,
) {
    val guests by viewModel.guests.collectAsState()
    val volunteers by viewModel.volunteers.collectAsState()
    val jobs by viewModel.jobs.collectAsState()
    val jobTypeConfigs by viewModel.jobTypeConfigs.collectAsState()
    val venues by viewModel.venues.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val context = LocalContext.current
    val settingsManager = remember { settingsManagerFor(context) }
    
    // OPTIMIZATION: Removed redundant sync triggers
    // Initial sync is handled by LaunchedEffect(Unit) in EventManagerApp
    // Tab-change sync is handled by LaunchedEffect(selectedTab) in EventManagerApp
    // This reduces duplicate network calls and improves navigation smoothness

    DashboardScreen(
        guests = guests,
        volunteers = volunteers,
        jobs = jobs,
        venues = venues,
        jobTypeConfigs = jobTypeConfigs,
        isSyncing = isSyncing,
        lastSyncTime = settingsManager.getLastSyncTime(),
        repository = viewModel.repository,
        viewModel = viewModel,
        onLogout = onLogout,
        onOpenPosReport = onOpenPosReport,
        onAdminRequireReauth = onAdminRequireReauth,
    )
}

@Composable
fun DashboardScreen(
    guests: List<Guest>,
    volunteers: List<Volunteer>,
    jobs: List<Job>,
    venues: List<VenueEntity> = emptyList(),
    jobTypeConfigs: List<JobTypeConfig> = emptyList(),
    @Suppress("UNUSED_PARAMETER") isSyncing: Boolean = false,
    @Suppress("UNUSED_PARAMETER") lastSyncTime: Long = 0L,
    @Suppress("UNUSED_PARAMETER") repository: com.eventmanager.app.data.repository.EventManagerRepository? = null,
    viewModel: com.eventmanager.app.ui.viewmodel.EventManagerViewModel? = null,
    onLogout: () -> Unit = {},
    onOpenPosReport: () -> Unit = {},
    onAdminRequireReauth: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val platformContext = remember(context) { createPlatformContext(context) }
    val isPhone = !isTablet()
    val responsivePadding = if (isPhone) getPhonePortraitPadding() else getResponsivePadding()
    val settingsManager = remember { settingsManagerFor(context) }
    
    // State for beer animation
    var showBeerAnimation by remember { mutableStateOf(false) }
    
    // Memoize seasonal fun setting to avoid repeated reads
    val seasonalFunEnabled = remember { settingsManager.isSeasonalFunEnabled() }
    
    // Auto-hide beer animation after short duration
    LaunchedEffect(showBeerAnimation) {
        if (showBeerAnimation) {
            kotlinx.coroutines.delay(1500) // Show for 1.5 seconds
            showBeerAnimation = false
        }
    }
    
    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(responsivePadding)
        ) {
        // Visibility settings
        val isPeopleCounterVisible = settingsManager.isPeopleCounterVisible()
        val isStatisticsVisible = settingsManager.isStatisticsVisible()
        val dateChangeOffsetHours = remember { settingsManager.getDateChangeOffsetHours() }
        val guestListZone = GuestListDefaultZoneId
        val guestListEffectiveToday = rememberGuestListEffectiveToday(
            zone = guestListZone,
            offsetHours = dateChangeOffsetHours
        )

        if (viewModel != null) {
            DashboardClockCard(
                settingsManager = settingsManager,
                isPhone = isPhone,
                trailingContent = {
                    FirebaseOrgSwitcher(
                        viewModel = viewModel,
                        placement = FirebaseOrgSwitcherPlacement.DashboardClockRow,
                        onAdminRequireReauth = onAdminRequireReauth,
                    )
                },
            )
        } else {
            DashboardClockCard(settingsManager = settingsManager, isPhone = isPhone)
        }
        
        Spacer(modifier = Modifier.height(if (isPhone) 16.dp else 24.dp))

        val occupancy = remember(
            guests,
            volunteers,
            jobs,
            jobTypeConfigs,
            dateChangeOffsetHours,
            guestListEffectiveToday,
        ) {
            GuestListOccupancy.snapshot(
                guests = guests,
                volunteers = volunteers,
                jobs = jobs,
                jobTypeConfigs = jobTypeConfigs,
                currentTime = System.currentTimeMillis(),
                offsetHours = dateChangeOffsetHours,
                zone = guestListZone,
                isTemporaryOnList = { it == guestListEffectiveToday },
            )
        }
        val permanentGuestCount = occupancy.permanentGuests
        val temporaryGuestCount = occupancy.temporaryGuests
        
        // Volunteer stats: single pass through volunteers list
        val (totalVolunteers, activeVolunteersCount, inactiveVolunteersCount) = remember(volunteers, jobs) {
            var active = 0
            var inactive = 0
            val jobsByVolunteer = VolunteerActivityManager.groupJobsByVolunteerId(jobs)
            volunteers.forEach { volunteer ->
                if (VolunteerActivityManager.isVolunteerActive(volunteer, jobsByVolunteer[volunteer.id])) {
                    active++
                } else {
                    inactive++
                }
            }
            Triple(volunteers.size, active, inactive)
        }
        
        val totalPeople = occupancy.totalList
        // Move expensive calculation to background if needed
        val totalFreeDrinks = remember(volunteers, jobs, jobTypeConfigs, dateChangeOffsetHours) {
            com.eventmanager.app.data.models.BenefitCalculator.calculateTotalFreeDrinks(
                volunteers = volunteers,
                jobs = jobs,
                jobTypeConfigs = jobTypeConfigs,
                offsetHours = dateChangeOffsetHours
            )
        }
        
        // Statistics Grid - 2 columns (always visible)
        Column(
            verticalArrangement = Arrangement.spacedBy(if (isPhone) 12.dp else 16.dp)
        ) {
            // Row 1: Permanent Guests, Total Volunteers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (isPhone) 12.dp else 16.dp)
            ) {
                StatCardV2(
                    title = context.getString(R.string.permanent_guests),
                    value = permanentGuestCount.toString(),
                    icon = Icons.Default.Person,
                    modifier = Modifier.weight(1f),
                    isPhone = isPhone
                )
                
                StatCardV2(
                    title = context.getString(R.string.volunteers_total),
                    value = totalVolunteers.toString(),
                    icon = Icons.Default.Group,
                    modifier = Modifier.weight(1f),
                    isPhone = isPhone
                )
            }
            
            // Row 2: Temporary guests (on guest list), Total People
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (isPhone) 12.dp else 16.dp)
            ) {
                StatCardV2(
                    title = context.getString(R.string.filter_temporary_guests),
                    value = temporaryGuestCount.toString(),
                    icon = Icons.Default.Event,
                    modifier = Modifier.weight(1f),
                    isPhone = isPhone
                )
                
                StatCardV2(
                    title = context.getString(R.string.total_people),
                    value = totalPeople.toString(),
                    icon = Icons.Default.Star,
                    modifier = Modifier.weight(1f),
                    isPhone = isPhone
                )
            }
            
            // Row 3: Active Volunteers and Inactive Volunteers in same box
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (isPhone) 12.dp else 16.dp)
            ) {
                Card(
                    modifier = Modifier
                        .weight(1f)
                        .height(if (isPhone) 140.dp else 160.dp),
                    shape = RoundedCornerShape(if (isPhone) 12.dp else 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    elevation = CardDefaults.cardElevation(
                        defaultElevation = 2.dp,
                        pressedElevation = 6.dp
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(if (isPhone) 14.dp else 16.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Active Volunteers (Left)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (isPhone) 36.dp else 44.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(if (isPhone) 8.dp else 12.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    modifier = Modifier.size(if (isPhone) 18.dp else 22.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(if (isPhone) 8.dp else 10.dp))
                            
                            Text(
                                text = activeVolunteersCount.toString(),
                                style = if (isPhone) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            
                            Spacer(modifier = Modifier.height(if (isPhone) 4.dp else 6.dp))
                            
                            Text(
                                text = context.getString(R.string.active_volunteers),
                                style = if (isPhone) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        
                        // Divider
                        VerticalDivider(
                            modifier = Modifier
                                .height(if (isPhone) 80.dp else 100.dp)
                                .width(1.dp),
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                        
                        // Inactive Volunteers (Right)
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (isPhone) 36.dp else 44.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(if (isPhone) 8.dp else 12.dp)
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    modifier = Modifier.size(if (isPhone) 18.dp else 22.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            
                            Spacer(modifier = Modifier.height(if (isPhone) 8.dp else 10.dp))
                            
                            Text(
                                text = inactiveVolunteersCount.toString(),
                                style = if (isPhone) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface,
                                textAlign = TextAlign.Center
                            )
                            
                            Spacer(modifier = Modifier.height(if (isPhone) 4.dp else 6.dp))
                            
                            Text(
                                text = context.getString(R.string.inactive_volunteers),
                                style = if (isPhone) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
            
            // Row 4: Free Drinks Today
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (isPhone) 12.dp else 16.dp)
            ) {
                StatCardV2(
                    title = context.getString(R.string.free_drinks_today),
                    value = totalFreeDrinks.toString(),
                    icon = Icons.Default.LocalBar,
                    modifier = Modifier.weight(1f),
                    isPhone = isPhone,
                    onTripleTap = { 
                        // Only show animation if seasonal fun is enabled
                        if (seasonalFunEnabled) {
                            showBeerAnimation = true
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(if (isPhone) 16.dp else 24.dp))

        LogoutCard(isPhone = isPhone, onLogout = onLogout)

        Spacer(modifier = Modifier.height(if (isPhone) 16.dp else 24.dp))
        
        // People Counter Component - only show if enabled
        if (isPeopleCounterVisible) {
            Spacer(modifier = Modifier.height(if (isPhone) 16.dp else 24.dp))
            viewModel?.let { vm ->
                PeopleCounter(isPhone = isPhone, viewModel = vm)
            }
        }

        // Announcement Button - always visible in Admin dashboard
        viewModel?.let { vm ->
            Spacer(modifier = Modifier.height(if (isPhone) 16.dp else 24.dp))
            SendAnnouncementButton(
                isPhone = isPhone,
                onClick = { vm.openSendAnnouncementDialog() }
            )
        }

        Spacer(modifier = Modifier.height(if (isPhone) 16.dp else 24.dp))
        PosAccountingReportEntryCard(
            onClick = onOpenPosReport,
            isPhone = isPhone,
        )
        
        // Stats Graphs Panel - only show if statistics are enabled
        if (isStatisticsVisible) {
            Spacer(modifier = Modifier.height(if (isPhone) 16.dp else 24.dp))
            StatsGraphsPanel(
                platformContext = platformContext,
                guests = guests,
                volunteers = volunteers,
                jobs = jobs,
                venues = venues,
                jobTypeConfigs = jobTypeConfigs,
                isPhone = isPhone,
                accountTransfers = viewModel?.let { it.accountTransfers.collectAsState().value } ?: emptyList(),
                salesSheetItems = viewModel?.let { it.salesSheetItems.collectAsState().value } ?: emptyList(),
            )
        }
        
        // Bottom padding to ensure content is not cut off by navigation bar or sync widget
        Spacer(modifier = Modifier.height(if (isPhone) 80.dp else 100.dp))
        }
        
        // Beer Animation Overlay (only if seasonal fun is enabled)
        if (showBeerAnimation && seasonalFunEnabled) {
            BeerAnimation(
                enabled = true,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
fun StatCardV2(
    title: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    emoji: String? = null,
    modifier: Modifier = Modifier,
    isPhone: Boolean = !isTablet(),
    onTripleTap: (() -> Unit)? = null
) {
    // Triple tap detection
    var tapCount by remember { mutableStateOf(0) }
    var lastTapTime by remember { mutableStateOf(0L) }
    
    // Reset tap count after timeout
    LaunchedEffect(tapCount, lastTapTime) {
        if (tapCount > 0 && tapCount < 3) {
            kotlinx.coroutines.delay(500)
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastTapTime >= 500) {
                tapCount = 0
            }
        }
    }
    
    Card(
        modifier = modifier
            .height(if (isPhone) 140.dp else 160.dp)
            .then(
                if (onTripleTap != null) {
                    Modifier.pointerInput(Unit) {
                        detectTapGestures { _ ->
                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastTapTime < 500) { // Within 500ms of last tap
                                tapCount++
                                if (tapCount >= 3) {
                                    onTripleTap()
                                    tapCount = 0
                                }
                            } else {
                                tapCount = 1
                            }
                            lastTapTime = currentTime
                        }
                    }
                } else {
                    Modifier
                }
            ),
        shape = RoundedCornerShape(if (isPhone) 12.dp else 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp,
            pressedElevation = 6.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(if (isPhone) 14.dp else 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Icon or Emoji with background
            Box(
                modifier = Modifier
                    .size(if (isPhone) 36.dp else 44.dp)
                    .background(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(if (isPhone) 8.dp else 12.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (emoji != null) {
                    Text(
                        text = emoji,
                        style = if (isPhone) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        fontSize = if (isPhone) 18.sp else 22.sp
                    )
                } else if (icon != null) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        modifier = Modifier.size(if (isPhone) 18.dp else 22.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(if (isPhone) 8.dp else 10.dp))
            
            // Large value text
            Text(
                text = value,
                style = if (isPhone) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            
            Spacer(modifier = Modifier.height(if (isPhone) 4.dp else 6.dp))
            
            // Title text - wrapped to handle long titles
            Text(
                text = title,
                style = if (isPhone) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// Wrapper composables that connect screens to ViewModel
@Composable
fun GuestListScreenWithViewModel(
    viewModel: EventManagerViewModel,
    readOnly: Boolean = false
) {
    val guests by viewModel.guests.collectAsState()
    val volunteers by viewModel.volunteers.collectAsState()
    val jobs by viewModel.jobs.collectAsState()
    val jobTypeConfigs by viewModel.jobTypeConfigs.collectAsState()
    val venues by viewModel.venues.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val settingsManager = remember { settingsManagerFor(context) }
    val scrollBehavior = remember { settingsManager.getScrollBehavior() }
    
    // OPTIMIZATION: Sync is now handled by the centralized LaunchedEffect(selectedTab) in EventManagerApp
    // This prevents duplicate syncs and improves navigation smoothness
    
    GuestListScreen(
        guests = guests,
        volunteers = volunteers,
        jobs = jobs,
        jobTypeConfigs = jobTypeConfigs,
        venues = venues,
        isSyncing = isSyncing,
        lastSyncTime = settingsManager.getLastSyncTime(),
        scrollBehavior = scrollBehavior,
        readOnly = readOnly,
        onAddGuest = { guest, photo -> 
            coroutineScope.launch { 
                try {
                    viewModel.addGuest(guest, photo)
                } catch (e: Exception) {
                    // Exception is already handled in ViewModel and shown in syncError
                    println("Guest addition failed: ${e.message}")
                }
            } 
        },
        onAddTemporaryGuests = { batch ->
            coroutineScope.launch {
                try {
                    viewModel.addTemporaryGuestBatch(batch)
                } catch (e: Exception) {
                    println("Temporary guest addition failed: ${e.message}")
                }
            }
        },
        onUpdateGuest = { 
            coroutineScope.launch { 
                try {
                    viewModel.updateGuest(it)
                } catch (e: Exception) {
                    println("Guest update failed: ${e.message}")
                }
            } 
        },
        onUpdateVolunteer = {
            coroutineScope.launch {
                try {
                    viewModel.updateVolunteer(it)
                } catch (e: Exception) {
                    println("Volunteer update from guest list failed: ${e.message}")
                }
            }
        },
        onDeleteGuest = { 
            coroutineScope.launch { 
                try {
                    viewModel.deleteGuest(it)
                } catch (e: Exception) {
                    println("Guest deletion failed: ${e.message}")
                }
            } 
        },
        onRefreshTemporaryGuests = {
            viewModel.refreshTemporaryGuests()
        },
        onConfirmEntry = { job, selectedInvites ->
            coroutineScope.launch {
                try {
                    viewModel.markBenefitAsUsed(job, selectedInvites)
                } catch (e: Exception) {
                    println("Benefit confirmation failed: ${e.message}")
                }
            }
        },
        viewModel = viewModel
    )
}

@Composable
fun VolunteerScreenWithViewModel(viewModel: EventManagerViewModel) {
    val volunteers by viewModel.volunteers.collectAsState()
    val jobs by viewModel.jobs.collectAsState()
    val venues by viewModel.venues.collectAsState()
    val jobTypeConfigs by viewModel.jobTypeConfigs.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val settingsManager = remember { settingsManagerFor(context) }
    val scrollBehavior = remember { settingsManager.getScrollBehavior() }
    
    // OPTIMIZATION: Sync is handled by the centralized LaunchedEffect(selectedTab) in EventManagerApp
    // Removing duplicate sync trigger improves navigation smoothness
    
    VolunteerScreen(
        volunteers = volunteers,
        volunteerJobs = jobs,
        venues = venues,
        jobTypeConfigs = jobTypeConfigs,
        onConfirmFutureEntry = { job, selectedInvites -> viewModel.markBenefitAsUsed(job, selectedInvites) },
        scrollBehavior = scrollBehavior,
        onAddVolunteer = { volunteer, photo -> 
            coroutineScope.launch { 
                try {
                    viewModel.addVolunteer(volunteer, photo)
                } catch (e: Exception) {
                    println("Volunteer addition failed: ${e.message}")
                }
            } 
        },
        onUpdateVolunteer = { 
            coroutineScope.launch { 
                try {
                    viewModel.updateVolunteer(it)
                } catch (e: Exception) {
                    println("Volunteer update failed: ${e.message}")
                }
            } 
        },
        onDeleteVolunteer = { volunteer, deleteShifts ->
            coroutineScope.launch { 
                try {
                    viewModel.deleteVolunteer(volunteer, deleteShifts)
                } catch (e: Exception) {
                    println("Volunteer deletion failed: ${e.message}")
                }
            } 
        },
        viewModel = viewModel
    )
}

@Composable
fun JobTrackingScreenWithViewModel(viewModel: EventManagerViewModel) {
    val jobs by viewModel.jobs.collectAsState()
    val volunteers by viewModel.volunteers.collectAsState()
    val jobTypeConfigs by viewModel.jobTypeConfigs.collectAsState()
    val venues by viewModel.venues.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val settingsManager = remember { settingsManagerFor(context) }
    val scrollBehavior = remember { settingsManager.getScrollBehavior() }
    
    // OPTIMIZATION: Sync is handled by the centralized LaunchedEffect(selectedTab) in EventManagerApp
    // Removing duplicate sync trigger improves navigation smoothness

    JobTrackingScreen(
        jobs = jobs,
        volunteers = volunteers,
        jobTypeConfigs = jobTypeConfigs,
        venues = venues,
        scrollBehavior = scrollBehavior,
        onAddJob = { 
            coroutineScope.launch { 
                try {
                    viewModel.addJob(it)
                } catch (e: Exception) {
                    println("Job addition failed: ${e.message}")
                }
            }
        },
        onUpdateJob = { 
            coroutineScope.launch { 
                try {
                    viewModel.updateJob(it)
                } catch (e: Exception) {
                    println("Job update failed: ${e.message}")
                }
            }
        },
        onDeleteJob = { 
            coroutineScope.launch { 
                try {
                    viewModel.deleteJob(it)
                } catch (e: Exception) {
                    println("Job deletion failed: ${e.message}")
                }
            }
        }
    )
}

@Composable
fun BenefitsScreenWithViewModel(viewModel: EventManagerViewModel) {
    val volunteers by viewModel.volunteers.collectAsState()
    val jobs by viewModel.jobs.collectAsState()
    val jobTypeConfigs by viewModel.jobTypeConfigs.collectAsState()
    val context = LocalContext.current
    val settingsManager = remember { settingsManagerFor(context) }
    val scrollBehavior = remember { settingsManager.getScrollBehavior() }
    
    // OPTIMIZATION: Sync is handled by the centralized LaunchedEffect(selectedTab) in EventManagerApp
    // Removing duplicate sync trigger improves navigation smoothness
    
    BenefitsScreen(
        volunteers = volunteers,
        jobs = jobs,
        jobTypeConfigs = jobTypeConfigs,
        scrollBehavior = scrollBehavior
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WelcomeScreen(
    onAdminSelected: () -> Unit,
    onTicketCheckSelected: () -> Unit,
    onPosSelected: () -> Unit = {},
    showAdminAccessSyncIndicator: Boolean = false,
    viewModel: EventManagerViewModel? = null,
) {
    val context = LocalContext.current
    val settingsManager = remember { settingsManagerFor(context) }
    val uiRefreshNonce by com.eventmanager.app.ui.platform.AppAppearanceState::refreshNonce
    val backgroundAnimationStyle = uiRefreshNonce.let { settingsManager.getBackgroundAnimationStyle() }
    val backgroundAnimationOpacity = uiRefreshNonce.let { settingsManager.getBackgroundAnimationOpacity() }
    val colorScheme = MaterialTheme.colorScheme
    val useWideWelcome = isLargeTablet()

    BackHandler {
        (context as? Activity)?.finish()
    }
    
    // Haptic feedback for start button
    val vibrator = remember { ContextCompat.getSystemService(context, Vibrator::class.java) }
    
    // Determine if dark theme is active
    val themeMode = ThemeMode.fromString(settingsManager.getThemeMode())
    val systemInDarkTheme = isSystemInDarkTheme()
    val isDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.DEFAULT -> systemInDarkTheme
    }
    
    // Check if it's Pride Day to customize the UI
    val calendar = Calendar.getInstance()
    val month = calendar.get(Calendar.MONTH)
    val day = calendar.get(Calendar.DAY_OF_MONTH)
    val isPrideDay = month == Calendar.JUNE && day == 28 && settingsManager.isSeasonalFunEnabled()

    if (useWideWelcome) {
        Scaffold { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .background(if (isPrideDay) Color.White else colorScheme.surface),
            ) {
                AppBackgroundAnimation(
                    style = backgroundAnimationStyle,
                    opacity = backgroundAnimationOpacity,
                    settingsManager = settingsManager,
                )
                if (isPrideDay) {
                    PrideAnimation(enabled = true)
                }

                WideWelcomeContent(
                    appName = context.getString(R.string.app_name),
                    onAdminSelected = {
                        performStrongHaptic(vibrator)
                        onAdminSelected()
                    },
                    onTicketCheckSelected = {
                        performStrongHaptic(vibrator)
                        onTicketCheckSelected()
                    },
                    onPosSelected = {
                        performStrongHaptic(vibrator)
                        onPosSelected()
                    },
                    hoverEnabled = false,
                    syncSlot = {
                        if (showAdminAccessSyncIndicator) {
                            Column(
                                modifier = Modifier
                                    .widthIn(max = 560.dp)
                                    .fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Spacer(Modifier.height(24.dp))
                                AdminStartupSyncBanner(modifier = Modifier.fillMaxWidth())
                            }
                        }
                    },
                )

                if (viewModel != null) {
                    FirebaseOrgSwitcher(
                        viewModel = viewModel,
                        placement = FirebaseOrgSwitcherPlacement.WelcomeTopEnd,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(20.dp),
                    )
                }
            }
        }
        return
    }
    
    Scaffold { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
        // Full-bleed launch screen with custom background and bottom CTA
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isPrideDay) Color.White else colorScheme.surface)
        ) {
            val logoName = remember(isDarkTheme) {
                if (isDarkTheme) "launch_logo_dark" else "launch_logo_light"
            }
            val logoPlatformContext = remember(context) { createPlatformContext(context) }
            val isLandscape = maxWidth > maxHeight
            val welcomeMaxWidth = maxWidth
            val welcomeMaxHeight = maxHeight

            // Animated background (arches or topographic lines).
            AppBackgroundAnimation(
                style = backgroundAnimationStyle,
                opacity = backgroundAnimationOpacity,
                settingsManager = settingsManager,
            )
            if (isPrideDay) {
                PrideAnimation(enabled = true)
            }

            val buttonColor = if (isPrideDay) {
                Color(0xFFE40303)
            } else {
                colorScheme.primary
            }

            // Logo + effects centered vertically in the band above the buttons (midpoint top → ticketing CTA)
            Column(Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            if (isPrideDay) {
                                Text(
                                    text = "Happy Pride! 🏳️‍🌈",
                                    style = if (isLandscape) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colorScheme.onBackground,
                                    modifier = Modifier.padding(bottom = 24.dp)
                                )
                            }

                            if (logoName.isNotBlank()) {
                                Card(
                                    modifier = Modifier
                                        .fillMaxWidth(if (isLandscape) 0.5f else 0.85f)
                                        .aspectRatio(1f)
                                        .padding(if (isLandscape) 16.dp else 24.dp),
                                    shape = RoundedCornerShape(if (isLandscape) 28.dp else 36.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor = colorScheme.surface.copy(alpha = 0.95f)
                                    ),
                                    elevation = CardDefaults.cardElevation(
                                        defaultElevation = 12.dp
                                    ),
                                    border = BorderStroke(
                                        width = 1.5.dp,
                                        color = colorScheme.primaryContainer.copy(alpha = 0.4f)
                                    )
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(
                                                brush = Brush.radialGradient(
                                                    colors = listOf(
                                                        colorScheme.primaryContainer.copy(alpha = 0.25f),
                                                        colorScheme.surfaceVariant.copy(alpha = 0.1f)
                                                    )
                                                ),
                                                shape = RoundedCornerShape(if (isLandscape) 28.dp else 36.dp)
                                            )
                                    )
                                }
                            } else {
                                Text(
                                    text = "CNL",
                                    style = if (isLandscape) MaterialTheme.typography.titleLarge else MaterialTheme.typography.displayLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = colorScheme.onBackground
                                )
                            }
                        }
                    }

                    if (logoName.isNotBlank()) {
                        val density = androidx.compose.ui.platform.LocalDensity.current
                        val maxLogoSizeDp = with(density) {
                            val maxScreenSize = max(welcomeMaxWidth.value, welcomeMaxHeight.value) * 1.2f
                            // Keep decode budget modest — full-screen cap caused huge decodes and ANRs on slower devices.
                            val maxAllowed = 640f
                            min(maxScreenSize, maxAllowed).dp
                        }

                        var scaledLogoBitmap by remember(logoName, maxLogoSizeDp) {
                            mutableStateOf<ImageBitmap?>(null)
                        }
                        LaunchedEffect(logoName, maxLogoSizeDp) {
                            scaledLogoBitmap = null
                            scaledLogoBitmap = withContext(Dispatchers.Default) {
                                ImageUtils.loadScaledImageBitmap(
                                    platformContext = logoPlatformContext,
                                    resourceName = logoName,
                                    maxWidthDp = maxLogoSizeDp,
                                    maxHeightDp = maxLogoSizeDp
                                )
                            }
                        }

                        val logoFadeAlpha by animateFloatAsState(
                            targetValue = if (scaledLogoBitmap != null) 1f else 0f,
                            animationSpec = tween(
                                durationMillis = 220,
                                easing = FastOutSlowInEasing
                            ),
                            label = "welcomeLogoFadeIn"
                        )

                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(horizontal = 24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (isPrideDay) {
                                    Text(
                                        text = "Happy Pride! 🏳️‍🌈",
                                        style = if (isLandscape) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.Transparent,
                                        modifier = Modifier.padding(bottom = 24.dp)
                                    )
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(if (isLandscape) 0.5f else 0.85f)
                                        .aspectRatio(1f)
                                        .padding(if (isLandscape) 16.dp else 24.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    val logoPadding = if (isLandscape) 6.dp else 8.dp
                                    val glowOuterRadius = if (isLandscape) 7.dp else 9.dp
                                    val glowInnerRadius = if (isLandscape) 4.dp else 5.dp

                                    scaledLogoBitmap?.let { bitmap ->
                                        val outerRadiusPx = with(density) { glowOuterRadius.toPx() }
                                        val innerRadiusPx = with(density) { glowInnerRadius.toPx() }
                                        val glowColor = colorScheme.primaryContainer.copy(alpha = 0.85f)
                                        val glowSamples = 16

                                        // Draw radial glow samples around the PNG alpha edge for a smoother contour.
                                        repeat(glowSamples) { sample ->
                                            val angle = (2.0 * PI * sample) / glowSamples
                                            val cosAngle = cos(angle).toFloat()
                                            val sinAngle = sin(angle).toFloat()
                                            Image(
                                                bitmap = bitmap,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(logoPadding)
                                                    .graphicsLayer {
                                                        translationX = cosAngle * outerRadiusPx
                                                        translationY = sinAngle * outerRadiusPx
                                                        alpha = 0.12f * logoFadeAlpha
                                                    },
                                                colorFilter = ColorFilter.tint(glowColor),
                                                filterQuality = FilterQuality.High
                                            )
                                            Image(
                                                bitmap = bitmap,
                                                contentDescription = null,
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(logoPadding)
                                                    .graphicsLayer {
                                                        translationX = cosAngle * innerRadiusPx
                                                        translationY = sinAngle * innerRadiusPx
                                                        alpha = 0.10f * logoFadeAlpha
                                                    },
                                                colorFilter = ColorFilter.tint(glowColor),
                                                filterQuality = FilterQuality.High
                                            )
                                        }

                                        Image(
                                            bitmap = bitmap,
                                            contentDescription = "App Logo",
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .padding(logoPadding)
                                                .graphicsLayer { alpha = logoFadeAlpha },
                                            filterQuality = FilterQuality.High
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    WelcomeForegroundPanel(
                        modifier = Modifier
                            .fillMaxWidth(if (isLandscape) 0.62f else 0.92f)
                            .padding(horizontal = 24.dp, vertical = 24.dp),
                    ) {
                        if (showAdminAccessSyncIndicator) {
                            AdminStartupSyncBanner(Modifier.fillMaxWidth())
                            Spacer(Modifier.height(12.dp))
                        }

                        Button(
                            onClick = {
                                performStrongHaptic(vibrator)
                                onTicketCheckSelected()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (isLandscape) 44.dp else 56.dp),
                            shape = RoundedCornerShape(if (isLandscape) 22.dp else 28.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = buttonColor,
                                contentColor = colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                Icons.Default.ConfirmationNumber,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = context.getString(R.string.ticket_check_mode),
                                style = if (isLandscape) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        WelcomeSecondaryButton(
                            onClick = {
                                performStrongHaptic(vibrator)
                                onPosSelected()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (isLandscape) 44.dp else 56.dp),
                        ) {
                            Icon(
                                Icons.Default.PointOfSale,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = context.getString(R.string.pos_welcome_button),
                                style = if (isLandscape) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(Modifier.height(12.dp))

                        WelcomeSecondaryButton(
                            onClick = {
                                performStrongHaptic(vibrator)
                                onAdminSelected()
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(if (isLandscape) 44.dp else 56.dp),
                        ) {
                            Icon(
                                Icons.Default.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = context.getString(R.string.admin_mode),
                                style = if (isLandscape) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }

                    }
                }
            }
        }
        if (viewModel != null) {
            FirebaseOrgSwitcher(
                viewModel = viewModel,
                placement = FirebaseOrgSwitcherPlacement.WelcomeTopEnd,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
            )
        }
        }
    }
}

// Job Type Management Screen
@Composable
fun JobTypeManagementScreenWithViewModel(
    viewModel: EventManagerViewModel,
    _onBack: () -> Unit
) {
    val jobTypeConfigs by viewModel.jobTypeConfigs.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    
    // Track if screen has been initialized to prevent re-syncing on every recomposition
    var isInitialized by remember { mutableStateOf(false) }
    
    // Trigger TARGETED sync when screen loads - only changed job types are updated
    // Use isInitialized flag to prevent re-execution on recomposition
    LaunchedEffect(isInitialized) {
        if (!isInitialized) {
            println("Job Type Management screen loaded - triggering TARGETED job type sync")
            viewModel.syncJobTypesWithTargetedUpdates()
            isInitialized = true
        }
    }
    
    BackHandler {
        _onBack()
    }

    JobTypeManagementScreen(
        jobTypeConfigs = jobTypeConfigs,
        onAddJobTypeConfig = { config ->
            coroutineScope.launch {
                try {
                    viewModel.addJobTypeConfig(config)
                } catch (e: Exception) {
                    println("Job type config addition failed: ${e.message}")
                }
            }
        },
        onUpdateJobTypeConfig = { config ->
            coroutineScope.launch {
                try {
                    viewModel.updateJobTypeConfig(config)
                } catch (e: Exception) {
                    println("Job type config update failed: ${e.message}")
                }
            }
        },
        onDeleteJobTypeConfig = { config ->
            coroutineScope.launch {
                try {
                    viewModel.deleteJobTypeConfig(config)
                } catch (e: Exception) {
                    println("Job type config deletion failed: ${e.message}")
                }
            }
        },
        onUpdateJobTypeConfigStatus = { id, isActive ->
            coroutineScope.launch {
                try {
                    val config = jobTypeConfigs.find { it.id == id }
                    if (config != null) {
                        viewModel.updateJobTypeConfig(config.copy(isActive = isActive))
                    }
                } catch (e: Exception) {
                    println("Job type config status update failed: ${e.message}")
                }
            }
        },
        onBack = _onBack
    )
}

@Composable
fun VenueManagementScreenWithViewModel(
    viewModel: EventManagerViewModel,
    _onBack: () -> Unit
) {
    val venues by viewModel.venues.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    
    // Track if screen has been initialized to prevent re-syncing on every recomposition
    var isInitialized by remember { mutableStateOf(false) }
    
    // Trigger TARGETED sync when screen loads - only changed venues are updated
    // Use isInitialized flag to prevent re-execution on recomposition
    LaunchedEffect(isInitialized) {
        if (!isInitialized) {
            println("Venue Management screen loaded - triggering TARGETED venue sync")
            viewModel.syncVenuesWithTargetedUpdates()
            isInitialized = true
        }
    }
    
    BackHandler {
        _onBack()
    }
    
    VenueManagementScreen(
        venues = venues,
        onAddVenue = { venue ->
            coroutineScope.launch {
                try {
                    viewModel.addVenue(venue)
                } catch (e: Exception) {
                    println("Venue addition failed: ${e.message}")
                }
            }
        },
        onUpdateVenue = { venue ->
            coroutineScope.launch {
                try {
                    viewModel.updateVenue(venue)
                } catch (e: Exception) {
                    println("Venue update failed: ${e.message}")
                }
            }
        },
        onDeleteVenue = { venue ->
            coroutineScope.launch {
                try {
                    viewModel.deleteVenue(venue)
                } catch (e: Exception) {
                    println("Venue deletion failed: ${e.message}")
                }
            }
        },
        onUpdateVenueStatus = { id, isActive ->
            coroutineScope.launch {
                try {
                    viewModel.updateVenueStatus(id, isActive)
                } catch (e: Exception) {
                    println("Venue status update failed: ${e.message}")
                }
            }
        },
        onBack = _onBack
    )
}

@Composable
fun GuestFormManagementScreenWithViewModel(
    viewModel: EventManagerViewModel,
    _onBack: () -> Unit,
) {
    BackHandler { _onBack() }
    GuestFormManagementScreen(
        viewModel = viewModel,
        onBack = _onBack,
    )
}

@Composable
fun SalesSheetItemManagementScreenWithViewModel(
    viewModel: EventManagerViewModel,
    _onBack: () -> Unit
) {
    val items by viewModel.salesSheetItems.collectAsState()
    val venues by viewModel.venues.collectAsState()
    val coroutineScope = rememberCoroutineScope()
    var isInitialized by remember { mutableStateOf(false) }

    LaunchedEffect(isInitialized) {
        if (!isInitialized) {
            println("Sales Sheet Item Management screen loaded - triggering TARGETED sales items sync")
            viewModel.syncSalesSheetItemsWithTargetedUpdates()
            isInitialized = true
        }
    }

    BackHandler {
        _onBack()
    }

    SalesSheetItemManagementScreen(
        items = items,
        venues = venues,
        onAddItem = { item ->
            coroutineScope.launch {
                try {
                    viewModel.addSalesSheetItem(item)
                } catch (e: Exception) {
                    println("Sales sheet item addition failed: ${e.message}")
                }
            }
        },
        onUpdateItem = { item ->
            coroutineScope.launch {
                try {
                    viewModel.updateSalesSheetItem(item)
                } catch (e: Exception) {
                    println("Sales sheet item update failed: ${e.message}")
                }
            }
        },
        onDeleteItem = { item ->
            coroutineScope.launch {
                try {
                    viewModel.deleteSalesSheetItem(item)
                } catch (e: Exception) {
                    println("Sales sheet item deletion failed: ${e.message}")
                }
            }
        },
        onUpdateItemStatus = { id, isActive ->
            coroutineScope.launch {
                try {
                    viewModel.updateSalesSheetItemStatus(id, isActive)
                } catch (e: Exception) {
                    println("Sales sheet item status update failed: ${e.message}")
                }
            }
        },
        onBack = _onBack,
        viewModel = viewModel
    )
}
