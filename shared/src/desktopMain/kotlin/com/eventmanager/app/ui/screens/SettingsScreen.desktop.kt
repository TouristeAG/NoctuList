package com.eventmanager.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Help
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eventmanager.app.data.sync.FileAppLogger
import com.eventmanager.app.data.sync.ServiceAccountJsonParser
import com.eventmanager.app.data.sync.ServiceAccountKeyInfo
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.ui.components.AppInformationPanel
import com.eventmanager.app.ui.components.FactoryResetSettingsSection
import com.eventmanager.app.ui.components.AppUpdateFlowDialog
import com.eventmanager.app.ui.components.BackgroundAnimationSettingsSection
import com.eventmanager.app.ui.components.UpdateSourcesDialog
import com.eventmanager.app.ui.components.BackgroundAnimationSettingsTarget
import com.eventmanager.app.ui.components.ColorThemePicker
import com.eventmanager.app.ui.components.DesktopAdminNavLayoutPicker
import com.eventmanager.app.ui.components.ThemeModePicker
import com.eventmanager.app.ui.components.toBiometricAdminProfileLink
import com.eventmanager.app.platform.DesktopAppEraser
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.platform.createAppStorage
import com.eventmanager.app.platform.createBiometricAuth
import com.eventmanager.app.platform.createGmailAuth
import com.eventmanager.app.platform.AppBuildInfo
import com.eventmanager.app.platform.hardware.DesktopBleReaderScanner
import com.eventmanager.app.platform.hardware.DesktopExternalNfcReader
import com.eventmanager.app.platform.hardware.WindowsAcsPcscSetup
import com.eventmanager.app.platform.openUrl
import com.eventmanager.app.ui.components.BleReaderPickerDialog
import com.eventmanager.app.ui.components.BiometricAdminVerificationDialog
import com.eventmanager.app.ui.components.BiometricOrgEnrollmentWizard
import com.eventmanager.app.data.sync.BiometricAdminOrgEnrollment
import com.eventmanager.app.data.remote.BackendType
import com.eventmanager.app.data.remote.FirebaseOrgAbbreviation
import com.eventmanager.app.ui.components.ScannerMatch
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.components.ActiveVolunteersDialog
import com.eventmanager.app.ui.components.CleanupInactiveVolunteersDialog
import com.eventmanager.app.ui.components.SyncStatusDialog
import com.eventmanager.app.ui.components.TemporaryGuestAccessSettingsSection
import com.eventmanager.app.ui.platform.ServiceAccountKeyUploadButton
import com.eventmanager.app.ui.platform.GmailOAuthClientUploadButton
import com.eventmanager.app.ui.platform.EmailLogoUploadSection
import com.eventmanager.app.platform.PlatformFileManager
import com.eventmanager.app.ui.platform.AppAppearanceState
import com.eventmanager.app.ui.desktop.AdminNavLayout
import com.eventmanager.app.ui.platform.applyLocaleChange
import com.eventmanager.app.ui.platform.applyThemeAppearanceChange
import com.eventmanager.app.ui.platform.isAppIconChangeSupported
import com.eventmanager.app.ui.platform.showPlatformToast
import com.eventmanager.app.ui.theme.ThemeMode
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private enum class DesktopSettingsNav {
    Sync,
    Email,
    Maintenance,
    Catalog,
    ExternalReader,
    Appearance,
    Announcements,
    Localization,
    Developer,
    About,
}

private data class DesktopSettingsNavItem(
    val id: DesktopSettingsNav,
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
)

