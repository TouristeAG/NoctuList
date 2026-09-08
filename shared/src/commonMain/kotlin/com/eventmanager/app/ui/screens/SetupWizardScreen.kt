package com.eventmanager.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.remote.BackendType
import com.eventmanager.app.data.security.firebaseOAuthCredentialsReady
import com.eventmanager.app.data.remote.FirebaseOrgBootstrap
import com.eventmanager.app.data.repository.EventManagerRepository
import com.eventmanager.app.data.sync.GoogleSheetsService
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.data.sync.SyncResult
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.platform.PlatformFileManager
import com.eventmanager.app.platform.createDatabase
import com.eventmanager.app.platform.isDesktop
import com.eventmanager.app.platform.openExternalUrl
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.components.ColorThemePicker
import com.eventmanager.app.ui.components.FirebaseAdminProjectConfigCard
import com.eventmanager.app.ui.components.FirebaseConfigReceivedBanner
import com.eventmanager.app.ui.components.FirebaseJoinImportSection
import com.eventmanager.app.ui.components.FirebaseSetupTutorialDialog
import com.eventmanager.app.ui.components.FirebaseSignInStep
import com.eventmanager.app.ui.components.GuidedChecklistItem
import com.eventmanager.app.ui.components.GuidedStepCard
import com.eventmanager.app.ui.components.ThemeModePicker
import com.eventmanager.app.ui.platform.ServiceAccountKeyUploadButton
import com.eventmanager.app.ui.platform.SetupLayoutScalePage
import com.eventmanager.app.ui.platform.applyLocaleChange
import com.eventmanager.app.ui.platform.applyLocaleOrThemeChange
import com.eventmanager.app.ui.platform.applyThemeAppearanceChange
import com.eventmanager.app.ui.platform.supportsResolutionScaleStep
import com.eventmanager.app.ui.theme.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

private enum class SetupPath { JOIN, FIREBASE, SHEETS }

private enum class SetupStep {
    WELCOME,
    LANGUAGE,
    THEME,
    COLOR_PROFILE,
    LAYOUT,
    CHOOSE_PATH,
    JOIN_ORG,
    SHEETS_CLOUD,
    SHEETS_KEY,
    SHEETS_SPREADSHEET,
    SHEETS_SHARE,
    SHEETS_NAMES,
    FIREBASE_ORG,
    FIREBASE_PROJECT,
    FIREBASE_SIGN_IN,
    FIRST_SYNC,
}

private enum class SetupPhase {
    WELCOME,
    PERSONALIZE,
    CONNECT,
    FINISH,
}

private fun SetupStep.phase(): SetupPhase = when (this) {
    SetupStep.WELCOME -> SetupPhase.WELCOME
    SetupStep.LANGUAGE, SetupStep.THEME, SetupStep.COLOR_PROFILE, SetupStep.LAYOUT -> SetupPhase.PERSONALIZE
    SetupStep.CHOOSE_PATH, SetupStep.JOIN_ORG,
    SetupStep.SHEETS_CLOUD, SetupStep.SHEETS_KEY, SetupStep.SHEETS_SPREADSHEET,
    SetupStep.SHEETS_SHARE, SetupStep.SHEETS_NAMES,
    SetupStep.FIREBASE_ORG, SetupStep.FIREBASE_PROJECT, SetupStep.FIREBASE_SIGN_IN -> SetupPhase.CONNECT
    SetupStep.FIRST_SYNC -> SetupPhase.FINISH
}

@Composable
private fun SetupStep.title(): String = when (this) {
    SetupStep.WELCOME -> stringResource(Res.string.setup_welcome_title)
    SetupStep.LANGUAGE -> stringResource(Res.string.setup_language_title)
    SetupStep.THEME -> stringResource(Res.string.setup_theme_title)
    SetupStep.COLOR_PROFILE -> stringResource(Res.string.setup_color_profile_title)
    SetupStep.LAYOUT -> stringResource(Res.string.setup_layout_size_title)
    SetupStep.CHOOSE_PATH -> stringResource(Res.string.setup_path_title)
    SetupStep.JOIN_ORG -> stringResource(Res.string.setup_join_title)
    SetupStep.SHEETS_CLOUD -> stringResource(Res.string.setup_sheets_cloud_title)
    SetupStep.SHEETS_KEY -> stringResource(Res.string.setup_sheets_key_title)
    SetupStep.SHEETS_SPREADSHEET -> stringResource(Res.string.setup_sheets_spreadsheet_title)
    SetupStep.SHEETS_SHARE -> stringResource(Res.string.setup_sheets_share_title)
    SetupStep.SHEETS_NAMES -> stringResource(Res.string.setup_sheets_title)
    SetupStep.FIREBASE_ORG -> stringResource(Res.string.setup_firebase_org_title)
    SetupStep.FIREBASE_PROJECT -> stringResource(Res.string.setup_firebase_project_title)
    SetupStep.FIREBASE_SIGN_IN -> stringResource(Res.string.setup_firebase_signin_title)
    SetupStep.FIRST_SYNC -> stringResource(Res.string.setup_wizard_first_sync_title)
}

@Composable
private fun SetupStep.description(): String = when (this) {
    SetupStep.WELCOME -> ""
    SetupStep.LANGUAGE -> stringResource(Res.string.setup_language_description)
    SetupStep.THEME -> stringResource(Res.string.setup_theme_description)
    SetupStep.COLOR_PROFILE -> stringResource(Res.string.setup_color_profile_description)
    SetupStep.LAYOUT -> stringResource(Res.string.setup_layout_size_description)
    SetupStep.CHOOSE_PATH -> stringResource(Res.string.setup_path_description)
    SetupStep.JOIN_ORG -> stringResource(Res.string.setup_join_description)
    SetupStep.SHEETS_CLOUD -> stringResource(Res.string.setup_sheets_cloud_body)
    SetupStep.SHEETS_KEY -> stringResource(Res.string.setup_sheets_key_body)
    SetupStep.SHEETS_SPREADSHEET -> stringResource(Res.string.setup_sheets_spreadsheet_body)
    SetupStep.SHEETS_SHARE -> stringResource(Res.string.setup_sheets_share_body)
    SetupStep.SHEETS_NAMES -> stringResource(Res.string.setup_sheets_defaults_hint)
    SetupStep.FIREBASE_ORG -> stringResource(Res.string.setup_firebase_org_body)
    SetupStep.FIREBASE_PROJECT -> stringResource(Res.string.setup_firebase_project_body)
    SetupStep.FIREBASE_SIGN_IN -> stringResource(Res.string.setup_firebase_signin_body)
    SetupStep.FIRST_SYNC -> stringResource(Res.string.setup_first_sync_ready)
}