@Composable
actual fun SettingsScreen(
    viewModel: EventManagerViewModel,
    onNavigateToJobTypeManagement: () -> Unit,
    onNavigateToVenueManagement: () -> Unit,
    onNavigateToSalesSheetItemManagement: () -> Unit,
    variant: SettingsScreenVariant,
    modifier: Modifier,
    onDesktopAdminNavLayoutChanged: () -> Unit,
    onFactoryResetComplete: () -> Unit,
) {
    val platformContext = LocalPlatformContext.current
    val settingsManager = remember(platformContext) { SettingsManager(createAppStorage(platformContext)) }
    val scope = rememberCoroutineScope()
    val gmailAuth = remember(platformContext) { createGmailAuth(platformContext) }

    var spreadsheetId by remember { mutableStateOf(settingsManager.getSpreadsheetId()) }
    var guestListSheet by remember { mutableStateOf(settingsManager.getGuestListSheet()) }
    var volunteerSheet by remember { mutableStateOf(settingsManager.getVolunteerSheet()) }
    var jobsSheet by remember { mutableStateOf(settingsManager.getJobsSheet()) }
    var jobTypesSheet by remember { mutableStateOf(settingsManager.getJobTypesSheet()) }
    var venuesSheet by remember { mutableStateOf(settingsManager.getVenuesSheet()) }
    var salesItemsSheet by remember { mutableStateOf(settingsManager.getSalesItemsSheet()) }
    var transfersSheet by remember { mutableStateOf(settingsManager.getTransfersSheet()) }
    var tempGuestListSheet by remember { mutableStateOf(settingsManager.getTempGuestListSheet()) }
    var settingsSheet by remember { mutableStateOf(settingsManager.getSettingsSheet()) }
    var syncInterval by remember { mutableStateOf(settingsManager.getSyncInterval()) }
    var uploadStatus by remember { mutableStateOf<String?>(null) }
    var firebaseSignInFeedback by remember { mutableStateOf<String?>(null) }
    var firebaseAuthEmail by remember { mutableStateOf(settingsManager.getFirebaseAuthEmail()) }
    var isFirebaseOrgAdmin by remember { mutableStateOf(false) }
    LaunchedEffect(firebaseAuthEmail, settingsManager.getFirebaseOrgId()) {
        isFirebaseOrgAdmin = com.eventmanager.app.data.remote.FirebaseOrgAdminAccess
            .currentUserIsOrgAdmin(platformContext, settingsManager)
    }
    val canEditInstitutionSettings = com.eventmanager.app.data.security.canEditSyncedInstitutionSettings(
        backendType = settingsManager.getBackendType(),
        isFirebaseOrgAdmin = isFirebaseOrgAdmin,
    )
    val canEditBilleterieSendSetting = com.eventmanager.app.data.security.canEditAnnouncementsNonAdminSendSetting(
        backendType = settingsManager.getBackendType(),
        isFirebaseOrgAdmin = isFirebaseOrgAdmin,
    )
    val venues by viewModel.venues.collectAsState()
    val activeVenues = remember(venues) { venues.filter { it.isActive } }
    val temporaryGuestAccesses by viewModel.temporaryGuestVenueAccesses.collectAsState()
    val temporaryGuestCreditsEnabled by viewModel.temporaryGuestCreditsEnabled.collectAsState()
    val temporaryGuestFeaturesEnabled = viewModel.isTemporaryGuestFeaturesEnabled()
    var migrationWizard by remember { mutableStateOf<com.eventmanager.app.data.remote.MigrationDirection?>(null) }
    var gmailLoading by remember { mutableStateOf(false) }
    var gmailSignedIn by remember { mutableStateOf(gmailAuth.isSignedIn) }
    var gmailEmail by remember { mutableStateOf(gmailAuth.accountEmail) }
    var gmailOAuthConfigured by remember(platformContext) {
        mutableStateOf(PlatformFileManager(platformContext).getGmailOAuthClientFile() != null)
    }
    var gmailOAuthUploadStatus by remember { mutableStateOf<String?>(null) }
    var gmailUseServiceAccount by remember { mutableStateOf(settingsManager.isGmailUseServiceAccount()) }
    var gmailServiceAccountSender by remember { mutableStateOf(settingsManager.getGmailServiceAccountSenderEmail()) }
    val isFirebaseBackend =
        settingsManager.getBackendType() == com.eventmanager.app.data.remote.BackendType.FIREBASE
    // Firebase has no Sheets service account — keep Gmail on OAuth only.
    LaunchedEffect(isFirebaseBackend) {
        if (isFirebaseBackend && gmailUseServiceAccount) {
            gmailUseServiceAccount = false
            settingsManager.setGmailUseServiceAccount(false)
        }
    }
    val platformFileManager = remember(platformContext) { PlatformFileManager(platformContext) }
    val serviceAccountConfigured = platformFileManager.getServiceAccountFile() != null

    var showUpdateResultDialog by remember { mutableStateOf(false) }
    var showUpdateSourcesDialog by remember { mutableStateOf(false) }
    var showBleReaderPicker by remember { mutableStateOf(false) }
    var externalBleReaderMac by remember { mutableStateOf(settingsManager.getExternalBleReaderMac()) }
    var externalBleReaderName by remember { mutableStateOf(settingsManager.getExternalBleReaderName()) }
    var externalReaderTestRunning by remember { mutableStateOf(false) }
    var externalReaderTestDialogMessage by remember { mutableStateOf<String?>(null) }
    var externalReaderTestDialogSuccess by remember { mutableStateOf(false) }
    var showPcscReadersDialog by remember { mutableStateOf(false) }
    var pcscReadersListing by remember { mutableStateOf("") }
    var usbReaderConnected by remember { mutableStateOf(DesktopExternalNfcReader.isUsbConnected()) }
    var usbReaderName by remember { mutableStateOf("") }
    var bleReaderLinked by remember { mutableStateOf(DesktopExternalNfcReader.isBleLinkActive(settingsManager)) }
    var bleLinkState by remember {
        mutableStateOf(DesktopExternalNfcReader.bleLinkState(settingsManager))
    }
    val isWindowsDesktop = remember { WindowsAcsPcscSetup.isWindows() }
    var acsDriverProbeSummary by remember { mutableStateOf<String?>(null) }
    var acsEscapeBusy by remember { mutableStateOf(false) }
    var acsEscapeResultMessage by remember { mutableStateOf<String?>(null) }
    var acsEscapeRelevant by remember { mutableStateOf(true) }
    var acsStackIsNative by remember { mutableStateOf(false) }
    var nfcSetupHelpExpanded by remember { mutableStateOf(false) }
    var nfcAdvancedExpanded by remember { mutableStateOf(false) }

    var showInstructions by remember { mutableStateOf(false) }
    var showGmailOAuthHelp by remember { mutableStateOf(false) }
    var showCleanupDialog by remember { mutableStateOf(false) }
    var showActiveVolunteersDialog by remember { mutableStateOf(false) }
    var showFactoryResetDialog by remember { mutableStateOf(false) }
    var factoryResetInProgress by remember { mutableStateOf(false) }
    var showUninstallDataDialog by remember { mutableStateOf(false) }
    var uninstallDataInProgress by remember { mutableStateOf(false) }
    val cacheClearedMsg = stringResource(Res.string.app_cache_cleared)

    val navItems = buildList {
        if (variant == SettingsScreenVariant.Full) {
            add(DesktopSettingsNavItem(DesktopSettingsNav.Sync, stringResource(Res.string.settings_category_sync), Icons.Default.CloudSync))
            add(DesktopSettingsNavItem(DesktopSettingsNav.Email, stringResource(Res.string.settings_category_email), Icons.Default.Email))
            add(DesktopSettingsNavItem(DesktopSettingsNav.Maintenance, stringResource(Res.string.settings_category_maintenance), Icons.Default.Build))
            add(DesktopSettingsNavItem(DesktopSettingsNav.Catalog, stringResource(Res.string.settings_category_catalog), Icons.Default.Category))
            add(DesktopSettingsNavItem(DesktopSettingsNav.ExternalReader, stringResource(Res.string.settings_category_external_reader), Icons.Default.Nfc))
            add(DesktopSettingsNavItem(DesktopSettingsNav.Appearance, stringResource(Res.string.settings_category_appearance), Icons.Default.Palette))
            add(DesktopSettingsNavItem(DesktopSettingsNav.Announcements, stringResource(Res.string.settings_category_announcements), Icons.Default.Campaign))
            add(DesktopSettingsNavItem(DesktopSettingsNav.Localization, stringResource(Res.string.settings_category_localization), Icons.Default.Language))
            add(DesktopSettingsNavItem(DesktopSettingsNav.Developer, stringResource(Res.string.settings_category_developer), Icons.Default.BugReport))
            add(DesktopSettingsNavItem(DesktopSettingsNav.About, stringResource(Res.string.app_info_title), Icons.Default.Info))
        } else {
            add(DesktopSettingsNavItem(DesktopSettingsNav.Appearance, stringResource(Res.string.settings_category_appearance), Icons.Default.Palette))
            add(DesktopSettingsNavItem(DesktopSettingsNav.Email, stringResource(Res.string.settings_category_email), Icons.Default.Email))
            add(DesktopSettingsNavItem(DesktopSettingsNav.ExternalReader, stringResource(Res.string.settings_category_external_reader), Icons.Default.Nfc))
            add(DesktopSettingsNavItem(DesktopSettingsNav.Announcements, stringResource(Res.string.settings_category_announcements), Icons.Default.Campaign))
            add(DesktopSettingsNavItem(DesktopSettingsNav.Localization, stringResource(Res.string.settings_category_localization), Icons.Default.Language))
            add(DesktopSettingsNavItem(DesktopSettingsNav.About, stringResource(Res.string.app_info_title), Icons.Default.Info))
        }
    }
    var selectedSection by remember(variant) {
        mutableStateOf(
            when (variant) {
                SettingsScreenVariant.BilleterieBasic, SettingsScreenVariant.PosBasic -> DesktopSettingsNav.Appearance
                else -> DesktopSettingsNav.Sync
            }
        )
    }
    val selectedNav = navItems.firstOrNull { it.id == selectedSection } ?: navItems.first()
    LaunchedEffect(navItems.map { it.id }) {
        if (navItems.none { it.id == selectedSection }) {
            selectedSection = navItems.first().id
        }
    }

    val serviceAccountKeyInfo = remember(uploadStatus, selectedSection) {
        platformFileManager.readServiceAccountJson()?.let { ServiceAccountJsonParser.parse(it) }
    }

    val syncStatusMessage by viewModel.syncStatusMessage.collectAsState()
    val showSyncStatusDialog by viewModel.showSyncStatusDialog.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    val announcementsVenues by viewModel.venues.collectAsState()
    val profilePhotosEnabled by viewModel.profilePhotosUploadEnabled.collectAsState()
    val guestFormsEnabled by viewModel.guestFormsEnabled.collectAsState()
    val billeterieSendEnabled by viewModel.announcementsBilleterieSendEnabled.collectAsState()

    val saveLabel = stringResource(Res.string.save)
    val gmailAuthSuccessMsg = stringResource(Res.string.email_gmail_auth_success)
    val gmailNotSignedInMsg = stringResource(Res.string.email_gmail_not_signed_in)
    val gmailRevokedMsg = stringResource(Res.string.email_gmail_auth_revoked)
    val gmailOAuthMissingMsg = stringResource(Res.string.email_gmail_oauth_client_missing)
    val gmailOAuthInvalidMsg = stringResource(Res.string.invalid_gmail_oauth_client_json)
    val gmailServiceAccountMissingMsg = stringResource(Res.string.email_gmail_service_account_missing)
    val gmailSenderMissingMsg = stringResource(Res.string.email_gmail_service_account_sender_label)
    val gmailInvalidServiceAccountMsg = stringResource(Res.string.invalid_service_account_json)
    val readerClearedToast = stringResource(Res.string.external_reader_cleared_toast)

    fun resolveGmailSignInErrorMessage(errorCode: String?): String = when (errorCode) {
        "missing_oauth_client" -> gmailOAuthMissingMsg
        "invalid_oauth_client" -> gmailOAuthInvalidMsg
        "missing_service_account" -> gmailServiceAccountMissingMsg
        "missing_sender_email" -> gmailSenderMissingMsg
        "invalid_service_account" -> gmailInvalidServiceAccountMsg
        null, "" -> gmailNotSignedInMsg
        else -> errorCode
    }

    fun saveSheetsConfig() {
        settingsManager.saveSpreadsheetId(spreadsheetId.trim())
        settingsManager.saveGuestListSheet(guestListSheet.trim())
        settingsManager.saveVolunteerSheet(volunteerSheet.trim())
        settingsManager.saveJobsSheet(jobsSheet.trim())
        settingsManager.saveJobTypesSheet(jobTypesSheet.trim())
        settingsManager.saveVenuesSheet(venuesSheet.trim())
        settingsManager.saveSalesItemsSheet(salesItemsSheet.trim())
        settingsManager.saveTransfersSheet(transfersSheet.trim())
        settingsManager.saveTempGuestListSheet(tempGuestListSheet.trim())
        settingsManager.saveSettingsSheet(settingsSheet.trim())
        showPlatformToast(platformContext, saveLabel)
    }

    LaunchedEffect(selectedSection) {
        if (selectedSection != DesktopSettingsNav.ExternalReader) return@LaunchedEffect
        while (isActive) {
            val status = withContext(Dispatchers.IO) {
                DesktopExternalNfcReader.refreshStatus(settingsManager)
            }
            usbReaderConnected = status.usbConnected
            usbReaderName = status.usbName.orEmpty()
            bleReaderLinked = status.bleAvailable
            bleLinkState = status.bleLinkState
            delay(1500)
        }
    }

    Row(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
    ) {
        DesktopSettingsSidebar(
            items = navItems,
            selected = selectedSection,
            onSelect = { selectedSection = it },
            subtitle = if (variant == SettingsScreenVariant.Full) {
                stringResource(Res.string.settings_subtitle)
            } else null,
        )

        HorizontalDivider(
            modifier = Modifier
                .fillMaxHeight()
                .width(1.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.45f),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.background),
        ) {
            DesktopSettingsDetailHeader(
                title = selectedNav.title,
                icon = selectedNav.icon,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 36.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // Variant-specific background animation is embedded in Appearance below.

        if (variant == SettingsScreenVariant.Full) {
        DesktopSettingsCategory(
            section = DesktopSettingsNav.Sync,
            selectedSection = selectedSection,
            title = stringResource(Res.string.settings_category_sync),
            icon = Icons.Default.CloudSync,
        ) {
            com.eventmanager.app.ui.components.SyncSetupInstructionsButton(
                backendType = settingsManager.getBackendType(),
                onClick = { showInstructions = true },
            )

            if (variant == SettingsScreenVariant.Full &&
                settingsManager.getBackendType() == com.eventmanager.app.data.remote.BackendType.SHEETS
            ) {
                OutlinedTextField(
                    value = spreadsheetId,
                    onValueChange = { spreadsheetId = it },
                    label = { Text(stringResource(Res.string.spreadsheet_id_label)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                SheetNameField(stringResource(Res.string.guest_list_sheet_label), guestListSheet) { guestListSheet = it }
                SheetNameField(stringResource(Res.string.volunteer_sheet_label), volunteerSheet) { volunteerSheet = it }
                SheetNameField(stringResource(Res.string.shifts_sheet_label), jobsSheet) { jobsSheet = it }
                SheetNameField(stringResource(Res.string.shift_types_sheet_label), jobTypesSheet) { jobTypesSheet = it }
                SheetNameField(stringResource(Res.string.venues_sheet_label), venuesSheet) { venuesSheet = it }
                SheetNameField(stringResource(Res.string.sales_items_sheet_label), salesItemsSheet) { salesItemsSheet = it }
                SheetNameField(stringResource(Res.string.transfers_sheet_label), transfersSheet) { transfersSheet = it }
                SheetNameField(stringResource(Res.string.temp_guest_list_sheet_label), tempGuestListSheet) { tempGuestListSheet = it }
                SheetNameField(stringResource(Res.string.settings_sheet_label), settingsSheet) { settingsSheet = it }

                ServiceAccountKeyUploadButton(
                    platformContext = platformContext,
                    onStatusUpdate = { uploadStatus = it },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                DesktopServiceAccountKeyInfoPanel(keyInfo = serviceAccountKeyInfo)
                uploadStatus?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { saveSheetsConfig() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.save_sheets_config))
                }
                com.eventmanager.app.ui.components.SheetsMigrateToFirebaseButton(
                    onClick = {
                        migrationWizard = com.eventmanager.app.data.remote.MigrationDirection.SHEETS_TO_FIREBASE
                    },
                    projectId = settingsManager.getFirebaseProjectId(),
                )
            }

            if (variant == SettingsScreenVariant.Full &&
                settingsManager.getBackendType() == com.eventmanager.app.data.remote.BackendType.FIREBASE
            ) {
                var firebaseConfiguredOrgs by remember { mutableStateOf(settingsManager.getFirebaseConfiguredOrgs()) }
                var firebaseProjectId by remember { mutableStateOf(settingsManager.getFirebaseProjectId()) }
                var firebaseApiKey by remember { mutableStateOf(settingsManager.getFirebaseApiKey()) }
                var firebaseApplicationId by remember { mutableStateOf(settingsManager.getFirebaseApplicationId()) }
                var allowedEmailDomains by remember {
                    mutableStateOf(settingsManager.getAllowedEmailDomains())
                }
                com.eventmanager.app.ui.components.FirebaseSyncSettingsSection(
                    configuredOrgs = firebaseConfiguredOrgs,
                    authEmail = firebaseAuthEmail.ifBlank { null },
                    onConfiguredOrgsChange = {
                        firebaseConfiguredOrgs = it
                        viewModel.updateFirebaseConfiguredOrgsLocal(it)
                    },
                    onOrgIdCommitted = viewModel::provisionFirebaseOrg,
                    onSignIn = {
                        scope.launch {
                            firebaseSignInFeedback = null
                            when (val result = com.eventmanager.app.data.remote.createFirebaseAuthService(platformContext).signInWithGoogle()) {
                                is com.eventmanager.app.data.remote.FirebaseAuthResult.Success -> {
                                    firebaseSignInFeedback = null
                                    firebaseAuthEmail = result.email.orEmpty()
                                    settingsManager.setFirebaseAuthEmail(result.email.orEmpty())
                                    val org = settingsManager.getFirebaseOrgId()
                                    if (org.isNotBlank() && result.uid.isNotBlank()) {
                                        val gateway = com.eventmanager.app.data.remote.createFirestoreGateway(
                                            platformContext,
                                            settingsManager,
                                        )
                                        runCatching {
                                            com.eventmanager.app.data.remote.FirebaseMemberSignIn.afterGoogleSignIn(
                                                gateway = gateway,
                                                settings = settingsManager,
                                                uid = result.uid,
                                                email = result.email,
                                                joinWithBootstrapCode = settingsManager.getFirebaseBootstrapCode().isNotBlank(),
                                            )
                                        }.onFailure { e ->
                                            firebaseSignInFeedback = e.message
                                            uploadStatus = e.message
                                        }
                                    }
                                }
                                is com.eventmanager.app.data.remote.FirebaseAuthResult.Error -> {
                                    firebaseSignInFeedback = result.message
                                    uploadStatus = result.message
                                }
                            }
                        }
                    },
                    onSignOut = {
                        scope.launch {
                            com.eventmanager.app.data.remote.createFirebaseAuthService(platformContext).signOut()
                            firebaseAuthEmail = ""
                            settingsManager.setFirebaseAuthEmail("")
                            firebaseSignInFeedback = null
                        }
                    },
                    onMigrateToSheets = {
                        migrationWizard = com.eventmanager.app.data.remote.MigrationDirection.FIREBASE_TO_SHEETS
                    },
                    onMirrorExport = {
                        viewModel.exportSheetsMirrorNow()
                    },
                    platformContext = platformContext,
                    onMirrorSettingsChanged = { viewModel.onSheetsMirrorSettingsChanged() },
                    settingsManager = settingsManager,
                    profilePhotosEnabled = profilePhotosEnabled,
                    onProfilePhotosEnabledChange = { viewModel.setProfilePhotosEnabled(it) },
                    guestFormsEnabled = guestFormsEnabled,
                    onGuestFormsEnabledChange = { viewModel.setGuestFormsEnabled(it) },
                    guestFormSiteOrigin = viewModel.guestFormSiteOrigin(),
                    projectId = firebaseProjectId,
                    apiKey = firebaseApiKey,
                    applicationId = firebaseApplicationId,
                    onProjectIdChange = {
                        firebaseProjectId = it
                        settingsManager.setFirebaseProjectId(it)
                    },
                    onApiKeyChange = {
                        firebaseApiKey = it
                        settingsManager.setFirebaseApiKey(it)
                    },
                    onApplicationIdChange = {
                        firebaseApplicationId = it
                        settingsManager.setFirebaseApplicationId(it)
                    },
                    webClientId = settingsManager.getFirebaseWebClientId(),
                    onWebClientIdChange = { settingsManager.setFirebaseWebClientId(it) },
                    webClientSecret = settingsManager.getFirebaseWebClientSecret(),
                    onWebClientSecretChange = { settingsManager.setFirebaseWebClientSecret(it) },
                    allowedEmailDomains = allowedEmailDomains,
                    onAllowedEmailDomainsChange = { domains ->
                        allowedEmailDomains = domains
                        settingsManager.setAllowedEmailDomains(domains)
                        scope.launch {
                            val org = settingsManager.resolveWritableFirebaseOrgId()
                            if (org.isBlank()) return@launch
                            val gateway = com.eventmanager.app.data.remote.createFirestoreGateway(
                                platformContext,
                                settingsManager,
                            )
                            runCatching {
                                com.eventmanager.app.data.remote.MemberRoleAdmin.publishAllowedEmailDomains(
                                    gateway = gateway,
                                    orgId = org,
                                    domains = domains,
                                )
                            }.onFailure { uploadStatus = it.message }
                        }
                    },
                    signInFeedback = firebaseSignInFeedback,
                )
            }

            if (variant == SettingsScreenVariant.Full) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
            }

            if (com.eventmanager.app.ui.components.shouldShowSyncInterval(settingsManager.getBackendType())) {
                DesktopSyncIntervalSettings(
                    settingsManager = settingsManager,
                    viewModel = viewModel,
                    syncInterval = syncInterval,
                    onSyncIntervalChange = { syncInterval = it }
                )
            }

            if (variant == SettingsScreenVariant.Full &&
                com.eventmanager.app.ui.components.shouldShowSheetsManualSyncControls(settingsManager.getBackendType())
            ) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { viewModel.performFullSync() },
                        enabled = !isSyncing,
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isSyncing) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Default.Sync, contentDescription = null)
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.manual_sync_now))
                    }
                    OutlinedButton(
                        onClick = { viewModel.testSyncStatus() },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(Res.string.test_sync_status))
                    }
                }
            }
        }
        }

        if (variant == SettingsScreenVariant.Full || variant == SettingsScreenVariant.BilleterieBasic || variant == SettingsScreenVariant.PosBasic) {
            DesktopSettingsCategory(
            section = DesktopSettingsNav.Email,
            selectedSection = selectedSection,
            title = stringResource(Res.string.settings_category_email),
            icon = Icons.Default.Email,
        ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.email_gmail_auth_title),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { showGmailOAuthHelp = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Help,
                            contentDescription = stringResource(Res.string.email_gmail_oauth_help_title),
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = stringResource(Res.string.email_gmail_auth_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                if (!isFirebaseBackend) {
                    DesktopSettingsToggleRow(
                        title = stringResource(Res.string.email_gmail_use_service_account),
                        description = stringResource(Res.string.email_gmail_use_service_account_description),
                        checked = gmailUseServiceAccount,
                        onCheckedChange = { enabled ->
                            gmailUseServiceAccount = enabled
                            settingsManager.setGmailUseServiceAccount(enabled)
                            scope.launch {
                                gmailAuth.signOut()
                                settingsManager.clearGmailAuth()
                                gmailSignedIn = false
                                gmailEmail = null
                            }
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                }
                if (!isFirebaseBackend && gmailUseServiceAccount) {
                    Text(
                        text = if (serviceAccountConfigured) {
                            stringResource(Res.string.email_gmail_service_account_configured)
                        } else {
                            stringResource(Res.string.email_gmail_service_account_missing)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (serviceAccountConfigured) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = gmailServiceAccountSender,
                        onValueChange = {
                            gmailServiceAccountSender = it
                            settingsManager.saveGmailServiceAccountSenderEmail(it)
                        },
                        label = { Text(stringResource(Res.string.email_gmail_service_account_sender_label)) },
                        supportingText = {
                            Text(stringResource(Res.string.email_gmail_service_account_sender_description))
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !gmailSignedIn
                    )
                } else {
                    Text(
                        text = stringResource(Res.string.email_gmail_oauth_client_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    GmailOAuthClientUploadButton(
                        platformContext = platformContext,
                        onStatusUpdate = { gmailOAuthUploadStatus = it },
                        onConfiguredChanged = { gmailOAuthConfigured = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = if (gmailOAuthConfigured) {
                            stringResource(Res.string.email_gmail_oauth_client_configured)
                        } else {
                            stringResource(Res.string.email_gmail_oauth_client_missing)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = if (gmailOAuthConfigured) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        }
                    )
                    gmailOAuthUploadStatus?.let { status ->
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))

                if (gmailSignedIn && gmailEmail != null) {
                    Text(
                        text = stringResource(Res.string.email_gmail_signed_in_as, gmailEmail!!),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                gmailLoading = true
                                gmailAuth.signOut()
                                settingsManager.clearGmailAuth()
                                gmailSignedIn = false
                                gmailEmail = null
                                gmailLoading = false
                                showPlatformToast(platformContext, gmailRevokedMsg)
                            }
                        },
                        enabled = !gmailLoading
                    ) {
                        Text(stringResource(Res.string.email_gmail_sign_out))
                    }
                } else {
                    Button(
                        onClick = {
                            scope.launch {
                                gmailLoading = true
                                if (!isFirebaseBackend && gmailUseServiceAccount) {
                                    settingsManager.saveGmailServiceAccountSenderEmail(gmailServiceAccountSender.trim())
                                }
                                val ok = gmailAuth.signIn()
                                gmailSignedIn = ok
                                gmailEmail = gmailAuth.accountEmail
                                gmailLoading = false
                                if (ok) {
                                    gmailEmail?.let { settingsManager.saveGmailAccount(it) }
                                    showPlatformToast(platformContext, gmailAuthSuccessMsg)
                                } else {
                                    val errorMsg = resolveGmailSignInErrorMessage(gmailAuth.lastSignInError)
                                    showPlatformToast(platformContext, errorMsg)
                                }
                            }
                        },
                        enabled = !gmailLoading && (
                            if (!isFirebaseBackend && gmailUseServiceAccount) {
                                serviceAccountConfigured && gmailServiceAccountSender.isNotBlank()
                            } else {
                                gmailOAuthConfigured
                            }
                            )
                    ) {
                        if (gmailLoading) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(Res.string.email_gmail_sign_in))
                        }
                    }
                }

                HorizontalDivider()

                DesktopEmailTemplateSettings(
                    settingsManager,
                    onSyncedSettingChanged = {
                        viewModel.backupInstitutionSettingsToSheets()
                    },
                    editable = canEditInstitutionSettings,
                    temporaryFeaturesEnabled = temporaryGuestFeaturesEnabled,
                    onInstitutionLogoBytes = { viewModel.setInstitutionLogo(it) },
                )
            }
        }

        if (variant == SettingsScreenVariant.Full) {
            DesktopSettingsCategory(
            section = DesktopSettingsNav.Maintenance,
            selectedSection = selectedSection,
            title = stringResource(Res.string.settings_category_maintenance),
            icon = Icons.Default.Build,
        ) {
                Button(
                    onClick = { showActiveVolunteersDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.People, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.view_active_volunteers))
                }
                Button(
                    onClick = { showCleanupDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.cleanup_inactive_volunteers))
                }
                Button(
                    onClick = {
                        viewModel.checkForAppUpdates()
                        showUpdateResultDialog = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.SystemUpdate, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.check_for_updates))
                }
                TextButton(
                    onClick = { showUpdateSourcesDialog = true },
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 4.dp),
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = stringResource(Res.string.change_update_sources),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                OutlinedButton(
                    onClick = {
                        viewModel.clearAppData()
                        showPlatformToast(platformContext, cacheClearedMsg)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Clear, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.clear_app_cache))
                }
                FactoryResetSettingsSection(
                    onResetClick = { showFactoryResetDialog = true },
                )
                Spacer(Modifier.height(8.dp))
                UninstallDataSection(
                    onUninstallClick = { showUninstallDataDialog = true },
                )
            }

            DesktopSettingsCategory(
                section = DesktopSettingsNav.Catalog,
                selectedSection = selectedSection,
                title = stringResource(Res.string.settings_category_catalog),
                icon = Icons.Default.Category,
            ) {
                DesktopManagementCard(
                    title = stringResource(Res.string.shift_type_management_title),
                    description = stringResource(Res.string.shift_type_management_description),
                    icon = Icons.Default.Work,
                    buttonLabel = stringResource(Res.string.manage_shift_types),
                    onClick = onNavigateToJobTypeManagement,
                )
                DesktopManagementCard(
                    title = stringResource(Res.string.sales_items_management_title),
                    description = stringResource(Res.string.sales_items_management_description),
                    icon = Icons.Default.PointOfSale,
                    buttonLabel = stringResource(Res.string.manage_sales_items),
                    onClick = onNavigateToSalesSheetItemManagement,
                )
                DesktopManagementCard(
                    title = stringResource(Res.string.venue_management_title),
                    description = stringResource(Res.string.venue_management_description),
                    icon = Icons.Default.LocationOn,
                    buttonLabel = stringResource(Res.string.manage_venues),
                    onClick = onNavigateToVenueManagement,
                )
                if (temporaryGuestFeaturesEnabled) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(Res.string.temp_guest_access_settings_title),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    TemporaryGuestAccessSettingsSection(
                        activeVenues = activeVenues,
                        accesses = temporaryGuestAccesses,
                        creditsEnabled = temporaryGuestCreditsEnabled,
                        canEdit = canEditInstitutionSettings,
                        onAddAccess = { venueName, name ->
                            viewModel.addTemporaryGuestVenueAccess(venueName, name)
                        },
                        onRemoveAccess = { viewModel.removeTemporaryGuestVenueAccess(it) },
                        onCreditsEnabledChange = { viewModel.setTemporaryGuestCreditsEnabled(it) },
                    )
                    if (!canEditInstitutionSettings) {
                        Text(
                            text = stringResource(Res.string.institution_settings_firebase_admin_only),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        DesktopSettingsCategory(
            section = DesktopSettingsNav.ExternalReader,
            selectedSection = selectedSection,
            title = stringResource(Res.string.settings_category_external_reader),
            icon = Icons.Default.Nfc,
        ) {
            val blePcscSupported = DesktopBleReaderScanner.isBlePcscSupportedOnPlatform()
            val hasUsbReader = usbReaderConnected
            val hasBleConfigured = externalBleReaderMac.isNotBlank()
            val hasLiveReader = hasUsbReader || bleReaderLinked

            Text(
                text = stringResource(Res.string.desktop_external_reader_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!hasUsbReader && !hasBleConfigured) {
                Text(
                    text = stringResource(Res.string.desktop_nfc_active_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (hasUsbReader || hasBleConfigured) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (hasUsbReader) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Usb,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        stringResource(Res.string.desktop_nfc_usb_connected),
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (usbReaderName.isNotBlank()) {
                                        Text(
                                            usbReaderName,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontFamily = FontFamily.Monospace,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        if (hasUsbReader && hasBleConfigured) {
                            HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                        if (hasBleConfigured) {
                            val bleStatusLabel = when (bleLinkState) {
                                DesktopExternalNfcReader.BleLinkState.Ready ->
                                    stringResource(Res.string.desktop_nfc_ble_connected)
                                DesktopExternalNfcReader.BleLinkState.Connecting ->
                                    stringResource(Res.string.desktop_nfc_ble_connecting)
                                else ->
                                    stringResource(Res.string.desktop_nfc_ble_not_ready)
                            }
                            val bleStatusColor = when (bleLinkState) {
                                DesktopExternalNfcReader.BleLinkState.Ready ->
                                    MaterialTheme.colorScheme.primary
                                DesktopExternalNfcReader.BleLinkState.Connecting ->
                                    MaterialTheme.colorScheme.tertiary
                                else ->
                                    MaterialTheme.colorScheme.error
                            }
                            val bleHint = when (bleLinkState) {
                                DesktopExternalNfcReader.BleLinkState.Ready -> null
                                DesktopExternalNfcReader.BleLinkState.Connecting ->
                                    stringResource(Res.string.desktop_nfc_ble_connecting_hint)
                                else -> stringResource(Res.string.desktop_nfc_ble_wake_hint)
                            }
                            Row(verticalAlignment = Alignment.Top) {
                                Icon(
                                    Icons.Default.Bluetooth,
                                    contentDescription = null,
                                    tint = bleStatusColor
                                )
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = bleStatusLabel,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold,
                                        color = bleStatusColor
                                    )
                                    Text(
                                        text = externalBleReaderName.ifBlank {
                                            stringResource(Res.string.ble_reader_unnamed)
                                        },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = externalBleReaderMac,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    if (bleHint != null) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = bleHint,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = bleStatusColor
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (hasLiveReader || hasBleConfigured) {
                Button(
                    onClick = {
                        if (externalReaderTestRunning) return@Button
                        externalReaderTestRunning = true
                        scope.launch {
                            val result = withContext(Dispatchers.IO) {
                                if (hasUsbReader) {
                                    DesktopExternalNfcReader.runDiagnostic()
                                } else {
                                    DesktopExternalNfcReader.runBleDiagnostic(settingsManager)
                                }
                            }
                            externalReaderTestDialogSuccess = result.success
                            externalReaderTestDialogMessage = result.details
                            externalReaderTestRunning = false
                        }
                    },
                    enabled = !externalReaderTestRunning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (externalReaderTestRunning) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.external_reader_testing))
                    } else {
                        Icon(Icons.Default.Sync, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.desktop_nfc_test_reader))
                    }
                }
            }

            if (blePcscSupported) {
                if (hasBleConfigured) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showBleReaderPicker = true },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.BluetoothSearching, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(Res.string.external_reader_change))
                        }
                        OutlinedButton(
                            onClick = {
                                DesktopExternalNfcReader.shutdownBle()
                                settingsManager.clearExternalBleReader()
                                externalBleReaderMac = ""
                                externalBleReaderName = ""
                                showPlatformToast(platformContext, readerClearedToast)
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Default.Clear, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(Res.string.external_reader_forget))
                        }
                    }
                } else {
                    OutlinedButton(
                        onClick = { showBleReaderPicker = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.BluetoothSearching, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.desktop_nfc_use_bluetooth))
                    }
                }
            }

            DesktopNfcExpandSection(
                title = stringResource(Res.string.desktop_nfc_setup_title),
                expanded = nfcSetupHelpExpanded,
                onToggle = { nfcSetupHelpExpanded = !nfcSetupHelpExpanded }
            ) {
                Text(
                    text = stringResource(Res.string.desktop_acs_prefer_usb),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(Res.string.desktop_nfc_setup_usb),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (blePcscSupported) {
                    Text(
                        text = stringResource(Res.string.desktop_nfc_setup_ble),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val os = System.getProperty("os.name").orEmpty().lowercase()
                    Text(
                        text = if (os.contains("mac") || os.contains("darwin")) {
                            stringResource(Res.string.desktop_ble_unsupported_macos)
                        } else {
                            stringResource(Res.string.desktop_ble_unsupported_linux)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = { openUrl(WindowsAcsPcscSetup.ACS_ACR1255_DRIVER_URL) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.desktop_acs_open_drivers))
                }
            }

            DesktopNfcExpandSection(
                title = stringResource(Res.string.desktop_nfc_advanced_title),
                expanded = nfcAdvancedExpanded,
                onToggle = { nfcAdvancedExpanded = !nfcAdvancedExpanded }
            ) {
                Text(
                    text = stringResource(Res.string.desktop_nfc_advanced_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            pcscReadersListing = withContext(Dispatchers.IO) {
                                DesktopExternalNfcReader.listPcscReadersReport()
                            }
                            showPcscReadersDialog = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.List, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.desktop_list_pcsc_readers))
                }
                if (isWindowsDesktop) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val probe = withContext(Dispatchers.IO) {
                                    WindowsAcsPcscSetup.probeDrivers()
                                }
                                acsDriverProbeSummary = probe.summary
                                acsEscapeRelevant = probe.escapeRelevant ||
                                    probe.stack == WindowsAcsPcscSetup.DriverStack.MICROSOFT_CCID
                                acsStackIsNative =
                                    probe.stack == WindowsAcsPcscSetup.DriverStack.ACS
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Info, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.desktop_acs_probe_drivers))
                    }
                    if (acsStackIsNative && !acsEscapeRelevant) {
                        Text(
                            text = stringResource(Res.string.desktop_acs_escape_not_needed),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Text(
                            text = stringResource(Res.string.desktop_acs_windows_usb_banner),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedButton(
                            onClick = {
                                if (acsEscapeBusy) return@OutlinedButton
                                acsEscapeBusy = true
                                acsEscapeResultMessage = null
                                scope.launch {
                                    val result = withContext(Dispatchers.IO) {
                                        WindowsAcsPcscSetup.enableEscapeCommandElevated()
                                    }
                                    acsEscapeResultMessage = result.message
                                    val probe = withContext(Dispatchers.IO) {
                                        WindowsAcsPcscSetup.probeDrivers()
                                    }
                                    acsDriverProbeSummary = probe.summary
                                    acsEscapeRelevant = probe.escapeRelevant ||
                                        probe.stack == WindowsAcsPcscSetup.DriverStack.MICROSOFT_CCID
                                    acsStackIsNative =
                                        probe.stack == WindowsAcsPcscSetup.DriverStack.ACS
                                    acsEscapeBusy = false
                                }
                            },
                            enabled = !acsEscapeBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (acsEscapeBusy) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(Res.string.desktop_acs_escape_running))
                            } else {
                                Icon(Icons.Default.Security, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text(stringResource(Res.string.desktop_acs_enable_escape))
                            }
                        }
                    }
                    acsDriverProbeSummary?.let { summary ->
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    acsEscapeResultMessage?.let { msg ->
                        Text(
                            text = msg,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        if (showBleReaderPicker) {
            BleReaderPickerDialog(
                platformContext = platformContext,
                onDismiss = { showBleReaderPicker = false },
                onPicked = { mac, name ->
                    settingsManager.saveExternalBleReaderMac(mac)
                    settingsManager.saveExternalBleReaderName(name)
                    externalBleReaderMac = mac
                    externalBleReaderName = name
                    showBleReaderPicker = false
                    scope.launch {
                        DesktopExternalNfcReader.prepareBleReader(settingsManager)
                        bleReaderLinked = DesktopExternalNfcReader.isBleLinkActive(settingsManager)
                        showPlatformToast(
                            platformContext,
                            getString(Res.string.external_reader_saved_toast, name)
                        )
                    }
                }
            )
        }

        DesktopSettingsCategory(
            section = DesktopSettingsNav.Appearance,
            selectedSection = selectedSection,
            title = stringResource(Res.string.settings_category_appearance),
            icon = Icons.Default.Palette,
        ) {
            when (variant) {
                SettingsScreenVariant.BilleterieBasic -> {
                    BackgroundAnimationSettingsSection(
                        settingsManager = settingsManager,
                        isDesktop = true,
                        target = BackgroundAnimationSettingsTarget.Billeterie,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                SettingsScreenVariant.PosBasic -> {
                    BackgroundAnimationSettingsSection(
                        settingsManager = settingsManager,
                        isDesktop = true,
                        target = BackgroundAnimationSettingsTarget.Pos,
                    )
                    Spacer(Modifier.height(8.dp))
                }
                else -> Unit
            }

            var pageAnimationsEnabled by remember {
                mutableStateOf(settingsManager.isPageAnimationsEnabled())
            }
            DesktopSettingsToggleRow(
                title = stringResource(Res.string.page_animations_title),
                description = stringResource(Res.string.page_animations_description),
                checked = pageAnimationsEnabled,
                onCheckedChange = {
                    pageAnimationsEnabled = it
                    settingsManager.setPageAnimationsEnabled(it)
                },
            )
            Spacer(Modifier.height(16.dp))

            var selectedThemeMode by remember { mutableStateOf(ThemeMode.fromString(settingsManager.getThemeMode())) }

            ThemeModePicker(
                selectedMode = selectedThemeMode,
                onSelect = { mode ->
                    selectedThemeMode = mode
                    settingsManager.saveThemeMode(mode.value)
                    applyThemeAppearanceChange(platformContext)
                },
            )

            Spacer(Modifier.height(16.dp))

            var selectedColorTheme by remember { mutableStateOf(settingsManager.getColorTheme()) }
            LaunchedEffect(Unit) {
                if (selectedColorTheme == "custom") {
                    selectedColorTheme = "system"
                    settingsManager.saveColorTheme("system")
                    applyThemeAppearanceChange(platformContext)
                }
            }
            ColorThemePicker(
                selectedThemeKey = selectedColorTheme,
                previewDark = when (selectedThemeMode) {
                    ThemeMode.LIGHT -> false
                    ThemeMode.DARK -> true
                    ThemeMode.DEFAULT -> isSystemInDarkTheme()
                },
                onSelect = { key ->
                    selectedColorTheme = key
                    settingsManager.saveColorTheme(key)
                    applyThemeAppearanceChange(platformContext)
                },
            )

            Spacer(Modifier.height(16.dp))

            if (variant == SettingsScreenVariant.Full) {
            var adminNavLayout by remember {
                mutableStateOf(settingsManager.getDesktopAdminNavLayout())
            }
            DesktopAdminNavLayoutPicker(
                selectedLayout = adminNavLayout,
                onSelect = { layout ->
                    adminNavLayout = layout
                    settingsManager.setDesktopAdminNavLayout(layout)
                    onDesktopAdminNavLayoutChanged()
                },
            )

            Spacer(Modifier.height(8.dp))

            BackgroundAnimationSettingsSection(
                settingsManager = settingsManager,
                isDesktop = true,
                target = BackgroundAnimationSettingsTarget.Admin,
            )
            }

            var peopleCounterVisible by remember { mutableStateOf(settingsManager.isPeopleCounterVisible()) }
            DesktopSettingsToggleRow(
                title = stringResource(Res.string.people_counter_visibility_title),
                description = stringResource(Res.string.people_counter_visibility_description),
                checked = peopleCounterVisible,
                onCheckedChange = {
                    peopleCounterVisible = it
                    settingsManager.setPeopleCounterVisible(it)
                    if (it) scope.launch { viewModel.refreshVenuesForPeopleCounterQuietly() }
                }
            )

            if (variant == SettingsScreenVariant.BilleterieBasic) {
                var billeterieClockVisible by remember { mutableStateOf(settingsManager.isBilleterieClockVisible()) }
                DesktopSettingsToggleRow(
                    title = stringResource(Res.string.billeterie_clock_visibility_title),
                    description = stringResource(Res.string.billeterie_clock_visibility_description),
                    checked = billeterieClockVisible,
                    onCheckedChange = {
                        billeterieClockVisible = it
                        settingsManager.setBilleterieClockVisible(it)
                    }
                )
            }

            if (variant == SettingsScreenVariant.Full) {
                var statisticsVisible by remember { mutableStateOf(settingsManager.isStatisticsVisible()) }
                DesktopSettingsToggleRow(
                    title = stringResource(Res.string.statistics_visibility_title),
                    description = stringResource(Res.string.statistics_visibility_description),
                    checked = statisticsVisible,
                    onCheckedChange = {
                        statisticsVisible = it
                        settingsManager.setStatisticsVisible(it)
                    }
                )
            }
        }

        DesktopSettingsCategory(
            section = DesktopSettingsNav.Announcements,
            selectedSection = selectedSection,
            title = stringResource(Res.string.settings_category_announcements),
            icon = Icons.Default.Campaign,
        ) {
            val activeVenues = remember(announcementsVenues) { announcementsVenues.filter { it.isActive } }
            com.eventmanager.app.ui.components.AnnouncementsSettingsContent(
                settingsManager = settingsManager,
                activeVenues = activeVenues,
                mode = if (variant == SettingsScreenVariant.Full) {
                    com.eventmanager.app.ui.components.AnnouncementsSettingsMode.Admin
                } else {
                    com.eventmanager.app.ui.components.AnnouncementsSettingsMode.Standard
                },
                billeterieSendEnabled = billeterieSendEnabled,
                canEditBilleterieSend = canEditBilleterieSendSetting,
                onBilleterieSendEnabledChange = if (variant == SettingsScreenVariant.Full) {
                    { enabled -> viewModel.setAnnouncementsBilleterieSendEnabled(enabled) }
                } else {
                    null
                },
            )
        }

        DesktopSettingsCategory(
            section = DesktopSettingsNav.Localization,
            selectedSection = selectedSection,
            title = stringResource(Res.string.settings_category_localization),
            icon = Icons.Default.Language,
        ) {
            val languageCode by AppAppearanceState::localeCode
            DesktopLanguagePicker(
                selectedLanguage = languageCode ?: settingsManager.getLanguage(),
                onLanguageSelected = { code ->
                    settingsManager.saveLanguage(code)
                    applyLocaleChange(platformContext)
                },
            )

            Spacer(Modifier.height(12.dp))
            DesktopDateTimeFormatSettings(settingsManager)
            Spacer(Modifier.height(12.dp))
            if (!canEditInstitutionSettings) {
                Text(
                    stringResource(Res.string.institution_settings_firebase_admin_only),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(modifier.height(8.dp))
            }
            DesktopDateChangeOffset(
                settingsManager,
                onSyncedSettingChanged = {
                    viewModel.backupInstitutionSettingsToSheets()
                },
                editable = canEditInstitutionSettings,
            )
            Spacer(modifier.height(12.dp))
            DesktopCurrencyPicker(
                selectedCurrency = settingsManager.getCurrencyCode(),
                onCurrencySelected = {
                    settingsManager.saveCurrencyCode(it)
                    viewModel.backupInstitutionSettingsToSheets()
                },
                editable = canEditInstitutionSettings,
            )
            if (variant == SettingsScreenVariant.Full) {
                Spacer(modifier.height(12.dp))
                DesktopPurchaseCreditBufferSetting(
                    settingsManager = settingsManager,
                    onSyncedSettingChanged = {
                        viewModel.backupInstitutionSettingsToSheets()
                    },
                    editable = canEditInstitutionSettings,
                )
            }
        }

        if (variant == SettingsScreenVariant.Full) {
            DesktopSettingsCategory(
            section = DesktopSettingsNav.Developer,
            selectedSection = selectedSection,
            title = stringResource(Res.string.settings_category_developer),
            icon = Icons.Default.BugReport,
        ) {
                DesktopDeveloperSettings(
                    settingsManager = settingsManager,
                    viewModel = viewModel,
                    platformContext = platformContext,
                )
            }
        }

        if (selectedSection == DesktopSettingsNav.About) {
            AppInformationPanel(
                versionName = AppBuildInfo.VERSION_NAME,
                lastSyncTimeMs = settingsManager.getLastSyncTime(),
            )
            if (isAppIconChangeSupported()) {
                Text(
                    stringResource(Res.string.desktop_app_icon_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (variant == SettingsScreenVariant.BilleterieBasic || variant == SettingsScreenVariant.PosBasic) {
                Spacer(Modifier.height(24.dp))
                FactoryResetSettingsSection(
                    onResetClick = { showFactoryResetDialog = true },
                )
                Spacer(Modifier.height(8.dp))
                UninstallDataSection(
                    onUninstallClick = { showUninstallDataDialog = true },
                )
            }
        }
            }
        }
    }

    if (showInstructions) {
        when (settingsManager.getBackendType()) {
            com.eventmanager.app.data.remote.BackendType.FIREBASE ->
                com.eventmanager.app.ui.components.FirebaseSetupTutorialDialog(
                    onDismiss = { showInstructions = false },
                    projectId = settingsManager.getFirebaseProjectId(),
                )
            com.eventmanager.app.data.remote.BackendType.SHEETS ->
                DesktopGoogleSheetsInstructionsDialog(onDismiss = { showInstructions = false })
        }
    }

    if (showGmailOAuthHelp) {
        DesktopGmailOAuthInstructionsDialog(
            showServiceAccountHelp = !isFirebaseBackend,
            onDismiss = { showGmailOAuthHelp = false },
        )
    }

    if (showCleanupDialog) {
        val volunteers by viewModel.volunteers.collectAsState()
        val jobs by viewModel.jobs.collectAsState()
        CleanupInactiveVolunteersDialog(
            volunteers = volunteers,
            jobs = jobs,
            onConfirm = { yearsInactive ->
                viewModel.cleanupInactiveVolunteers(yearsInactive)
                showCleanupDialog = false
            },
            onDismiss = { showCleanupDialog = false }
        )
    }

    if (showActiveVolunteersDialog) {
        val volunteers by viewModel.volunteers.collectAsState()
        val jobs by viewModel.jobs.collectAsState()
        ActiveVolunteersDialog(
            volunteers = volunteers,
            jobs = jobs,
            onDismiss = { showActiveVolunteersDialog = false },
        )
    }

    if (showFactoryResetDialog) {
        AlertDialog(
            onDismissRequest = { if (!factoryResetInProgress) showFactoryResetDialog = false },
            icon = {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(Res.string.factory_reset_confirm_title)) },
            text = { Text(stringResource(Res.string.factory_reset_confirm_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        if (factoryResetInProgress) return@Button
                        factoryResetInProgress = true
                        viewModel.factoryReset(
                            settingsManager = settingsManager,
                            fileManager = platformFileManager,
                        ) {
                            showFactoryResetDialog = false
                            factoryResetInProgress = false
                            onFactoryResetComplete()
                        }
                    },
                    enabled = !factoryResetInProgress,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    if (factoryResetInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.factory_reset_in_progress))
                    } else {
                        Text(stringResource(Res.string.factory_reset_confirm_button))
                    }
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showFactoryResetDialog = false },
                    enabled = !factoryResetInProgress,
                ) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    if (showUninstallDataDialog) {
        AlertDialog(
            onDismissRequest = { if (!uninstallDataInProgress) showUninstallDataDialog = false },
            icon = {
                Icon(
                    Icons.Default.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            title = { Text(stringResource(Res.string.uninstall_data_confirm_title)) },
            text = { Text(stringResource(Res.string.uninstall_data_confirm_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        if (uninstallDataInProgress) return@Button
                        uninstallDataInProgress = true
                        scope.launch { DesktopAppEraser.eraseAllAndExit() }
                    },
                    enabled = !uninstallDataInProgress,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    if (uninstallDataInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onError,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.uninstall_data_in_progress))
                    } else {
                        Text(stringResource(Res.string.uninstall_data_confirm_button))
                    }
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = { showUninstallDataDialog = false },
                    enabled = !uninstallDataInProgress,
                ) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    externalReaderTestDialogMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { externalReaderTestDialogMessage = null },
            icon = {
                Icon(
                    imageVector = if (externalReaderTestDialogSuccess) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (externalReaderTestDialogSuccess) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    }
                )
            },
            title = {
                Text(
                    text = stringResource(
                        if (externalReaderTestDialogSuccess) {
                            Res.string.external_reader_test_success_title
                        } else {
                            Res.string.external_reader_test_failure_title
                        }
                    ),
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            },
            confirmButton = {
                Button(onClick = { externalReaderTestDialogMessage = null }) {
                    Text(stringResource(Res.string.external_reader_test_close))
                }
            }
        )
    }

    if (showPcscReadersDialog) {
        AlertDialog(
            onDismissRequest = { showPcscReadersDialog = false },
            title = { Text(stringResource(Res.string.desktop_list_pcsc_readers)) },
            text = {
                Text(
                    text = pcscReadersListing,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace
                )
            },
            confirmButton = {
                Button(onClick = { showPcscReadersDialog = false }) {
                    Text(stringResource(Res.string.external_reader_test_close))
                }
            }
        )
    }

    SyncStatusDialog(
        isVisible = showSyncStatusDialog,
        onDismiss = { viewModel.dismissSyncStatusDialog() },
        statusMessage = syncStatusMessage
    )

    com.eventmanager.app.ui.components.BackendMigrationUiHost(
        viewModel = viewModel,
        settingsManager = settingsManager,
        platformContext = platformContext,
        showMigrationWizard = migrationWizard,
        onDismissMigrationWizard = { migrationWizard = null },
        onRequestFirebaseSignIn = { onResult ->
            scope.launch {
                val result = com.eventmanager.app.data.remote
                    .createFirebaseAuthService(platformContext)
                    .signInWithGoogle()
                when (result) {
                    is com.eventmanager.app.data.remote.FirebaseAuthResult.Success -> {
                        firebaseAuthEmail = result.email.orEmpty()
                        settingsManager.setFirebaseAuthEmail(result.email.orEmpty())
                    }
                    is com.eventmanager.app.data.remote.FirebaseAuthResult.Error -> {
                        uploadStatus = result.message
                    }
                }
                onResult(result)
            }
        },
    )

    val updateCheckResult by viewModel.updateCheckState.collectAsState()
    val updateDownloadState by viewModel.updateDownloadState.collectAsState()
    AppUpdateFlowDialog(
        visible = showUpdateResultDialog,
        updateResult = updateCheckResult,
        downloadState = updateDownloadState,
        fallbackStoreUrl = settingsManager.getUpdateStoreUrl(),
        onDismiss = { showUpdateResultDialog = false },
        onDownload = { url -> viewModel.downloadUpdate(url) },
        onInstall = { path ->
            viewModel.installUpdate(path)
            showUpdateResultDialog = false
        },
    )

    if (showUpdateSourcesDialog) {
        UpdateSourcesDialog(
            settingsManager = settingsManager,
            onDismiss = { showUpdateSourcesDialog = false },
        )
    }
}

@Composable
private fun DesktopLanguagePicker(selectedLanguage: String, onLanguageSelected: (String) -> Unit) {
    var currentLanguage by remember(selectedLanguage) { mutableStateOf(selectedLanguage) }
    var showMenu by remember { mutableStateOf(false) }

    val options = listOf(
        "en" to Res.string.language_english,
        "fr" to Res.string.language_french,
        "es" to Res.string.language_spanish,
        "zh-TW" to Res.string.language_chinese,
        "zh-CN" to Res.string.language_chinese_simplified,
        "la" to Res.string.language_latin,
        "hi" to Res.string.language_hindi,
    )

    Text(stringResource(Res.string.language_title), style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(Res.string.language_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))

    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { showMenu = true }, modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = options.firstOrNull { it.first == currentLanguage }?.let { stringResource(it.second) }
                        ?: stringResource(Res.string.language_english),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            options.forEach { (code, labelRes) ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    onClick = {
                        currentLanguage = code
                        onLanguageSelected(code)
                        showMenu = false
                    }
                )
            }
        }
    }
}

@Composable
private fun DesktopCurrencyPicker(
    selectedCurrency: String,
    onCurrencySelected: (String) -> Unit,
    editable: Boolean = true,
) {
    var currentCurrency by remember(selectedCurrency) { mutableStateOf(selectedCurrency) }
    var showMenu by remember { mutableStateOf(false) }
    val options = listOf("CHF", "EUR", "USD", "GBP")

    Text(stringResource(Res.string.currency_setting_label), style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(Res.string.currency_setting_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))

    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { if (editable) showMenu = true },
            enabled = editable,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = currentCurrency,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            options.forEach { code ->
                DropdownMenuItem(
                    text = { Text(code) },
                    onClick = {
                        currentCurrency = code
                        onCurrencySelected(code)
                        showMenu = false
                    }
                )
            }
        }
    }
}

@Composable
private fun DesktopPurchaseCreditBufferSetting(
    settingsManager: SettingsManager,
    onSyncedSettingChanged: () -> Unit,
    editable: Boolean = true,
) {
    var bufferText by remember {
        mutableStateOf(settingsManager.getPurchaseCreditBuffer().toString())
    }
    val currencyCode = settingsManager.getCurrencyCode()

    Text(stringResource(Res.string.purchase_credit_buffer_label), style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(Res.string.purchase_credit_buffer_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = bufferText,
        onValueChange = { newValue ->
            if (!editable) return@OutlinedTextField
            if (newValue.isEmpty() || newValue.matches(Regex("^\\d*\\.?\\d*$"))) {
                bufferText = newValue
                val amount = newValue.toDoubleOrNull() ?: 0.0
                settingsManager.savePurchaseCreditBuffer(amount)
                onSyncedSettingChanged()
            }
        },
        label = { Text(stringResource(Res.string.amount_label)) },
        suffix = { Text(currencyCode) },
        singleLine = true,
        enabled = editable,
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun DesktopDateTimeFormatSettings(settingsManager: SettingsManager) {
    var selectedDateFormat by remember { mutableStateOf(settingsManager.getDateFormat()) }
    var showDateFormatMenu by remember { mutableStateOf(false) }
    var selectedTimeFormat by remember { mutableStateOf(settingsManager.getTimeFormat()) }
    var showTimeFormatMenu by remember { mutableStateOf(false) }

    Text(stringResource(Res.string.date_time_format_title), style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(Res.string.date_time_format_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))

    val dateOptions = listOf(
        "dd/MM/yyyy" to Res.string.date_format_dd_mm_yyyy,
        "MM/dd/yyyy" to Res.string.date_format_mm_dd_yyyy,
        "yyyy-MM-dd" to Res.string.date_format_yyyy_mm_dd
    )
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { showDateFormatMenu = true }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = dateOptions.firstOrNull { it.first == selectedDateFormat }?.let { stringResource(it.second) }
                        ?: stringResource(Res.string.date_format_dd_mm_yyyy),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = showDateFormatMenu, onDismissRequest = { showDateFormatMenu = false }) {
            dateOptions.forEach { (format, labelRes) ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    onClick = {
                        selectedDateFormat = format
                        settingsManager.saveDateFormat(format)
                        showDateFormatMenu = false
                    }
                )
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    val timeOptions = listOf(
        "HH:mm" to Res.string.time_format_hh_mm,
        "HH:mm:ss" to Res.string.time_format_hh_mm_ss,
        "HH:mm:ss.SSS" to Res.string.time_format_hh_mm_ss_sss
    )
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { showTimeFormatMenu = true }, modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = timeOptions.firstOrNull { it.first == selectedTimeFormat }?.let { stringResource(it.second) }
                        ?: stringResource(Res.string.time_format_hh_mm),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Start
                )
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
        }
        DropdownMenu(expanded = showTimeFormatMenu, onDismissRequest = { showTimeFormatMenu = false }) {
            timeOptions.forEach { (format, labelRes) ->
                DropdownMenuItem(
                    text = { Text(stringResource(labelRes)) },
                    onClick = {
                        selectedTimeFormat = format
                        settingsManager.saveTimeFormat(format)
                        showTimeFormatMenu = false
                    }
                )
            }
        }
    }
}

@Composable
private fun DesktopSyncIntervalSettings(
    settingsManager: SettingsManager,
    viewModel: EventManagerViewModel,
    syncInterval: Int,
    onSyncIntervalChange: (Int) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var debounceJob by remember { mutableStateOf<Job?>(null) }

    fun applyInterval(newValue: Int) {
        val clamped = newValue.coerceIn(1, 60)
        onSyncIntervalChange(clamped)
        settingsManager.saveSyncInterval(clamped)
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(500)
            viewModel.updateSyncInterval()
        }
    }

    Text(
        text = stringResource(Res.string.sync_config_title),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        text = stringResource(Res.string.sync_interval_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Timer,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = stringResource(Res.string.sync_interval_label),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilledTonalIconButton(
                    onClick = { applyInterval(syncInterval - 1) },
                    enabled = syncInterval > 1,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Remove, contentDescription = stringResource(Res.string.date_change_offset_decrease))
                }

                Column(
                    modifier = Modifier.weight(1f),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = syncInterval.toString(),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = stringResource(Res.string.minutes_short),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                FilledTonalIconButton(
                    onClick = { applyInterval(syncInterval + 1) },
                    enabled = syncInterval < 60,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.date_change_offset_increase))
                }
            }

            Slider(
                value = syncInterval.toFloat(),
                onValueChange = { applyInterval(it.toInt()) },
                valueRange = 1f..60f,
                steps = 58,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun DesktopDateChangeOffset(
    settingsManager: SettingsManager,
    onSyncedSettingChanged: () -> Unit = {},
    editable: Boolean = true,
) {
    var dateChangeOffsetHours by remember { mutableStateOf(settingsManager.getDateChangeOffsetHours()) }

    Text(stringResource(Res.string.date_change_offset_title), style = MaterialTheme.typography.titleMedium)
    Text(
        text = stringResource(Res.string.date_change_offset_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Spacer(Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = {
                if (!editable) return@IconButton
                if (dateChangeOffsetHours > -12) {
                    dateChangeOffsetHours--
                    settingsManager.saveDateChangeOffsetHours(dateChangeOffsetHours)
                    onSyncedSettingChanged()
                }
            },
            enabled = editable && dateChangeOffsetHours > -12
        ) {
            Icon(Icons.Default.Remove, contentDescription = stringResource(Res.string.date_change_offset_decrease))
        }

        val offsetLabel = when {
            dateChangeOffsetHours == 0 -> stringResource(Res.string.date_change_offset_zero)
            dateChangeOffsetHours > 0 -> {
                val switchTime = dateChangeOffsetHours.toString().padStart(2, '0') + ":00"
                stringResource(Res.string.date_change_offset_hours, dateChangeOffsetHours) +
                    " (${stringResource(Res.string.date_change_offset_time, switchTime)})"
            }
            else -> {
                val switchTime = (24 + dateChangeOffsetHours).toString().padStart(2, '0') + ":00"
                stringResource(Res.string.date_change_offset_hours, dateChangeOffsetHours) +
                    " (${stringResource(Res.string.date_change_offset_previous_day, switchTime)})"
            }
        }
        Text(
            text = offsetLabel,
            modifier = Modifier.weight(1f),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )

        IconButton(
            onClick = {
                if (!editable) return@IconButton
                if (dateChangeOffsetHours < 12) {
                    dateChangeOffsetHours++
                    settingsManager.saveDateChangeOffsetHours(dateChangeOffsetHours)
                    onSyncedSettingChanged()
                }
            },
            enabled = editable && dateChangeOffsetHours < 12
        ) {
            Icon(Icons.Default.Add, contentDescription = stringResource(Res.string.date_change_offset_increase))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopEmailTemplateSettings(
    settingsManager: SettingsManager,
    onSyncedSettingChanged: () -> Unit = {},
    editable: Boolean = true,
    /** The artist guest list tab only exists on Firebase, like the rest of the feature. */
    temporaryFeaturesEnabled: Boolean = false,
    /** Keep the e-mail logo and the institution / form logo in sync. */
    onInstitutionLogoBytes: (ByteArray?) -> Unit = {},
) {
    fun persistSynced(block: () -> Unit) {
        if (!editable) return
        block()
        onSyncedSettingChanged()
    }
    val platformContext = LocalPlatformContext.current
    val subjectDefault = stringResource(Res.string.email_subject_default)
    val contentBeforeDefault = stringResource(Res.string.email_content_before_default)
    val contentAfterDefault = stringResource(Res.string.email_content_after_default)
    val signatureDefault = stringResource(Res.string.email_signature_default)
    val guestSubjectDefault = stringResource(Res.string.guest_email_subject_default)
    val guestContentBeforeDefault = stringResource(Res.string.guest_email_content_before_default)
    val guestContentAfterDefault = stringResource(Res.string.guest_email_content_after_default)
    val tempSubjectDefault = stringResource(Res.string.temp_guest_email_subject_default)
    val tempContentBeforeDefault = stringResource(Res.string.temp_guest_email_content_before_default)
    val tempContentAfterDefault = stringResource(Res.string.temp_guest_email_content_after_default)
    val associationNameDefault = stringResource(Res.string.email_association_name_hint)

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabLabels = buildList {
        add(stringResource(Res.string.email_tab_volunteer))
        add(stringResource(Res.string.email_tab_guest))
        if (temporaryFeaturesEnabled) add(stringResource(Res.string.temp_guest_email_tab))
    }

    var volunteerSubject by remember { mutableStateOf(subjectDefault) }
    var volunteerContentBefore by remember { mutableStateOf(contentBeforeDefault) }
    var volunteerContentAfter by remember { mutableStateOf(contentAfterDefault) }
    var volunteerIncludeQr by remember { mutableStateOf(true) }
    var guestSubject by remember { mutableStateOf(guestSubjectDefault) }
    var guestContentBefore by remember { mutableStateOf(guestContentBeforeDefault) }
    var guestContentAfter by remember { mutableStateOf(guestContentAfterDefault) }
    var guestIncludeQr by remember { mutableStateOf(true) }
    var tempSubject by remember { mutableStateOf(tempSubjectDefault) }
    var tempContentBefore by remember { mutableStateOf(tempContentBeforeDefault) }
    var tempContentAfter by remember { mutableStateOf(tempContentAfterDefault) }
    var tempIncludeQr by remember { mutableStateOf(true) }
    var emailSignature by remember { mutableStateOf(signatureDefault) }
    var emailAssociationName by remember { mutableStateOf(associationNameDefault) }
    var emailLogoUri by remember { mutableStateOf("") }
    var includeWalletPass by remember { mutableStateOf(true) }
    var includeLogo by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        volunteerSubject = settingsManager.getEmailSubject().ifEmpty { subjectDefault }
        volunteerContentBefore = settingsManager.getEmailContentBefore().ifEmpty { contentBeforeDefault }
        volunteerContentAfter = settingsManager.getEmailContentAfter().ifEmpty { contentAfterDefault }
        volunteerIncludeQr = settingsManager.isEmailIncludeQrEnabled()
        guestSubject = settingsManager.getGuestEmailSubject().ifEmpty { guestSubjectDefault }
        guestContentBefore = settingsManager.getGuestEmailContentBefore().ifEmpty { guestContentBeforeDefault }
        guestContentAfter = settingsManager.getGuestEmailContentAfter().ifEmpty { guestContentAfterDefault }
        guestIncludeQr = settingsManager.isGuestEmailIncludeQrEnabled()
        tempSubject = settingsManager.getTemporaryGuestEmailSubject().ifEmpty { tempSubjectDefault }
        tempContentBefore =
            settingsManager.getTemporaryGuestEmailContentBefore().ifEmpty { tempContentBeforeDefault }
        tempContentAfter =
            settingsManager.getTemporaryGuestEmailContentAfter().ifEmpty { tempContentAfterDefault }
        tempIncludeQr = settingsManager.isTemporaryGuestEmailIncludeQrEnabled()
        emailSignature = settingsManager.getEmailSignature().ifEmpty { signatureDefault }
        emailAssociationName = settingsManager.getEmailAssociationName().ifEmpty { associationNameDefault }
        includeWalletPass = settingsManager.isEmailIncludeDigitalWalletPassEnabled()
        includeLogo = settingsManager.isEmailIncludeLogoEnabled()
        emailLogoUri = settingsManager.getEmailLogoUri()
    }

    Text(stringResource(Res.string.email_qr_settings_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    Text(
        text = stringResource(Res.string.email_qr_settings_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    if (!editable) {
        Text(
            stringResource(Res.string.institution_settings_firebase_admin_only),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }


    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        tabLabels.forEachIndexed { index, label ->
            SegmentedButton(
                selected = selectedTab == index,
                onClick = { selectedTab = index },
                shape = SegmentedButtonDefaults.itemShape(index = index, count = tabLabels.size)
            ) {
                Text(label)
            }
        }
    }

    if (selectedTab == 0) {
        OutlinedTextField(
            value = volunteerSubject,
            onValueChange = {
                volunteerSubject = it
                persistSynced { settingsManager.saveEmailSubject(it) }
            },
            label = { Text(stringResource(Res.string.email_subject_label)) },
            placeholder = { Text(stringResource(Res.string.email_subject_hint)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = editable,
        )
        OutlinedTextField(
            value = volunteerContentBefore,
            onValueChange = {
                volunteerContentBefore = it
                persistSynced { settingsManager.saveEmailContentBefore(it) }
            },
            label = { Text(stringResource(Res.string.email_content_before_label)) },
            placeholder = { Text(stringResource(Res.string.email_content_before_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            enabled = editable,
        )
        DesktopSettingsToggleRow(
            title = stringResource(Res.string.email_include_qr_label),
            description = stringResource(Res.string.email_include_qr_description),
            checked = volunteerIncludeQr,
            onCheckedChange = {
                volunteerIncludeQr = it
                persistSynced { settingsManager.setEmailIncludeQrEnabled(it) }
            },
            enabled = editable,
        )
        OutlinedTextField(
            value = volunteerContentAfter,
            onValueChange = {
                volunteerContentAfter = it
                persistSynced { settingsManager.saveEmailContentAfter(it) }
            },
            label = { Text(stringResource(Res.string.email_content_after_label)) },
            placeholder = { Text(stringResource(Res.string.email_content_after_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            enabled = editable,
        )
    } else if (selectedTab == 2) {
        OutlinedTextField(
            value = tempSubject,
            onValueChange = {
                tempSubject = it
                persistSynced { settingsManager.saveTemporaryGuestEmailSubject(it) }
            },
            label = { Text(stringResource(Res.string.email_subject_label)) },
            placeholder = { Text(stringResource(Res.string.email_subject_hint)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = editable,
        )
        OutlinedTextField(
            value = tempContentBefore,
            onValueChange = {
                tempContentBefore = it
                persistSynced { settingsManager.saveTemporaryGuestEmailContentBefore(it) }
            },
            label = { Text(stringResource(Res.string.email_content_before_label)) },
            placeholder = { Text(stringResource(Res.string.email_content_before_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            enabled = editable,
        )
        DesktopSettingsToggleRow(
            title = stringResource(Res.string.email_include_qr_label),
            description = stringResource(Res.string.email_include_qr_description),
            checked = tempIncludeQr,
            onCheckedChange = {
                tempIncludeQr = it
                persistSynced { settingsManager.setTemporaryGuestEmailIncludeQrEnabled(it) }
            },
            enabled = editable,
        )
        OutlinedTextField(
            value = tempContentAfter,
            onValueChange = {
                tempContentAfter = it
                persistSynced { settingsManager.saveTemporaryGuestEmailContentAfter(it) }
            },
            label = { Text(stringResource(Res.string.email_content_after_label)) },
            placeholder = { Text(stringResource(Res.string.email_content_after_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            enabled = editable,
        )
    } else {
        OutlinedTextField(
            value = guestSubject,
            onValueChange = {
                guestSubject = it
                persistSynced { settingsManager.saveGuestEmailSubject(it) }
            },
            label = { Text(stringResource(Res.string.email_subject_label)) },
            placeholder = { Text(stringResource(Res.string.email_subject_hint)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = editable,
        )
        OutlinedTextField(
            value = guestContentBefore,
            onValueChange = {
                guestContentBefore = it
                persistSynced { settingsManager.saveGuestEmailContentBefore(it) }
            },
            label = { Text(stringResource(Res.string.email_content_before_label)) },
            placeholder = { Text(stringResource(Res.string.email_content_before_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            enabled = editable,
        )
        DesktopSettingsToggleRow(
            title = stringResource(Res.string.email_include_qr_label),
            description = stringResource(Res.string.email_include_qr_description),
            checked = guestIncludeQr,
            onCheckedChange = {
                guestIncludeQr = it
                persistSynced { settingsManager.setGuestEmailIncludeQrEnabled(it) }
            },
            enabled = editable,
        )
        OutlinedTextField(
            value = guestContentAfter,
            onValueChange = {
                guestContentAfter = it
                persistSynced { settingsManager.saveGuestEmailContentAfter(it) }
            },
            label = { Text(stringResource(Res.string.email_content_after_label)) },
            placeholder = { Text(stringResource(Res.string.email_content_after_hint)) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            enabled = editable,
        )
    }

    HorizontalDivider()

    Text(
        text = stringResource(Res.string.email_shared_settings_title),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold
    )
    Text(
        text = stringResource(Res.string.email_shared_settings_description),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    OutlinedTextField(
        value = emailSignature,
        onValueChange = {
            emailSignature = it
            persistSynced { settingsManager.saveEmailSignature(it) }
        },
        label = { Text(stringResource(Res.string.email_signature_label)) },
        placeholder = { Text(stringResource(Res.string.email_signature_hint)) },
        modifier = Modifier.fillMaxWidth(),
        minLines = 2,
        enabled = editable,
    )
    OutlinedTextField(
        value = emailAssociationName,
        onValueChange = {
            emailAssociationName = it
            persistSynced { settingsManager.saveEmailAssociationName(it) }
        },
        label = { Text(stringResource(Res.string.email_association_name_label)) },
        placeholder = { Text(stringResource(Res.string.email_association_name_hint)) },
        supportingText = { Text(stringResource(Res.string.email_association_name_description)) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        enabled = editable,
    )

    DesktopSettingsToggleRow(
        title = stringResource(Res.string.email_include_digital_wallet_pass_label),
        description = stringResource(Res.string.email_include_digital_wallet_pass_description),
        checked = includeWalletPass,
        onCheckedChange = {
            includeWalletPass = it
            settingsManager.setEmailIncludeDigitalWalletPassEnabled(it)
        },
        enabled = editable,
    )
    if (includeWalletPass) {
        DesktopWalletPassCertificateSettings(settingsManager = settingsManager, platformContext = platformContext)
    }
    DesktopSettingsToggleRow(
        title = stringResource(Res.string.email_include_logo_label),
        description = stringResource(Res.string.email_include_logo_description),
        checked = includeLogo,
        onCheckedChange = {
            includeLogo = it
            settingsManager.setEmailIncludeLogoEnabled(it)
        },
        enabled = editable,
    )

    if (includeLogo) {
        EmailLogoUploadSection(
            platformContext = platformContext,
            logoPath = emailLogoUri,
            onLogoPathChanged = { path ->
                if (!editable) return@EmailLogoUploadSection
                emailLogoUri = path
                settingsManager.saveEmailLogoUri(path)
                val bytes = if (path.isBlank()) {
                    null
                } else {
                    PlatformFileManager(platformContext).readEmailLogoBytes()
                }
                onInstitutionLogoBytes(bytes)
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    OutlinedButton(
        onClick = {
            if (!editable) return@OutlinedButton
            if (selectedTab == 0) {
                volunteerSubject = subjectDefault
                volunteerContentBefore = contentBeforeDefault
                volunteerIncludeQr = true
                volunteerContentAfter = contentAfterDefault
                settingsManager.saveEmailSubject(volunteerSubject)
                settingsManager.saveEmailContentBefore(volunteerContentBefore)
                settingsManager.setEmailIncludeQrEnabled(volunteerIncludeQr)
                persistSynced { settingsManager.saveEmailContentAfter(volunteerContentAfter) }
            } else {
                guestSubject = guestSubjectDefault
                guestContentBefore = guestContentBeforeDefault
                guestIncludeQr = true
                guestContentAfter = guestContentAfterDefault
                settingsManager.saveGuestEmailSubject(guestSubject)
                settingsManager.saveGuestEmailContentBefore(guestContentBefore)
                settingsManager.setGuestEmailIncludeQrEnabled(guestIncludeQr)
                persistSynced { settingsManager.saveGuestEmailContentAfter(guestContentAfter) }
            }
        },
        modifier = Modifier.fillMaxWidth(),
        enabled = editable,
    ) {
        Icon(Icons.Default.Refresh, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text("${stringResource(Res.string.email_reset_to_defaults)} (${tabLabels[selectedTab]})")
    }
}

@Composable
private fun DesktopWalletPassCertificateSettings(
    settingsManager: SettingsManager,
    platformContext: com.eventmanager.app.platform.PlatformContext,
) {
    val scope = rememberCoroutineScope()
    val fileManager = remember(platformContext) { com.eventmanager.app.platform.PlatformFileManager(platformContext) }
    var certPassword by remember { mutableStateOf(settingsManager.getWalletPassCertificatePassword()) }
    var certConfigured by remember { mutableStateOf(fileManager.getWalletPassCertificateFile()?.exists() == true) }
    val signingReady = remember(certConfigured, certPassword) {
        com.eventmanager.app.wallet.WalletPassService.isAppleWalletSigningConfigured(
            settingsManager,
            fileManager.getWalletPassCertificateFile()?.readBytes(),
        )
    }
    val signingConfig = remember(signingReady, certPassword) {
        if (!signingReady) null else com.eventmanager.app.wallet.WalletPassSigningConfigLoader.fromSettings(
            settingsManager,
            fileManager.getWalletPassCertificateFile()?.readBytes(),
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                stringResource(Res.string.email_wallet_pass_cert_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(Res.string.email_wallet_pass_cert_description),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    scope.launch {
                        val bytes = fileManager.pickWalletPassCertificateFile()
                        if (bytes != null && fileManager.saveWalletPassCertificate(bytes)) {
                            certConfigured = true
                            val info = com.eventmanager.app.wallet.PkPassCertificateParser.loadPkcs12(
                                bytes,
                                certPassword.toCharArray(),
                            )
                            info?.passTypeIdentifier?.let {
                                if (settingsManager.getWalletPassTypeIdentifier().isBlank()) {
                                    settingsManager.saveWalletPassTypeIdentifier(it)
                                }
                            }
                            info?.teamIdentifier?.let {
                                if (settingsManager.getWalletPassTeamIdentifier().isBlank()) {
                                    settingsManager.saveWalletPassTeamIdentifier(it)
                                }
                            }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.email_wallet_pass_cert_upload))
            }
            OutlinedTextField(
                value = certPassword,
                onValueChange = {
                    certPassword = it
                    settingsManager.saveWalletPassCertificatePassword(it)
                },
                label = { Text(stringResource(Res.string.email_wallet_pass_cert_password_label)) },
                placeholder = { Text(stringResource(Res.string.email_wallet_pass_cert_password_hint)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            val statusText = if (signingReady && signingConfig != null) {
                stringResource(
                    Res.string.email_wallet_pass_cert_configured,
                    signingConfig.passTypeIdentifier,
                    signingConfig.teamIdentifier,
                )
            } else {
                stringResource(Res.string.email_wallet_pass_cert_missing)
            }
            Text(
                statusText,
                style = MaterialTheme.typography.bodySmall,
                color = if (signingReady) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun DesktopDeveloperSettings(
    settingsManager: SettingsManager,
    viewModel: EventManagerViewModel,
    platformContext: com.eventmanager.app.platform.PlatformContext,
) {
    var debugModeEnabled by remember { mutableStateOf(settingsManager.getDebugMode()) }
    var logFiles by remember { mutableStateOf(FileAppLogger.getAllLogFiles()) }
    var totalLogSize by remember { mutableStateOf(FileAppLogger.getTotalLogSize()) }
    var showLogViewer by remember { mutableStateOf(false) }
    var selectedLogContent by remember { mutableStateOf<String?>(null) }
    val logsDirectoryPath = remember { FileAppLogger.getLogsDirectoryPath() }
    val scope = rememberCoroutineScope()
    val errorReadingGeneric = stringResource(Res.string.debug_logs_error_reading_generic)

    LaunchedEffect(debugModeEnabled) {
        if (debugModeEnabled) {
            logFiles = FileAppLogger.getAllLogFiles()
            totalLogSize = FileAppLogger.getTotalLogSize()
        }
    }

    DesktopSettingsToggleRow(
        title = stringResource(Res.string.debug_mode_title),
        description = stringResource(Res.string.debug_mode_description),
        checked = debugModeEnabled,
        onCheckedChange = {
            debugModeEnabled = it
            settingsManager.saveDebugMode(it)
            FileAppLogger.setIntercepting(it)
            FileAppLogger.i("SettingsScreen", "Debug mode ${if (it) "enabled" else "disabled"}")
            if (it) {
                logFiles = FileAppLogger.getAllLogFiles()
                totalLogSize = FileAppLogger.getTotalLogSize()
            }
        }
    )

    Spacer(Modifier.height(8.dp))

    if (debugModeEnabled) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(Res.string.debug_logs_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = stringResource(Res.string.debug_logs_location, logsDirectoryPath),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(
                    Res.string.debug_logs_files_size,
                    logFiles.size,
                    String.format("%.2f", totalLogSize / (1024.0 * 1024.0))
                ),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (logFiles.isNotEmpty()) {
                Text(stringResource(Res.string.debug_logs_recent_files), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                logFiles.takeLast(3).reversed().forEach { logFile ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(logFile.name, style = MaterialTheme.typography.labelSmall)
                            Text("${String.format("%.2f KB", logFile.length() / 1024.0)}", style = MaterialTheme.typography.labelSmall)
                        }
                        TextButton(onClick = {
                            scope.launch {
                                selectedLogContent = withContext(Dispatchers.IO) {
                                    try {
                                        logFile.readText()
                                    } catch (e: Exception) {
                                        e.message ?: errorReadingGeneric
                                    }
                                }
                                showLogViewer = true
                            }
                        }) {
                            Text(stringResource(Res.string.debug_logs_view))
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                if (logFiles.isNotEmpty()) {
                    Button(
                        onClick = {
                            scope.launch {
                                selectedLogContent = withContext(Dispatchers.IO) {
                                    FileAppLogger.getLatestLogContent()
                                }
                                showLogViewer = true
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(Res.string.debug_logs_view_latest))
                    }
                }
                Button(
                    onClick = {
                        FileAppLogger.clearAllLogs()
                        logFiles = FileAppLogger.getAllLogFiles()
                        totalLogSize = FileAppLogger.getTotalLogSize()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        stringResource(Res.string.debug_logs_clear_all),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }

    if (showLogViewer) {
        DesktopLogViewerDialog(
            content = selectedLogContent ?: errorReadingGeneric,
            onDismiss = { showLogViewer = false }
        )
    }

    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

    val biometricAuth = remember(platformContext) { createBiometricAuth(platformContext) }
    var biometricEnabled by remember { mutableStateOf(settingsManager.isBiometricAdminLoginEnabled()) }
    var showBiometricWarningDialog by remember { mutableStateOf(false) }
    var showBiometricAdminVerifyDialog by remember { mutableStateOf(false) }
    var showBiometricOrgWizard by remember { mutableStateOf(false) }
    var pendingBiometricEnrollmentMatch by remember { mutableStateOf<ScannerMatch?>(null) }
    var pendingBiometricEnrollments by remember { mutableStateOf<List<BiometricAdminOrgEnrollment>?>(null) }

    val configuredOrgs = viewModel.getFirebaseConfiguredOrgs()
    val useMultiOrgBiometricWizard =
        settingsManager.getBackendType() == BackendType.FIREBASE && configuredOrgs.size > 1
    val biometricEnrollments = remember(biometricEnabled) {
        settingsManager.getBiometricEnrollments()
    }

    val noneEnrolled = biometricAuth.isNoneEnrolled
    val biometricAvailable = biometricAuth.isAvailable
    val enrollmentTitle = stringResource(Res.string.biometric_enrollment_prompt_title)
    val enrollmentSubtitle = stringResource(Res.string.biometric_enrollment_prompt_subtitle)
    val enrollmentSuccessMsg = stringResource(Res.string.biometric_enrollment_success)
    val biometricDisabledMsg = stringResource(Res.string.biometric_admin_login_disabled)

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.biometric_admin_login_title),
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = when {
                    noneEnrolled -> stringResource(Res.string.biometric_admin_login_none_enrolled)
                    !biometricAvailable -> stringResource(Res.string.biometric_admin_login_not_available)
                    else -> stringResource(Res.string.biometric_admin_login_description)
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (!biometricAvailable) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(
            checked = biometricEnabled,
            onCheckedChange = { wantsEnabled ->
                if (wantsEnabled) {
                    showBiometricWarningDialog = true
                } else {
                    biometricEnabled = false
                    settingsManager.setBiometricAdminLoginEnabled(false)
                    showPlatformToast(platformContext, biometricDisabledMsg)
                }
            },
            enabled = biometricAvailable
        )
    }

    if (biometricEnabled && useMultiOrgBiometricWizard && biometricEnrollments.isNotEmpty()) {
        biometricEnrollments.forEach { enrollment ->
            Text(
                text = stringResource(
                    Res.string.biometric_enrollment_org_enrolled,
                    FirebaseOrgAbbreviation.abbreviate(enrollment.orgId),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
    }

    if (showBiometricWarningDialog) {
        AlertDialog(
            onDismissRequest = { showBiometricWarningDialog = false },
            icon = { Icon(Icons.Default.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
            title = { Text(stringResource(Res.string.biometric_warning_title), fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(Res.string.biometric_warning_message)) },
            confirmButton = {
                Button(
                    onClick = {
                        showBiometricWarningDialog = false
                        if (useMultiOrgBiometricWizard) {
                            showBiometricOrgWizard = true
                        } else {
                            showBiometricAdminVerifyDialog = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(stringResource(Res.string.biometric_warning_accept))
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showBiometricWarningDialog = false }) {
                    Text(stringResource(Res.string.biometric_warning_cancel))
                }
            }
        )
    }

    if (showBiometricOrgWizard) {
        BiometricOrgEnrollmentWizard(
            platformContext = platformContext,
            viewModel = viewModel,
            configuredOrgs = configuredOrgs,
            onCompleted = { enrollments ->
                showBiometricOrgWizard = false
                pendingBiometricEnrollments = enrollments
            },
            onDismiss = { showBiometricOrgWizard = false },
        )
    }

    if (showBiometricAdminVerifyDialog) {
        BiometricAdminVerificationDialog(
            platformContext = platformContext,
            viewModel = viewModel,
            onVerified = { match ->
                showBiometricAdminVerifyDialog = false
                pendingBiometricEnrollmentMatch = match
            },
            onDismiss = { showBiometricAdminVerifyDialog = false }
        )
    }

    LaunchedEffect(pendingBiometricEnrollmentMatch) {
        val match = pendingBiometricEnrollmentMatch ?: return@LaunchedEffect
        pendingBiometricEnrollmentMatch = null
        val ok = biometricAuth.authenticate(enrollmentTitle, enrollmentSubtitle)
        if (ok) {
            settingsManager.setBiometricAdminProfileLink(match.toBiometricAdminProfileLink())
            biometricEnabled = true
            showPlatformToast(platformContext, enrollmentSuccessMsg)
        }
    }

    LaunchedEffect(pendingBiometricEnrollments) {
        val enrollments = pendingBiometricEnrollments ?: return@LaunchedEffect
        pendingBiometricEnrollments = null
        val ok = biometricAuth.authenticate(enrollmentTitle, enrollmentSubtitle)
        if (ok) {
            settingsManager.setBiometricEnrollments(enrollments)
            biometricEnabled = true
            showPlatformToast(platformContext, enrollmentSuccessMsg)
        }
    }
}

@Composable
private fun DesktopGmailOAuthInstructionsDialog(
    showServiceAccountHelp: Boolean = true,
    onDismiss: () -> Unit,
) {
    var selectedTab by remember { mutableStateOf(GmailHelpTab.OAuth) }
    val scrollState = rememberScrollState()

    LaunchedEffect(selectedTab) {
        scrollState.scrollTo(0)
    }
    LaunchedEffect(showServiceAccountHelp) {
        if (!showServiceAccountHelp && selectedTab == GmailHelpTab.ServiceAccount) {
            selectedTab = GmailHelpTab.OAuth
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            tonalElevation = 6.dp,
            modifier = Modifier
                .fillMaxWidth(0.55f)
                .fillMaxHeight(0.75f)
        ) {
            Column(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.email_gmail_oauth_help_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (showServiceAccountHelp) {
                    GmailHelpTabRow(
                        selectedTab = selectedTab,
                        onTabSelected = { selectedTab = it }
                    )
                }

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    when (selectedTab) {
                        GmailHelpTab.OAuth -> GmailHelpOAuthTab()
                        GmailHelpTab.ServiceAccount -> if (showServiceAccountHelp) {
                            GmailHelpServiceAccountTab()
                        } else {
                            GmailHelpOAuthTab()
                        }
                    }
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.got_it))
                    }
                }
            }
        }
    }
}

private enum class GmailHelpTab { OAuth, ServiceAccount }

@Composable
private fun GmailHelpTabRow(
    selectedTab: GmailHelpTab,
    onTabSelected: (GmailHelpTab) -> Unit,
) {
    val tabs = listOf(
        GmailHelpTab.OAuth to stringResource(Res.string.email_gmail_help_tab_oauth),
        GmailHelpTab.ServiceAccount to stringResource(Res.string.email_gmail_help_tab_service_account),
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        tabs.forEach { (tab, label) ->
            GmailHelpTabChip(
                label = label,
                selected = selectedTab == tab,
                onClick = { onTabSelected(tab) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun GmailHelpTabChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .background(
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                },
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

@Composable
private fun GmailHelpOAuthTab() {
    Text(
        text = stringResource(Res.string.email_gmail_oauth_help_intro),
        fontWeight = FontWeight.SemiBold
    )
    OutlinedButton(
        onClick = { openUrl("https://console.cloud.google.com/") },
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(stringResource(Res.string.open_google_cloud_console))
    }
    val steps = listOf(
        Res.string.email_gmail_oauth_step_1_title to Res.string.email_gmail_oauth_step_1_description,
        Res.string.email_gmail_oauth_step_2_title to Res.string.email_gmail_oauth_step_2_description,
        Res.string.email_gmail_oauth_step_3_title to Res.string.email_gmail_oauth_step_3_description,
        Res.string.email_gmail_oauth_step_4_title to Res.string.email_gmail_oauth_step_4_description,
        Res.string.email_gmail_oauth_step_5_title to Res.string.email_gmail_oauth_step_5_description,
        Res.string.email_gmail_oauth_step_6_title to Res.string.email_gmail_oauth_step_6_description,
    )
    steps.forEachIndexed { index, (titleRes, descRes) ->
        DesktopInstructionStep(
            number = (index + 1).toString(),
            title = stringResource(titleRes),
            description = stringResource(descRes)
        )
    }
}

@Composable
private fun GmailHelpServiceAccountTab() {
    Text(
        text = stringResource(Res.string.email_gmail_service_account_help_intro),
        fontWeight = FontWeight.SemiBold
    )
    GmailHelpSection(
        title = stringResource(Res.string.email_gmail_service_account_help_sender_title),
        body = stringResource(Res.string.email_gmail_service_account_help_sender_body)
    )
    GmailHelpSection(
        title = stringResource(Res.string.email_gmail_service_account_help_not_title),
        body = stringResource(Res.string.email_gmail_service_account_help_not_body)
    )
    GmailHelpSection(
        title = stringResource(Res.string.email_gmail_service_account_help_requirements_title),
        body = stringResource(Res.string.email_gmail_service_account_help_requirements_body)
    )
    val steps = listOf(
        Res.string.email_gmail_service_account_help_step_1_title to Res.string.email_gmail_service_account_help_step_1_description,
        Res.string.email_gmail_service_account_help_step_2_title to Res.string.email_gmail_service_account_help_step_2_description,
        Res.string.email_gmail_service_account_help_step_3_title to Res.string.email_gmail_service_account_help_step_3_description,
    )
    steps.forEachIndexed { index, (titleRes, descRes) ->
        DesktopInstructionStep(
            number = (index + 1).toString(),
            title = stringResource(titleRes),
            description = stringResource(descRes)
        )
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.65f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
    ) {
        Text(
            text = stringResource(Res.string.email_gmail_service_account_help_no_workspace),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
        )
    }
}

@Composable
private fun GmailHelpSection(title: String, body: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DesktopGoogleSheetsInstructionsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(stringResource(Res.string.google_sheets_setup_instructions), fontWeight = FontWeight.Bold)
        },
        text = {
            LazyColumn(
                modifier = Modifier.heightIn(max = 400.dp).fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(stringResource(Res.string.setup_instructions_intro), fontWeight = FontWeight.SemiBold)
                }
                item {
                    OutlinedButton(
                        onClick = { openUrl("https://console.cloud.google.com/") },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.open_google_cloud_console))
                    }
                }
                val steps = listOf(
                    Res.string.step_1_title to Res.string.step_1_description,
                    Res.string.step_2_title to Res.string.step_2_description,
                    Res.string.step_3_title to Res.string.step_3_description,
                    Res.string.step_4_title to Res.string.step_4_description,
                    Res.string.step_5_title to Res.string.step_5_description,
                    Res.string.step_6_title to Res.string.step_6_description,
                    Res.string.step_7_title to Res.string.step_7_description,
                    Res.string.step_8_title to Res.string.step_8_description,
                    Res.string.step_9_title to Res.string.step_9_description
                )
                steps.forEachIndexed { index, (titleRes, descRes) ->
                    item {
                        DesktopInstructionStep(
                            number = (index + 1).toString(),
                            title = stringResource(titleRes),
                            description = stringResource(descRes)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.got_it))
            }
        }
    )
}

@Composable
private fun DesktopInstructionStep(number: String, title: String, description: String) {
    Row(verticalAlignment = Alignment.Top) {
        Card(
            modifier = Modifier.size(24.dp),
            shape = CircleShape,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(number, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DesktopLogViewerDialog(content: String, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(0.9f).fillMaxHeight(0.8f),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(Res.string.debug_logs_content), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.close))
                    }
                }
                Spacer(Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())
                ) {
                    Text(
                        text = content,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopServiceAccountKeyInfoPanel(
    keyInfo: ServiceAccountKeyInfo?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (keyInfo != null) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            } else {
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = if (keyInfo != null) Icons.Default.CheckCircle else Icons.Default.Warning,
                contentDescription = null,
                tint = if (keyInfo != null) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.error
                }
            )
            Spacer(Modifier.width(12.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = if (keyInfo != null) {
                        stringResource(Res.string.service_account_key_found)
                    } else {
                        stringResource(Res.string.service_account_key_required)
                    },
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                if (keyInfo != null) {
                    Text(
                        text = stringResource(Res.string.email_colon, keyInfo.clientEmail),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(Res.string.project_colon, keyInfo.projectId),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = stringResource(Res.string.upload_key_file_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DesktopSettingsSidebar(
    items: List<DesktopSettingsNavItem>,
    selected: DesktopSettingsNav,
    onSelect: (DesktopSettingsNav) -> Unit,
    subtitle: String?,
) {
    Column(
        modifier = Modifier
            .width(252.dp)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(vertical = 20.dp, horizontal = 12.dp),
    ) {
        Text(
            text = stringResource(Res.string.settings_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        Spacer(Modifier.height(18.dp))
        items.forEach { item ->
            val isSelected = item.id == selected
            val container = if (isSelected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                Color.Transparent
            }
            val content = if (isSelected) {
                MaterialTheme.colorScheme.onSecondaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(container)
                    .clickable { onSelect(item.id) }
                    .padding(horizontal = 12.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = item.icon,
                    contentDescription = null,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = content,
                    maxLines = 2,
                )
            }
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun DesktopSettingsDetailHeader(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 36.dp, vertical = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
        Spacer(Modifier.width(14.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DesktopSettingsToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        }
    }
}

@Composable
private fun SheetNameField(label: String, value: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
    )
}

@Composable
private fun DesktopManagementCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    buttonLabel: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(16.dp))
            OutlinedButton(onClick = onClick, shape = RoundedCornerShape(10.dp)) {
                Text(buttonLabel)
            }
        }
    }
}


@Composable
private fun DesktopNfcExpandSection(
    title: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onToggle)
                .padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(
                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                content = content,
            )
        }
    }
}

@Composable
private fun UninstallDataSection(
    onUninstallClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = onUninstallClick,
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.error,
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
        ) {
            Icon(Icons.Default.DeleteForever, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(stringResource(Res.string.uninstall_data_title))
        }
        Text(
            text = stringResource(Res.string.uninstall_data_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun DesktopSettingsCategory(
    section: DesktopSettingsNav,
    selectedSection: DesktopSettingsNav,
    @Suppress("UNUSED_PARAMETER") title: String,
    @Suppress("UNUSED_PARAMETER") icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit,
) {
    if (section != selectedSection) return
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        content = content,
    )
}