private fun SetupStep.icon(): ImageVector = when (this) {
    SetupStep.WELCOME -> Icons.Default.RocketLaunch
    SetupStep.LANGUAGE -> Icons.Default.Language
    SetupStep.THEME -> Icons.Default.DarkMode
    SetupStep.COLOR_PROFILE -> Icons.Default.Palette
    SetupStep.LAYOUT -> Icons.Default.AspectRatio
    SetupStep.CHOOSE_PATH -> Icons.Default.Hub
    SetupStep.JOIN_ORG -> Icons.Default.QrCodeScanner
    SetupStep.SHEETS_CLOUD -> Icons.Default.Cloud
    SetupStep.SHEETS_KEY -> Icons.Default.VpnKey
    SetupStep.SHEETS_SPREADSHEET -> Icons.Default.TableChart
    SetupStep.SHEETS_SHARE -> Icons.Default.Share
    SetupStep.SHEETS_NAMES -> Icons.Default.List
    SetupStep.FIREBASE_ORG -> Icons.Default.Business
    SetupStep.FIREBASE_PROJECT -> Icons.Default.Cloud
    SetupStep.FIREBASE_SIGN_IN -> Icons.Default.Login
    SetupStep.FIRST_SYNC -> Icons.Default.Sync
}

@Composable
private fun SetupPhase.label(): String = when (this) {
    SetupPhase.WELCOME -> ""
    SetupPhase.PERSONALIZE -> stringResource(Res.string.setup_phase_personalize)
    SetupPhase.CONNECT -> stringResource(Res.string.setup_phase_connect)
    SetupPhase.FINISH -> stringResource(Res.string.setup_phase_finish)
}

private fun serviceAccountEmailFromJson(json: String?): String? {
    if (json.isNullOrBlank()) return null
    return Regex(""""client_email"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SetupWizardScreen(
    platformContext: PlatformContext,
    onSetupComplete: () -> Unit,
    onThemeModeChanged: (String) -> Unit = {},
    onRequestFirebaseSignIn: (() -> Unit)? = null,
    firebaseAuthEmail: String? = null,
    firebaseSignInFeedback: String? = null,
) {
    val settingsManager = remember(platformContext) { settingsManagerFor(platformContext) }
    val fileManager = remember(platformContext) { PlatformFileManager(platformContext) }
    val scope = rememberCoroutineScope()
    var selectedPath by remember { mutableStateOf<SetupPath?>(null) }
    val selectedBackend = when (selectedPath) {
        SetupPath.SHEETS -> BackendType.SHEETS
        SetupPath.JOIN, SetupPath.FIREBASE -> BackendType.FIREBASE
        null -> settingsManager.getBackendType()
    }
    val steps = remember(selectedPath) {
        buildList {
            add(SetupStep.WELCOME)
            add(SetupStep.LANGUAGE)
            add(SetupStep.THEME)
            add(SetupStep.COLOR_PROFILE)
            if (supportsResolutionScaleStep()) add(SetupStep.LAYOUT)
            add(SetupStep.CHOOSE_PATH)
            when (selectedPath) {
                SetupPath.JOIN -> {
                    add(SetupStep.JOIN_ORG)
                    add(SetupStep.FIREBASE_SIGN_IN)
                }
                SetupPath.FIREBASE -> {
                    add(SetupStep.FIREBASE_ORG)
                    add(SetupStep.FIREBASE_PROJECT)
                    add(SetupStep.FIREBASE_SIGN_IN)
                }
                SetupPath.SHEETS -> {
                    add(SetupStep.SHEETS_CLOUD)
                    add(SetupStep.SHEETS_KEY)
                    add(SetupStep.SHEETS_SPREADSHEET)
                    add(SetupStep.SHEETS_SHARE)
                    add(SetupStep.SHEETS_NAMES)
                }
                null -> Unit
            }
            if (selectedPath != null) add(SetupStep.FIRST_SYNC)
        }
    }

    val pagerState = rememberPagerState(pageCount = { steps.size })
    var selectedLanguage by remember { mutableStateOf(settingsManager.getLanguage()) }
    var selectedTheme by remember { mutableStateOf(ThemeMode.fromString(settingsManager.getThemeMode())) }
    var selectedColorTheme by remember { mutableStateOf(settingsManager.getColorTheme()) }
    var resolutionScale by remember { mutableStateOf(settingsManager.getResolutionScale()) }
    val baselineScale = remember { settingsManager.getResolutionScale().coerceIn(0.8f, 1.2f) }

    var spreadsheetId by remember { mutableStateOf(settingsManager.getSpreadsheetId()) }
    var guestListSheet by remember { mutableStateOf(settingsManager.getGuestListSheet()) }
    var volunteerSheet by remember { mutableStateOf(settingsManager.getVolunteerSheet()) }
    var jobsSheet by remember { mutableStateOf(settingsManager.getJobsSheet()) }
    var volunteerGuestListSheet by remember { mutableStateOf(settingsManager.getVolunteerGuestListSheet()) }
    var jobTypesSheet by remember { mutableStateOf(settingsManager.getJobTypesSheet()) }
    var venuesSheet by remember { mutableStateOf(settingsManager.getVenuesSheet()) }
    var salesItemsSheet by remember { mutableStateOf(settingsManager.getSalesItemsSheet()) }
    var tempGuestListSheet by remember { mutableStateOf(settingsManager.getTempGuestListSheet()) }
    var settingsSheet by remember { mutableStateOf(settingsManager.getSettingsSheet()) }

    var jsonKeyStatus by remember { mutableStateOf<String?>(null) }
    var jsonKeyPresent by remember { mutableStateOf(fileManager.getServiceAccountFile() != null) }
    var serviceAccountEmail by remember {
        mutableStateOf(serviceAccountEmailFromJson(fileManager.readServiceAccountJson()))
    }
    var firebaseConfiguredOrgs by remember {
        mutableStateOf(
            settingsManager.getFirebaseConfiguredOrgs().ifEmpty {
                listOf(
                    com.eventmanager.app.data.remote.FirebaseConfiguredOrg(
                        orgId = settingsManager.getFirebaseOrgId(),
                        colorArgb = com.eventmanager.app.data.remote.FirebaseConfiguredOrgCodec.defaultColorForIndex(0),
                    ),
                )
            },
        )
    }
    var firebaseProjectId by remember { mutableStateOf(settingsManager.getFirebaseProjectId()) }
    var firebaseApplicationId by remember { mutableStateOf(settingsManager.getFirebaseApplicationId()) }
    var firebaseApiKey by remember { mutableStateOf(settingsManager.getFirebaseApiKey()) }
    var firebaseWebClientId by remember { mutableStateOf(settingsManager.getFirebaseWebClientId()) }
    var firebaseWebClientSecret by remember { mutableStateOf(settingsManager.getFirebaseWebClientSecret()) }
    var firebaseJoinImported by remember { mutableStateOf(settingsManager.isFirebaseJoinImported()) }
    // Mirrored as state: the "continue" gate below reads it, and a plain settings read never
    // recomposes when a scan or a manually typed invitation code stores a new one.
    var firebaseBootstrapCode by remember { mutableStateOf(settingsManager.getFirebaseBootstrapCode()) }
    var joinError by remember { mutableStateOf<String?>(null) }
    var authEmail by remember {
        mutableStateOf(firebaseAuthEmail ?: settingsManager.getFirebaseAuthEmail().ifBlank { null })
    }
    LaunchedEffect(firebaseAuthEmail) {
        if (!firebaseAuthEmail.isNullOrBlank()) authEmail = firebaseAuthEmail
        else {
            val fromSettings = settingsManager.getFirebaseAuthEmail().ifBlank { null }
            if (fromSettings != null) authEmail = fromSettings
        }
    }
    var firstSyncRunning by remember { mutableStateOf(false) }
    var firstSyncError by remember { mutableStateOf<String?>(null) }
    var firstSyncDone by remember { mutableStateOf(false) }
    var showFirebaseTutorial by remember { mutableStateOf(false) }
    var showJoinScan by remember { mutableStateOf(false) }
    // Resolved here: the scanner callback is not a composable scope.
    val joinOAuthMissingMessage = stringResource(Res.string.firebase_join_error_oauth_missing)
    val joinInviteMissingMessage = stringResource(Res.string.firebase_join_error_invite_missing)
    val invalidJoinCodeMessage = stringResource(Res.string.firebase_join_error_invalid)

    fun persistFirebaseFields() {
        settingsManager.setFirebaseConfiguredOrgs(firebaseConfiguredOrgs)
        settingsManager.setFirebaseProjectId(firebaseProjectId.trim())
        settingsManager.setFirebaseApplicationId(firebaseApplicationId.trim())
        settingsManager.setFirebaseApiKey(firebaseApiKey.trim())
        settingsManager.setFirebaseWebClientId(firebaseWebClientId.trim())
        settingsManager.setFirebaseWebClientSecret(firebaseWebClientSecret.trim())
    }

    fun reloadFirebaseFromSettings() {
        firebaseConfiguredOrgs = settingsManager.getFirebaseConfiguredOrgs()
        firebaseProjectId = settingsManager.getFirebaseProjectId()
        firebaseApplicationId = settingsManager.getFirebaseApplicationId()
        firebaseApiKey = settingsManager.getFirebaseApiKey()
        firebaseWebClientId = settingsManager.getFirebaseWebClientId()
        firebaseWebClientSecret = settingsManager.getFirebaseWebClientSecret()
        firebaseJoinImported = settingsManager.isFirebaseJoinImported()
        firebaseBootstrapCode = settingsManager.getFirebaseBootstrapCode()
    }

    fun firebaseProjectReady(): Boolean =
        firebaseProjectId.isNotBlank() && firebaseApplicationId.isNotBlank() && firebaseApiKey.isNotBlank()

    fun firebaseOAuthReady(): Boolean =
        firebaseOAuthCredentialsReady(firebaseWebClientId, firebaseWebClientSecret)

    fun firebaseOrgReady(): Boolean =
        firebaseConfiguredOrgs.any { FirebaseOrgBootstrap.isValidOrgId(it.orgId) }

    fun firebaseAuthReady(): Boolean = !authEmail.isNullOrBlank()

    fun sheetsStepReady(): Boolean =
        spreadsheetId.isNotBlank() && spreadsheetId != "YOUR_SPREADSHEET_ID_HERE"

    fun finishSetup() {
        settingsManager.setSetupWizardCompleted(true)
        settingsManager.markOpenWelcomeAfterSetup()
        onSetupComplete()
    }

    fun runFirstSync() {
        scope.launch {
            firstSyncRunning = true
            firstSyncError = null
            settingsManager.setBackendType(selectedBackend)
            val result = withContext(Dispatchers.IO) {
                try {
                    when (selectedBackend) {
                        BackendType.FIREBASE -> {
                            persistFirebaseFields()
                            val projectOk = firebaseProjectReady() && firebaseOrgReady()
                            if (!projectOk || !firebaseAuthReady()) {
                                SyncResult.Error("Complete Firebase setup and Google Sign-In first")
                            } else {
                                val auth = com.eventmanager.app.data.remote.createFirebaseAuthService(platformContext)
                                val authResult = when {
                                    auth.isSignedIn() -> com.eventmanager.app.data.remote.FirebaseAuthResult.Success(
                                        uid = auth.currentUserId().orEmpty(),
                                        email = auth.currentUserEmail(),
                                    )
                                    else -> auth.restoreSession() ?: auth.signInWithGoogle()
                                }
                                when (authResult) {
                                    is com.eventmanager.app.data.remote.FirebaseAuthResult.Error ->
                                        SyncResult.Error(authResult.message)
                                    is com.eventmanager.app.data.remote.FirebaseAuthResult.Success -> {
                                        authEmail = authResult.email
                                        settingsManager.setFirebaseAuthEmail(authResult.email.orEmpty())
                                        val gateway = com.eventmanager.app.data.remote.createFirestoreGateway(
                                            platformContext,
                                            settingsManager,
                                        )
                                        val activeOrgId = settingsManager.getFirebaseOrgId().trim()
                                        val membershipError: SyncResult.Error? = when (selectedPath) {
                                            SetupPath.JOIN -> {
                                                val invite = settingsManager.getFirebaseBootstrapCode()
                                                if (invite.isBlank()) {
                                                    SyncResult.Error(
                                                        "Invitation code missing — scan a full join QR from Settings, or enter the 8-character invitation code",
                                                    )
                                                } else {
                                                    // Shared path: already-a-member is a no-op, and
                                                    // refusals come back with an actionable message.
                                                    runCatching {
                                                        FirebaseOrgBootstrap.ensureOrgBootstrappedIfNeeded(
                                                            gateway = gateway,
                                                            settings = settingsManager,
                                                            orgId = activeOrgId,
                                                            intent = com.eventmanager.app.data.remote
                                                                .OrgBootstrapIntent.ENSURE_MEMBERSHIP,
                                                            signedInUid = authResult.uid,
                                                            signedInEmail = authResult.email,
                                                        )
                                                    }.exceptionOrNull()?.let { e ->
                                                        SyncResult.Error(e.message ?: "Failed to join organization")
                                                    }
                                                }
                                            }
                                            else -> {
                                                val bootstrapCode = com.eventmanager.app.data.remote.MemberRoleAdmin
                                                    .bootstrapOrgAdmin(
                                                        gateway = gateway,
                                                        orgId = activeOrgId,
                                                        uid = authResult.uid,
                                                        email = authResult.email,
                                                        allowedEmailDomains = settingsManager.getAllowedEmailDomains(),
                                                    )
                                                settingsManager.setFirebaseBootstrapCode(bootstrapCode)
                                                null
                                            }
                                        }
                                        if (membershipError != null) {
                                            membershipError
                                        } else {
                                            settingsManager.applyLocalInstitutionBackendAnnouncement(
                                                com.eventmanager.app.data.remote.InstitutionBackendAnnouncement(
                                                    backendType = BackendType.FIREBASE,
                                                    migrationId = "",
                                                    migratedAt = System.currentTimeMillis(),
                                                    firebaseOrgId = activeOrgId,
                                                )
                                            )
                                            val db = createDatabase(platformContext)
                                            val repository = EventManagerRepository(
                                                db.guestDao(), db.volunteerDao(), db.jobDao(),
                                                db.jobTypeConfigDao(), db.venueDao(), db.salesSheetItemDao(),
                                                db.accountTransferDao(), db.guestFormDao()
                                            )
                                            val ledger = com.eventmanager.app.data.remote.FirebaseLedgerService(
                                                repository, settingsManager, gateway,
                                            )
                                            val firebase = com.eventmanager.app.data.remote.FirebaseRemoteBackend(
                                                platformContext = platformContext,
                                                repository = repository,
                                                settingsManager = settingsManager,
                                                firestoreGateway = gateway,
                                                ledgerService = ledger,
                                            )
                                            firebase.performStartupSync()
                                        }
                                    }
                                }
                            }
                        }
                        BackendType.SHEETS -> {
                            settingsManager.saveSpreadsheetId(spreadsheetId)
                            settingsManager.saveGuestListSheet(guestListSheet)
                            settingsManager.saveVolunteerSheet(volunteerSheet)
                            settingsManager.saveJobsSheet(jobsSheet)
                            settingsManager.saveVolunteerGuestListSheet(volunteerGuestListSheet)
                            settingsManager.saveJobTypesSheet(jobTypesSheet)
                            settingsManager.saveVenuesSheet(venuesSheet)
                            settingsManager.saveSalesItemsSheet(salesItemsSheet)
                            settingsManager.saveTempGuestListSheet(tempGuestListSheet)
                            settingsManager.saveSettingsSheet(settingsSheet)
                            val db = createDatabase(platformContext)
                            val repository = EventManagerRepository(
                                db.guestDao(), db.volunteerDao(), db.jobDao(),
                                db.jobTypeConfigDao(), db.venueDao(), db.salesSheetItemDao(),
                                db.accountTransferDao(), db.guestFormDao()
                            )
                            val syncManager = com.eventmanager.app.data.sync.SyncManager(
                                platformContext, repository, GoogleSheetsService(platformContext)
                            )
                            syncManager.repairSheetStructureThenFullDownload()
                        }
                    }
                } catch (e: Exception) {
                    SyncResult.Error(e.message ?: "Sync failed")
                }
            }
            firstSyncRunning = false
            if (result is SyncResult.Success) {
                firstSyncDone = true
                settingsManager.markSkipNextStartupSync()
            } else {
                firstSyncError = (result as? SyncResult.Error)?.message ?: "Sync failed"
            }
        }
    }

    val currentStep = steps.getOrElse(pagerState.currentPage) { SetupStep.WELCOME }
    val progress = (pagerState.currentPage + 1).toFloat() / steps.size.coerceAtLeast(1)
    val density = LocalDensity.current
    val previewScale = resolutionScale.coerceIn(0.8f, 1.2f)
    val previewDensity = remember(density, baselineScale, previewScale) {
        Density(
            density.density * baselineScale / previewScale,
            density.fontScale * baselineScale / previewScale,
        )
    }

    CompositionLocalProvider(LocalDensity provides previewDensity) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.surface,
            bottomBar = {
                Surface(tonalElevation = 4.dp, shadowElevation = 8.dp) {
                    Column(Modifier.fillMaxWidth()) {
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(4.dp),
                            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (pagerState.currentPage > 0) {
                                OutlinedButton(
                                    onClick = { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } }
                                ) {
                                    Icon(Icons.Default.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(stringResource(Res.string.setup_back))
                                }
                            } else {
                                Spacer(Modifier.width(8.dp))
                            }
                            val isLast = pagerState.currentPage == steps.lastIndex
                            Button(
                                onClick = {
                                    if (currentStep == SetupStep.FIREBASE_ORG ||
                                        currentStep == SetupStep.FIREBASE_PROJECT ||
                                        currentStep == SetupStep.JOIN_ORG
                                    ) {
                                        persistFirebaseFields()
                                    }
                                    if (isLast) {
                                        if (firstSyncDone) finishSetup()
                                        else if (!firstSyncRunning) runFirstSync()
                                    } else {
                                        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                                    }
                                },
                                enabled = when (currentStep) {
                                    SetupStep.CHOOSE_PATH -> selectedPath != null
                                    SetupStep.JOIN_ORG -> firebaseProjectReady() &&
                                        firebaseOrgReady() &&
                                        firebaseOAuthReady() &&
                                        firebaseBootstrapCode.isNotBlank()
                                    SetupStep.FIREBASE_ORG -> firebaseOrgReady()
                                    SetupStep.FIREBASE_PROJECT -> firebaseProjectReady() && firebaseOAuthReady()
                                    SetupStep.FIREBASE_SIGN_IN -> firebaseAuthReady()
                                    SetupStep.SHEETS_KEY -> jsonKeyPresent
                                    SetupStep.SHEETS_SPREADSHEET -> sheetsStepReady()
                                    SetupStep.FIRST_SYNC -> firstSyncDone || !firstSyncRunning
                                    else -> true
                                }
                            ) {
                                Text(
                                    when {
                                        currentStep == SetupStep.WELCOME -> stringResource(Res.string.setup_get_started)
                                        isLast && firstSyncDone -> stringResource(Res.string.setup_finish)
                                        isLast -> stringResource(Res.string.setup_start_sync)
                                        else -> stringResource(Res.string.setup_continue)
                                    }
                                )
                                if (!isLast || !firstSyncDone) {
                                    Spacer(Modifier.width(6.dp))
                                    Icon(Icons.Default.ArrowForward, contentDescription = null, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentAlignment = Alignment.TopCenter
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize().widthIn(max = 640.dp),
                    userScrollEnabled = false
                ) { page ->
                    val step = steps.getOrElse(page) { SetupStep.WELCOME }
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        SetupWizardHeader(
                            step = step,
                            stepIndex = page,
                            totalSteps = steps.size,
                            onSkip = { finishSetup() },
                            showSkip = step != SetupStep.WELCOME && step != SetupStep.FIRST_SYNC
                        )

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(20.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(20.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                when (step) {
                                    SetupStep.WELCOME -> WelcomePage()
                                    SetupStep.LANGUAGE -> LanguagePage(selectedLanguage) { code ->
                                        selectedLanguage = code
                                        settingsManager.saveLanguage(code)
                                        settingsManager.markSkipNextStartupSync()
                                        applyLocaleChange(platformContext)
                                    }
                                    SetupStep.THEME -> ThemeModePicker(
                                        selectedMode = selectedTheme,
                                        onSelect = { mode ->
                                            selectedTheme = mode
                                            settingsManager.saveThemeMode(mode.value)
                                            onThemeModeChanged(mode.value)
                                            settingsManager.markSkipNextStartupSync()
                                            applyThemeAppearanceChange(platformContext)
                                        },
                                        showHeader = false,
                                    )
                                    SetupStep.COLOR_PROFILE -> ColorThemePicker(
                                        selectedThemeKey = selectedColorTheme,
                                        previewDark = when (selectedTheme) {
                                            ThemeMode.LIGHT -> false
                                            ThemeMode.DARK -> true
                                            ThemeMode.DEFAULT -> isSystemInDarkTheme()
                                        },
                                        onSelect = { key ->
                                            selectedColorTheme = key
                                            settingsManager.saveColorTheme(key)
                                            settingsManager.markSkipNextStartupSync()
                                            applyThemeAppearanceChange(platformContext)
                                        },
                                        showHeader = false,
                                    )
                                    SetupStep.LAYOUT -> SetupLayoutScalePage(
                                        resolutionScale = resolutionScale,
                                        onSave = { scale ->
                                            resolutionScale = scale
                                            settingsManager.saveResolutionScale(scale)
                                            applyLocaleOrThemeChange(platformContext)
                                        },
                                        onUseRecommended = {
                                            resolutionScale = 1.07f
                                            settingsManager.saveResolutionScale(1.07f)
                                            applyLocaleOrThemeChange(platformContext)
                                        }
                                    )
                                    SetupStep.CHOOSE_PATH -> ChoosePathPage(
                                        selected = selectedPath,
                                        onSelect = { path ->
                                            selectedPath = path
                                            settingsManager.setBackendType(
                                                if (path == SetupPath.SHEETS) BackendType.SHEETS else BackendType.FIREBASE,
                                            )
                                        },
                                    )
                                    SetupStep.JOIN_ORG -> JoinOrgPage(
                                        settingsManager = settingsManager,
                                        orgId = settingsManager.getFirebaseOrgId().ifBlank {
                                            firebaseConfiguredOrgs.firstOrNull { it.orgId.isNotBlank() }?.orgId.orEmpty()
                                        },
                                        projectReady = firebaseProjectReady(),
                                        orgReady = firebaseOrgReady(),
                                        oauthReady = firebaseOAuthReady(),
                                        inviteReady = firebaseBootstrapCode.isNotBlank(),
                                        configImported = firebaseJoinImported,
                                        joinError = joinError,
                                        onRequestScan = { showJoinScan = true },
                                        onJoined = { reloadFirebaseFromSettings() },
                                        onJoinInputChanged = {
                                            joinError = null
                                            reloadFirebaseFromSettings()
                                        },
                                    )
                                    SetupStep.SHEETS_CLOUD -> SheetsCloudPage(platformContext)
                                    SetupStep.SHEETS_KEY -> SheetsKeyPage(
                                        platformContext = platformContext,
                                        jsonKeyStatus = jsonKeyStatus,
                                        onJsonKeyStatus = { status ->
                                            jsonKeyStatus = status
                                            jsonKeyPresent = fileManager.getServiceAccountFile() != null
                                            serviceAccountEmail =
                                                serviceAccountEmailFromJson(fileManager.readServiceAccountJson())
                                        },
                                    )
                                    SetupStep.SHEETS_SPREADSHEET -> SheetsSpreadsheetPage(
                                        spreadsheetId = spreadsheetId,
                                        onSpreadsheetIdChange = { spreadsheetId = it },
                                        platformContext = platformContext,
                                    )
                                    SetupStep.SHEETS_SHARE -> SheetsSharePage(
                                        platformContext = platformContext,
                                        serviceAccountEmail = serviceAccountEmail,
                                    )
                                    SetupStep.SHEETS_NAMES -> SheetsPage(
                                        guestListSheet, { guestListSheet = it },
                                        volunteerSheet, { volunteerSheet = it },
                                        jobsSheet, { jobsSheet = it },
                                        volunteerGuestListSheet, { volunteerGuestListSheet = it },
                                        jobTypesSheet, { jobTypesSheet = it },
                                        venuesSheet, { venuesSheet = it },
                                        salesItemsSheet, { salesItemsSheet = it },
                                        tempGuestListSheet, { tempGuestListSheet = it },
                                        settingsSheet, { settingsSheet = it }
                                    )
                                    SetupStep.FIREBASE_ORG -> FirebaseOrgPage(
                                        orgId = firebaseConfiguredOrgs.firstOrNull()?.orgId.orEmpty(),
                                        onOrgIdChange = { newId ->
                                            val color = firebaseConfiguredOrgs.firstOrNull()?.colorArgb
                                                ?: com.eventmanager.app.data.remote.FirebaseConfiguredOrgCodec.defaultColorForIndex(0)
                                            firebaseConfiguredOrgs = listOf(
                                                com.eventmanager.app.data.remote.FirebaseConfiguredOrg(
                                                    orgId = newId,
                                                    colorArgb = color,
                                                ),
                                            )
                                        },
                                    )
                                    SetupStep.FIREBASE_PROJECT -> FirebaseProjectPage(
                                        settingsManager = settingsManager,
                                        projectId = firebaseProjectId,
                                        onProjectIdChange = { firebaseProjectId = it },
                                        applicationId = firebaseApplicationId,
                                        onApplicationIdChange = { firebaseApplicationId = it },
                                        apiKey = firebaseApiKey,
                                        onApiKeyChange = { firebaseApiKey = it },
                                        webClientId = firebaseWebClientId,
                                        onWebClientIdChange = { firebaseWebClientId = it },
                                        webClientSecret = firebaseWebClientSecret,
                                        onWebClientSecretChange = { firebaseWebClientSecret = it },
                                        onOpenTutorial = { showFirebaseTutorial = true },
                                        platformContext = platformContext,
                                    )
                                    SetupStep.FIREBASE_SIGN_IN -> FirebaseSignInStep(
                                        authEmail = authEmail,
                                        onSignIn = {
                                            persistFirebaseFields()
                                            if (onRequestFirebaseSignIn != null) {
                                                onRequestFirebaseSignIn()
                                            } else {
                                                scope.launch {
                                                    when (val result = com.eventmanager.app.data.remote
                                                        .createFirebaseAuthService(platformContext)
                                                        .signInWithGoogle()
                                                    ) {
                                                        is com.eventmanager.app.data.remote.FirebaseAuthResult.Success -> {
                                                            authEmail = result.email
                                                            settingsManager.setFirebaseAuthEmail(result.email.orEmpty())
                                                        }
                                                        is com.eventmanager.app.data.remote.FirebaseAuthResult.Error -> {
                                                            firstSyncError = result.message
                                                        }
                                                    }
                                                }
                                            }
                                        },
                                        signInFeedback = firebaseSignInFeedback ?: firstSyncError,
                                    )
                                    SetupStep.FIRST_SYNC -> FirstSyncPage(firstSyncRunning, firstSyncDone, firstSyncError)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFirebaseTutorial) {
        FirebaseSetupTutorialDialog(
            onDismiss = { showFirebaseTutorial = false },
            projectId = settingsManager.getFirebaseProjectId(),
        )
    }
    if (showJoinScan) {
        com.eventmanager.app.ui.components.RawPayloadQrScannerDialog(
            platformContext = platformContext,
            onDismiss = { showJoinScan = false },
            onPayload = { raw ->
                // Same validation as the paste path: a legacy QR without the OAuth secret or the
                // invitation still imports project options, and the wizard would otherwise show
                // "configuration received" next to a permanently disabled Continue button.
                joinError = when (
                    val result = com.eventmanager.app.data.remote.FirebaseJoinImport
                        .apply(settingsManager, raw)
                ) {
                    is com.eventmanager.app.data.remote.FirebaseJoinImportResult.Undecodable ->
                        result.message ?: invalidJoinCodeMessage

                    is com.eventmanager.app.data.remote.FirebaseJoinImportResult.Incomplete ->
                        when (result.problem) {
                            com.eventmanager.app.data.remote.FirebaseJoinImportProblem
                                .OAUTH_SECRET_MISSING -> joinOAuthMissingMessage

                            com.eventmanager.app.data.remote.FirebaseJoinImportProblem
                                .INVITATION_MISSING -> joinInviteMissingMessage
                        }

                    is com.eventmanager.app.data.remote.FirebaseJoinImportResult.Complete -> null
                }
                reloadFirebaseFromSettings()
                showJoinScan = false
            },
        )
    }
}

@Composable
private fun SetupWizardHeader(
    step: SetupStep,
    stepIndex: Int,
    totalSteps: Int,
    onSkip: () -> Unit,
    showSkip: Boolean
) {
    val phaseLabel = step.phase().label()
    val description = step.description()
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(Res.string.setup_step_progress, stepIndex + 1, totalSteps),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
            if (showSkip) {
                TextButton(onClick = onSkip) {
                    Text(
                        stringResource(Res.string.setup_skip),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        if (step == SetupStep.WELCOME) return
        if (phaseLabel.isNotBlank()) {
            AssistChip(
                onClick = {},
                enabled = false,
                label = { Text(phaseLabel) },
                leadingIcon = {
                    Icon(
                        when (step.phase()) {
                            SetupPhase.PERSONALIZE -> Icons.Default.Tune
                            SetupPhase.CONNECT -> Icons.Default.Link
                            SetupPhase.FINISH -> Icons.Default.CheckCircle
                            else -> Icons.Default.Info
                        },
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                },
                border = null,
                colors = AssistChipDefaults.assistChipColors(
                    disabledContainerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    disabledLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
        Box(
            modifier = Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                step.icon(),
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = step.title(),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        if (description.isNotBlank()) {
            Text(
                text = description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun WelcomePage() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.RocketLaunch,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            stringResource(Res.string.setup_welcome_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        Text(
            stringResource(Res.string.setup_welcome_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            WelcomeOverviewRow(1, Icons.Default.Tune, stringResource(Res.string.setup_overview_1_title), stringResource(Res.string.setup_overview_1_body))
            WelcomeOverviewRow(2, Icons.Default.Link, stringResource(Res.string.setup_overview_2_title), stringResource(Res.string.setup_overview_2_body))
            WelcomeOverviewRow(3, Icons.Default.Sync, stringResource(Res.string.setup_overview_3_title), stringResource(Res.string.setup_overview_3_body))
        }
    }
}

@Composable
private fun WelcomeOverviewRow(number: Int, icon: ImageVector, title: String, body: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            }
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChoosePathPage(
    selected: SetupPath?,
    onSelect: (SetupPath) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        SetupChoiceCard(
            selected = selected == SetupPath.JOIN,
            title = stringResource(Res.string.setup_path_join),
            body = stringResource(Res.string.setup_path_join_body),
            icon = Icons.Default.QrCodeScanner,
            onClick = { onSelect(SetupPath.JOIN) },
        )
        SetupChoiceCard(
            selected = selected == SetupPath.FIREBASE,
            title = stringResource(Res.string.setup_path_firebase),
            body = stringResource(Res.string.setup_path_firebase_body),
            icon = Icons.Default.Cloud,
            onClick = { onSelect(SetupPath.FIREBASE) },
        )
        SetupChoiceCard(
            selected = selected == SetupPath.SHEETS,
            title = stringResource(Res.string.setup_path_sheets),
            body = stringResource(Res.string.setup_path_sheets_body),
            icon = Icons.Default.TableChart,
            onClick = { onSelect(SetupPath.SHEETS) },
        )
    }
}

@Composable
private fun SetupChoiceCard(
    selected: Boolean,
    title: String,
    body: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            if (selected) 2.dp else 1.dp,
            if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
    ) {
        Row(
            modifier = Modifier.padding(16.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(
                    body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun JoinOrgPage(
    settingsManager: SettingsManager,
    orgId: String,
    projectReady: Boolean,
    orgReady: Boolean,
    oauthReady: Boolean,
    inviteReady: Boolean,
    configImported: Boolean,
    joinError: String?,
    onRequestScan: () -> Unit,
    onJoined: () -> Unit,
    onJoinInputChanged: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        FirebaseJoinImportSection(
            settingsManager = settingsManager,
            onJoined = { onJoined() },
            onRequestScan = onRequestScan,
            joinError = joinError,
            onJoinInputChanged = onJoinInputChanged,
            configImported = configImported,
        )
        // The import section already confirms an imported config; avoid a second banner.
        if (projectReady && !configImported) {
            FirebaseConfigReceivedBanner(orgId = orgId)
        }
        // These four are exactly what the Continue button waits for. A join code can import a
        // partial configuration, so the page has to say which piece is still missing.
        GuidedStepCard(title = stringResource(Res.string.firebase_checklist_title)) {
            GuidedChecklistItem(stringResource(Res.string.firebase_checklist_org), orgReady)
            GuidedChecklistItem(stringResource(Res.string.firebase_checklist_project), projectReady)
            GuidedChecklistItem(stringResource(Res.string.firebase_checklist_oauth), oauthReady)
            GuidedChecklistItem(stringResource(Res.string.firebase_checklist_invite), inviteReady)
        }
    }
}

@Composable
private fun SheetsCloudPage(platformContext: PlatformContext) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SetupGuideStep(1, stringResource(Res.string.step_1_title), stringResource(Res.string.step_1_description))
        SetupGuideStep(2, stringResource(Res.string.step_2_title), stringResource(Res.string.step_2_description))
        OutlinedButton(
            onClick = { openExternalUrl(platformContext, "https://console.cloud.google.com/") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.open_google_cloud_console))
        }
    }
}

@Composable
private fun SheetsKeyPage(
    platformContext: PlatformContext,
    jsonKeyStatus: String?,
    onJsonKeyStatus: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SetupGuideStep(3, stringResource(Res.string.step_3_title), stringResource(Res.string.step_3_description))
        ServiceAccountKeyUploadButton(
            platformContext = platformContext,
            onStatusUpdate = onJsonKeyStatus,
            modifier = Modifier.fillMaxWidth(),
        )
        jsonKeyStatus?.let {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
            ) {
                Text(
                    it,
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@Composable
private fun SheetsSpreadsheetPage(
    spreadsheetId: String,
    onSpreadsheetIdChange: (String) -> Unit,
    platformContext: PlatformContext,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SetupGuideStep(4, stringResource(Res.string.step_5_title), stringResource(Res.string.step_5_description))
        OutlinedButton(
            onClick = { openExternalUrl(platformContext, "https://sheets.google.com/") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.setup_open_google_sheets))
        }
        OutlinedTextField(
            value = spreadsheetId,
            onValueChange = onSpreadsheetIdChange,
            label = { Text(stringResource(Res.string.spreadsheet_id_label)) },
            modifier = Modifier.fillMaxWidth(),
            leadingIcon = { Icon(Icons.Default.TableChart, contentDescription = null) },
            supportingText = { Text(stringResource(Res.string.setup_spreadsheet_hint)) },
            minLines = 2
        )
    }
}

@Composable
private fun SheetsSharePage(
    platformContext: PlatformContext,
    serviceAccountEmail: String?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SetupGuideStep(5, stringResource(Res.string.step_8_title), stringResource(Res.string.step_8_description))
        if (!serviceAccountEmail.isNullOrBlank()) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f),
            ) {
                Text(
                    stringResource(Res.string.setup_sheets_share_email, serviceAccountEmail),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        OutlinedButton(
            onClick = { openExternalUrl(platformContext, "https://sheets.google.com/") },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.setup_open_google_sheets))
        }
    }
}

@Composable
private fun SetupGuideStep(number: Int, title: String, body: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                number.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun FirebaseOrgPage(
    orgId: String,
    onOrgIdChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = orgId,
        onValueChange = onOrgIdChange,
        label = { Text(stringResource(Res.string.firebase_org_id_label)) },
        supportingText = { Text(stringResource(Res.string.firebase_org_id_hint)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        leadingIcon = { Icon(Icons.Default.Business, contentDescription = null) },
    )
}

@Composable
private fun FirebaseProjectPage(
    settingsManager: SettingsManager,
    projectId: String,
    onProjectIdChange: (String) -> Unit,
    applicationId: String,
    onApplicationIdChange: (String) -> Unit,
    apiKey: String,
    onApiKeyChange: (String) -> Unit,
    webClientId: String,
    onWebClientIdChange: (String) -> Unit,
    webClientSecret: String,
    onWebClientSecretChange: (String) -> Unit,
    onOpenTutorial: () -> Unit,
    platformContext: PlatformContext,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { openExternalUrl(platformContext, "https://console.firebase.google.com/") },
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.setup_open_firebase_console))
            }
            OutlinedButton(onClick = onOpenTutorial, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.HelpOutline, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.firebase_tutorial_title))
            }
        }
        FirebaseAdminProjectConfigCard(
            settingsManager = settingsManager,
            projectId = projectId,
            applicationId = applicationId,
            apiKey = apiKey,
            webClientId = webClientId,
            webClientSecret = webClientSecret,
            onProjectIdChange = onProjectIdChange,
            onApplicationIdChange = onApplicationIdChange,
            onApiKeyChange = onApiKeyChange,
            onWebClientIdChange = onWebClientIdChange,
            onWebClientSecretChange = onWebClientSecretChange,
        )
    }
}

@Composable
private fun LanguagePage(selectedLanguage: String, onSelect: (String) -> Unit) {
    val options = listOf(
        "en" to Res.string.language_english,
        "fr" to Res.string.language_french,
        "es" to Res.string.language_spanish,
        "zh-TW" to Res.string.language_chinese,
        "zh-CN" to Res.string.language_chinese_simplified,
        "la" to Res.string.language_latin,
        "hi" to Res.string.language_hindi,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (code, labelRes) ->
            val selected = selectedLanguage == code
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onSelect(code) },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
                    else MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(
                    if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Row(
                    modifier = Modifier.padding(16.dp).fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (selected) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    } else {
                        Spacer(Modifier.size(24.dp))
                    }
                    Text(
                        text = stringResource(labelRes),
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetsPage(
    guestListSheet: String, onGuestListSheet: (String) -> Unit,
    volunteerSheet: String, onVolunteerSheet: (String) -> Unit,
    jobsSheet: String, onJobsSheet: (String) -> Unit,
    volunteerGuestListSheet: String, onVolunteerGuestListSheet: (String) -> Unit,
    jobTypesSheet: String, onJobTypesSheet: (String) -> Unit,
    venuesSheet: String, onVenuesSheet: (String) -> Unit,
    salesItemsSheet: String, onSalesItemsSheet: (String) -> Unit,
    tempGuestListSheet: String, onTempGuestListSheet: (String) -> Unit,
    settingsSheet: String, onSettingsSheet: (String) -> Unit
) {
    var showAdvanced by remember { mutableStateOf(false) }

    @Composable
    fun sheetField(value: String, onValue: (String) -> Unit, labelRes: org.jetbrains.compose.resources.StringResource) {
        OutlinedTextField(
            value = value,
            onValueChange = onValue,
            label = { Text(stringResource(labelRes)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!showAdvanced) {
            OutlinedButton(onClick = { showAdvanced = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ExpandMore, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.setup_sheets_customize))
            }
        } else {
            sheetField(guestListSheet, onGuestListSheet, Res.string.guest_list_sheet_label)
            sheetField(volunteerSheet, onVolunteerSheet, Res.string.volunteer_sheet_label)
            sheetField(jobsSheet, onJobsSheet, Res.string.shifts_sheet_label)
            sheetField(volunteerGuestListSheet, onVolunteerGuestListSheet, Res.string.volunteer_guest_list_sheet_label)
            sheetField(jobTypesSheet, onJobTypesSheet, Res.string.shift_types_sheet_label)
            sheetField(venuesSheet, onVenuesSheet, Res.string.venues_sheet_label)
            sheetField(salesItemsSheet, onSalesItemsSheet, Res.string.sales_items_sheet_label)
            sheetField(tempGuestListSheet, onTempGuestListSheet, Res.string.temp_guest_list_sheet_label)
            sheetField(settingsSheet, onSettingsSheet, Res.string.settings_sheet_label)
        }
    }
}

@Composable
private fun FirstSyncPage(running: Boolean, done: Boolean, error: String?) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
    ) {
        when {
            running -> {
                CircularProgressIndicator(modifier = Modifier.size(56.dp), strokeWidth = 4.dp)
                Text(
                    stringResource(Res.string.setup_wizard_first_sync_message),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            done -> {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(Res.string.setup_finish),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
            error != null -> {
                Icon(
                    Icons.Default.ErrorOutline,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.error
                )
                Text(
                    stringResource(Res.string.setup_wizard_first_sync_error_title),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
                Text(
                    error,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            else -> {
                Icon(
                    Icons.Default.CloudDownload,
                    contentDescription = null,
                    modifier = Modifier.size(56.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    stringResource(Res.string.setup_first_sync_ready),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
