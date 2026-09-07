package com.eventmanager.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.remote.BackendType
import com.eventmanager.app.data.remote.InstitutionBackendAnnouncement
import com.eventmanager.app.data.remote.MigrationDirection
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.data.sync.SyncResult
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.backend_mismatch_title
import com.eventmanager.app.resources.firebase_join_error_invalid
import com.eventmanager.app.resources.firebase_join_error_invite_missing
import com.eventmanager.app.resources.firebase_join_error_oauth_missing
import com.eventmanager.app.resources.follow_connect
import com.eventmanager.app.resources.follow_factory_reset
import com.eventmanager.app.resources.follow_factory_reset_body
import com.eventmanager.app.resources.follow_factory_reset_confirm
import com.eventmanager.app.resources.follow_intro
import com.eventmanager.app.resources.follow_later
import com.eventmanager.app.resources.follow_migrated_by
import com.eventmanager.app.resources.follow_need_sign_in
import com.eventmanager.app.resources.follow_step_connect
import com.eventmanager.app.resources.follow_step_credentials
import com.eventmanager.app.resources.follow_target_firebase
import com.eventmanager.app.resources.follow_target_sheets
import com.eventmanager.app.resources.follow_title
import com.eventmanager.app.resources.firebase_tutorial_help_cd
import com.eventmanager.app.resources.migration_service_account_missing
import com.eventmanager.app.resources.migration_service_account_ok
import com.eventmanager.app.resources.migration_spreadsheet_id_label
import com.eventmanager.app.resources.migration_wizard_cancel
import com.eventmanager.app.resources.migration_wizard_cancel_inflight
import com.eventmanager.app.resources.migration_wizard_cancel_inflight_hint
import com.eventmanager.app.resources.migration_wizard_done
import com.eventmanager.app.resources.migration_wizard_intro
import com.eventmanager.app.resources.migration_wizard_start
import com.eventmanager.app.resources.migration_wizard_step_config
import com.eventmanager.app.resources.migration_wizard_step_config_firebase
import com.eventmanager.app.resources.migration_wizard_step_config_sheets
import com.eventmanager.app.resources.migration_wizard_step_run
import com.eventmanager.app.resources.migration_wizard_step_run_body
import com.eventmanager.app.resources.migration_wizard_to_firebase_title
import com.eventmanager.app.resources.migration_wizard_to_sheets_title
import com.eventmanager.app.data.remote.FirebaseJoinImport
import com.eventmanager.app.data.remote.FirebaseJoinImportProblem
import com.eventmanager.app.data.remote.FirebaseJoinImportResult
import com.eventmanager.app.ui.components.FirebaseConnectionFields
import com.eventmanager.app.ui.components.FirebaseJoinImportSection
import com.eventmanager.app.ui.components.FirebaseSetupTutorialDialog
import com.eventmanager.app.ui.components.GuidedStatusBanner
import com.eventmanager.app.ui.components.GuidedStepCard
import com.eventmanager.app.ui.components.RawPayloadQrScannerDialog
import com.eventmanager.app.ui.components.SheetsMigrationCredentialsSection
import com.eventmanager.app.ui.components.firebaseConnectionReady
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Admin-only follow screen after an institution backend migration.
 * Collects the credentials needed to connect to the new remote database.
 */
@Composable
fun BackendFollowScreen(
    announcement: InstitutionBackendAnnouncement,
    settingsManager: SettingsManager,
    platformContext: PlatformContext?,
    onFollow: suspend (orgId: String?, spreadsheetId: String?) -> SyncResult,
    onCancelUnavailable: () -> Unit = {},
    onFactoryReset: (() -> Unit)? = null,
    onRequestFirebaseSignIn: ((com.eventmanager.app.data.remote.FirebaseAuthResult) -> Unit) -> Unit = { onResult ->
        onResult(com.eventmanager.app.data.remote.FirebaseAuthResult.Error("Firebase Sign-In is not available"))
    },
) {
    var configuredOrgs by remember {
        mutableStateOf(
            settingsManager.getFirebaseConfiguredOrgs().ifEmpty {
                listOf(
                    com.eventmanager.app.data.remote.FirebaseConfiguredOrg(
                        orgId = announcement.firebaseOrgId.orEmpty().ifBlank { settingsManager.getFirebaseOrgId() },
                        colorArgb = com.eventmanager.app.data.remote.FirebaseConfiguredOrgCodec.defaultColorForIndex(0),
                    ),
                )
            },
        )
    }
    fun activeOrgId(): String = settingsManager.getFirebaseOrgId().ifBlank {
        configuredOrgs.firstOrNull { it.orgId.isNotBlank() }?.orgId.orEmpty()
    }
    var spreadsheetId by remember {
        mutableStateOf(announcement.sheetsSpreadsheetIdHint.orEmpty().ifBlank { settingsManager.getSpreadsheetId() })
    }
    var projectId by remember { mutableStateOf(settingsManager.getFirebaseProjectId()) }
    var applicationId by remember { mutableStateOf(settingsManager.getFirebaseApplicationId()) }
    var apiKey by remember { mutableStateOf(settingsManager.getFirebaseApiKey()) }
    var webClientId by remember { mutableStateOf(settingsManager.getFirebaseWebClientId()) }
    var webClientSecret by remember { mutableStateOf(settingsManager.getFirebaseWebClientSecret()) }
    var authEmail by remember { mutableStateOf(settingsManager.getFirebaseAuthEmail().ifBlank { null }) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var showJoinScan by remember { mutableStateOf(false) }
    var joinedViaQr by remember { mutableStateOf(false) }
    var joinError by remember { mutableStateOf<String?>(null) }
    var showResetConfirm by remember { mutableStateOf(false) }
    var sheetsUploadStatus by remember { mutableStateOf<String?>(null) }
    var serviceAccountReady by remember { mutableStateOf(settingsManager.isConfigured()) }
    val scope = rememberCoroutineScope()
    val needSignInMsg = stringResource(Res.string.follow_need_sign_in)
    val joinOAuthMissingMsg = stringResource(Res.string.firebase_join_error_oauth_missing)
    val joinInviteMissingMsg = stringResource(Res.string.firebase_join_error_invite_missing)
    val joinInvalidMsg = stringResource(Res.string.firebase_join_error_invalid)

    fun reloadFirebaseFromSettings() {
        configuredOrgs = settingsManager.getFirebaseConfiguredOrgs()
        projectId = settingsManager.getFirebaseProjectId()
        applicationId = settingsManager.getFirebaseApplicationId()
        apiKey = settingsManager.getFirebaseApiKey()
        webClientId = settingsManager.getFirebaseWebClientId()
        webClientSecret = settingsManager.getFirebaseWebClientSecret()
    }

    LaunchedEffect(announcement) {
        if (announcement.backendType == BackendType.FIREBASE) {
            settingsManager.applySilentFirebaseOptionsFromAnnouncement(announcement)
            if (announcement.firebaseOrgId?.isNotBlank() == true) {
                val id = announcement.firebaseOrgId!!
                configuredOrgs = settingsManager.getFirebaseConfiguredOrgs().let { current ->
                    if (current.any { it.orgId == id }) current
                    else current + com.eventmanager.app.data.remote.FirebaseConfiguredOrg(
                        id,
                        com.eventmanager.app.data.remote.FirebaseConfiguredOrgCodec.nextAvailableColor(current.map { it.colorArgb }),
                    )
                }
            }
            projectId = settingsManager.getFirebaseProjectId()
            applicationId = settingsManager.getFirebaseApplicationId()
            apiKey = settingsManager.getFirebaseApiKey()
            webClientId = settingsManager.getFirebaseWebClientId()
            webClientSecret = settingsManager.getFirebaseWebClientSecret()
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            authEmail = settingsManager.getFirebaseAuthEmail().ifBlank { null }
        }
    }

    val projectOptionsReady = projectId.isNotBlank() && applicationId.isNotBlank() && apiKey.isNotBlank()
    val hideSecrets = projectOptionsReady || announcement.hasFirebaseProjectOptions() || joinedViaQr
    val canConnect = when (announcement.backendType) {
        BackendType.FIREBASE -> firebaseConnectionReady(configuredOrgs, projectId, applicationId, apiKey, authEmail)
        BackendType.SHEETS -> {
            spreadsheetId.isNotBlank() &&
                spreadsheetId != "YOUR_SPREADSHEET_ID_HERE" &&
                serviceAccountReady
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                stringResource(Res.string.follow_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(Res.string.follow_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GuidedStatusBanner(
                title = when (announcement.backendType) {
                    BackendType.FIREBASE -> stringResource(Res.string.follow_target_firebase)
                    BackendType.SHEETS -> stringResource(Res.string.follow_target_sheets)
                },
                subtitle = announcement.migratedBy.takeIf { it.isNotBlank() }?.let {
                    stringResource(Res.string.follow_migrated_by, it)
                },
                ready = canConnect,
                warning = !canConnect,
            )

            GuidedStepCard(title = stringResource(Res.string.follow_step_credentials)) {
                when (announcement.backendType) {
                    BackendType.FIREBASE -> {
                        // Same shape as "join an organization" in the setup wizard: scan the QR,
                        // or type the codes. This used to be hidden as soon as the announcement
                        // carried project options, leaving a wall of credential fields as the
                        // only visible path.
                        FirebaseJoinImportSection(
                            settingsManager = settingsManager,
                            onJoined = {
                                reloadFirebaseFromSettings()
                                joinedViaQr = true
                            },
                            onRequestScan = if (platformContext != null) {
                                { showJoinScan = true }
                            } else {
                                null
                            },
                            joinError = joinError,
                            onJoinInputChanged = {
                                joinError = null
                                reloadFirebaseFromSettings()
                            },
                            configImported = joinedViaQr ||
                                settingsManager.isFirebaseJoinImported(),
                        )
                        FirebaseConnectionFields(
                            configuredOrgs = configuredOrgs,
                            onConfiguredOrgsChange = { configuredOrgs = it },
                            projectId = projectId,
                            onProjectIdChange = { projectId = it },
                            applicationId = applicationId,
                            onApplicationIdChange = { applicationId = it },
                            apiKey = apiKey,
                            onApiKeyChange = { apiKey = it },
                            webClientId = webClientId,
                            onWebClientIdChange = { webClientId = it },
                            webClientSecret = webClientSecret,
                            onWebClientSecretChange = { webClientSecret = it },
                            authEmail = authEmail,
                            onSignIn = {
                                settingsManager.setFirebaseConfiguredOrgs(configuredOrgs)
                                settingsManager.setFirebaseProjectId(projectId.trim())
                                settingsManager.setFirebaseApplicationId(applicationId.trim())
                                settingsManager.setFirebaseApiKey(apiKey.trim())
                                settingsManager.setFirebaseWebClientId(webClientId.trim())
                                settingsManager.setFirebaseWebClientSecret(webClientSecret.trim())
                                status = null
                                onRequestFirebaseSignIn { result ->
                                    when (result) {
                                        is com.eventmanager.app.data.remote.FirebaseAuthResult.Success -> {
                                            authEmail = result.email
                                            settingsManager.setFirebaseAuthEmail(result.email.orEmpty())
                                            status = null
                                        }
                                        is com.eventmanager.app.data.remote.FirebaseAuthResult.Error -> {
                                            status = result.message
                                        }
                                    }
                                }
                            },
                            onSignOut = {
                                scope.launch {
                                    com.eventmanager.app.data.remote.createFirebaseAuthService(platformContext).signOut()
                                    authEmail = null
                                }
                            },
                            compact = true,
                            signInFeedback = status?.takeIf { authEmail.isNullOrBlank() },
                            hideProjectSecrets = hideSecrets,
                            orgIdReadOnly = hideSecrets && activeOrgId().isNotBlank(),
                        )
                    }
                    BackendType.SHEETS -> {
                        SheetsMigrationCredentialsSection(
                            spreadsheetId = spreadsheetId,
                            onSpreadsheetIdChange = { spreadsheetId = it },
                            settingsManager = settingsManager,
                            platformContext = platformContext,
                            uploadStatus = sheetsUploadStatus,
                            onUploadStatus = { sheetsUploadStatus = it },
                            onConfiguredChanged = { serviceAccountReady = it },
                        )
                    }
                }
                status?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            GuidedStepCard(
                title = stringResource(Res.string.follow_step_connect),
            ) {
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                status?.let {
                    Text(
                        it,
                        color = if (it.contains("Followed", ignoreCase = true) || it.contains("Success", ignoreCase = true)) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }
                Button(
                    onClick = {
                        busy = true
                        status = null
                        scope.launch {
                            if (announcement.backendType == BackendType.FIREBASE) {
                                settingsManager.setFirebaseConfiguredOrgs(configuredOrgs)
                                settingsManager.setFirebaseProjectId(projectId.trim())
                                settingsManager.setFirebaseApplicationId(applicationId.trim())
                                settingsManager.setFirebaseApiKey(apiKey.trim())
                                settingsManager.setFirebaseWebClientId(webClientId.trim())
                                settingsManager.setFirebaseWebClientSecret(webClientSecret.trim())
                                val auth = com.eventmanager.app.data.remote.createFirebaseAuthService(platformContext)
                                if (!auth.isSignedIn()) {
                                    when (val restored = auth.restoreSession()) {
                                        is com.eventmanager.app.data.remote.FirebaseAuthResult.Success -> {
                                            authEmail = restored.email
                                            settingsManager.setFirebaseAuthEmail(restored.email.orEmpty())
                                        }
                                        else -> {
                                            busy = false
                                            status = needSignInMsg
                                            onRequestFirebaseSignIn { result ->
                                                when (result) {
                                                    is com.eventmanager.app.data.remote.FirebaseAuthResult.Success -> {
                                                        authEmail = result.email
                                                        settingsManager.setFirebaseAuthEmail(result.email.orEmpty())
                                                        status = null
                                                    }
                                                    is com.eventmanager.app.data.remote.FirebaseAuthResult.Error -> {
                                                        status = result.message
                                                    }
                                                }
                                            }
                                            return@launch
                                        }
                                    }
                                }
                                val uid = auth.currentUserId().orEmpty()
                                if (uid.isNotBlank() && activeOrgId().isNotBlank()) {
                                    val gateway = com.eventmanager.app.data.remote.createFirestoreGateway(
                                        platformContext,
                                        settingsManager,
                                    )
                                    runCatching {
                                        com.eventmanager.app.data.remote.FirebaseMemberSignIn.afterGoogleSignIn(
                                            gateway = gateway,
                                            settings = settingsManager,
                                            uid = uid,
                                            email = auth.currentUserEmail() ?: authEmail,
                                            isOrgBootstrap = false,
                                            joinWithBootstrapCode = true,
                                        )
                                    }.onFailure { e ->
                                        busy = false
                                        status = e.message
                                        return@launch
                                    }
                                }
                            } else {
                                settingsManager.saveSpreadsheetId(spreadsheetId.trim())
                            }
                            val result = onFollow(
                                activeOrgId().takeIf { announcement.backendType == BackendType.FIREBASE },
                                spreadsheetId.takeIf { announcement.backendType == BackendType.SHEETS },
                            )
                            busy = false
                            status = when (result) {
                                is SyncResult.Success -> result.message
                                is SyncResult.Error -> result.message
                            }
                        }
                    },
                    enabled = !busy && canConnect,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.follow_connect))
                }
                TextButton(onClick = onCancelUnavailable) {
                    Text(stringResource(Res.string.follow_later))
                }
                // Last resort. A migration that failed after announcing used to leave this
                // dialog up with no way out and no way through.
                if (onFactoryReset != null) {
                    TextButton(onClick = { showResetConfirm = true }) {
                        Text(
                            stringResource(Res.string.follow_factory_reset),
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }

    if (showResetConfirm && onFactoryReset != null) {
        AlertDialog(
            onDismissRequest = { showResetConfirm = false },
            title = { Text(stringResource(Res.string.follow_factory_reset)) },
            text = { Text(stringResource(Res.string.follow_factory_reset_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showResetConfirm = false
                        onFactoryReset()
                    },
                ) {
                    Text(
                        stringResource(Res.string.follow_factory_reset_confirm),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetConfirm = false }) {
                    Text(stringResource(Res.string.migration_wizard_cancel))
                }
            },
        )
    }

    if (showJoinScan && platformContext != null) {
        RawPayloadQrScannerDialog(
            platformContext = platformContext,
            onDismiss = { showJoinScan = false },
            onPayload = { raw ->
                when (val result = FirebaseJoinImport.apply(settingsManager, raw)) {
                    is FirebaseJoinImportResult.Undecodable -> {
                        joinError = result.message ?: joinInvalidMsg
                    }
                    is FirebaseJoinImportResult.Incomplete -> {
                        joinError = when (result.problem) {
                            FirebaseJoinImportProblem.OAUTH_SECRET_MISSING -> joinOAuthMissingMsg
                            FirebaseJoinImportProblem.INVITATION_MISSING -> joinInviteMissingMsg
                        }
                        reloadFirebaseFromSettings()
                    }
                    is FirebaseJoinImportResult.Complete -> {
                        joinError = null
                        status = null
                        reloadFirebaseFromSettings()
                        joinedViaQr = true
                    }
                }
                showJoinScan = false
            },
        )
    }
}

@Composable
fun BackendMigrationWizardScreen(
    direction: MigrationDirection,
    settingsManager: SettingsManager,
    platformContext: PlatformContext?,
    onMigrateToFirebase: suspend (orgId: String) -> SyncResult,
    onMigrateToSheets: suspend (spreadsheetId: String) -> SyncResult,
    onDismiss: () -> Unit,
    onCancelInFlight: () -> Unit = {},
    onRequestFirebaseSignIn: ((com.eventmanager.app.data.remote.FirebaseAuthResult) -> Unit) -> Unit = { onResult ->
        onResult(com.eventmanager.app.data.remote.FirebaseAuthResult.Error("Firebase Sign-In is not available"))
    },
) {
    // Migration always targets exactly one org — multi-org lists confuse which destination
    // receives the Sheets data. Extra orgs are added later in Admin → Firebase sync.
    fun migrationTargetOrgs(): List<com.eventmanager.app.data.remote.FirebaseConfiguredOrg> {
        val activeId = settingsManager.getFirebaseOrgId().trim()
        val existing = settingsManager.getFirebaseConfiguredOrgs()
        val primary = existing.firstOrNull { it.orgId.trim() == activeId && activeId.isNotBlank() }
            ?: existing.firstOrNull { it.orgId.isNotBlank() }
            ?: com.eventmanager.app.data.remote.FirebaseConfiguredOrg(
                orgId = activeId,
                colorArgb = com.eventmanager.app.data.remote.FirebaseConfiguredOrgCodec.defaultColorForIndex(0),
            )
        return listOf(primary)
    }
    var configuredOrgs by remember { mutableStateOf(migrationTargetOrgs()) }
    fun activeOrgId(): String = configuredOrgs.firstOrNull { it.orgId.isNotBlank() }?.orgId?.trim()
        .orEmpty()
        .ifBlank { settingsManager.getFirebaseOrgId().trim() }
    var spreadsheetId by remember { mutableStateOf(settingsManager.getSpreadsheetId()) }
    var projectId by remember { mutableStateOf(settingsManager.getFirebaseProjectId()) }
    var applicationId by remember { mutableStateOf(settingsManager.getFirebaseApplicationId()) }
    var apiKey by remember { mutableStateOf(settingsManager.getFirebaseApiKey()) }
    var webClientId by remember { mutableStateOf(settingsManager.getFirebaseWebClientId()) }
    var webClientSecret by remember { mutableStateOf(settingsManager.getFirebaseWebClientSecret()) }
    var authEmail by remember { mutableStateOf(settingsManager.getFirebaseAuthEmail().ifBlank { null }) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var succeeded by remember { mutableStateOf(false) }
    var sheetsUploadStatus by remember { mutableStateOf<String?>(null) }
    var serviceAccountReady by remember { mutableStateOf(settingsManager.isConfigured()) }
    val scope = rememberCoroutineScope()
    val needSignInMsg = stringResource(Res.string.follow_need_sign_in)
    val cancelHint = stringResource(Res.string.migration_wizard_cancel_inflight_hint)
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1_000)
            authEmail = settingsManager.getFirebaseAuthEmail().ifBlank { null }
        }
    }

    val canStart = when (direction) {
        MigrationDirection.SHEETS_TO_FIREBASE ->
            firebaseConnectionReady(configuredOrgs, projectId, applicationId, apiKey, authEmail)
        MigrationDirection.FIREBASE_TO_SHEETS ->
            spreadsheetId.isNotBlank() && serviceAccountReady
    }

    Card(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 6.dp),
    ) {
        Column(
            modifier = Modifier.padding(20.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            var showFirebaseTutorial by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    when (direction) {
                        MigrationDirection.SHEETS_TO_FIREBASE ->
                            stringResource(Res.string.migration_wizard_to_firebase_title)
                        MigrationDirection.FIREBASE_TO_SHEETS ->
                            stringResource(Res.string.migration_wizard_to_sheets_title)
                    },
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (direction == MigrationDirection.SHEETS_TO_FIREBASE) {
                    IconButton(onClick = { showFirebaseTutorial = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.HelpOutline,
                            contentDescription = stringResource(Res.string.firebase_tutorial_help_cd),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (showFirebaseTutorial) {
                com.eventmanager.app.ui.components.FirebaseSetupTutorialDialog(
                    onDismiss = { showFirebaseTutorial = false },
                    projectId = settingsManager.getFirebaseProjectId(),
                )
            }
            Text(
                stringResource(Res.string.migration_wizard_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            GuidedStatusBanner(
                title = stringResource(Res.string.migration_wizard_step_config),
                subtitle = when (direction) {
                    MigrationDirection.SHEETS_TO_FIREBASE ->
                        stringResource(Res.string.migration_wizard_step_config_firebase)
                    MigrationDirection.FIREBASE_TO_SHEETS ->
                        stringResource(Res.string.migration_wizard_step_config_sheets)
                },
                ready = canStart,
                warning = !canStart,
            )

            GuidedStepCard(
                title = stringResource(Res.string.migration_wizard_step_config),
                body = when (direction) {
                    MigrationDirection.SHEETS_TO_FIREBASE ->
                        stringResource(Res.string.migration_wizard_step_config_firebase)
                    MigrationDirection.FIREBASE_TO_SHEETS ->
                        stringResource(Res.string.migration_wizard_step_config_sheets)
                },
            ) {
                when (direction) {
                    MigrationDirection.SHEETS_TO_FIREBASE -> {
                        FirebaseConnectionFields(
                            configuredOrgs = configuredOrgs,
                            onConfiguredOrgsChange = { configuredOrgs = it.take(1) },
                            allowMultipleOrgs = false,
                            projectId = projectId,
                            onProjectIdChange = { projectId = it },
                            applicationId = applicationId,
                            onApplicationIdChange = { applicationId = it },
                            apiKey = apiKey,
                            onApiKeyChange = { apiKey = it },
                            webClientId = webClientId,
                            onWebClientIdChange = { webClientId = it },
                            webClientSecret = webClientSecret,
                            onWebClientSecretChange = { webClientSecret = it },
                            authEmail = authEmail,
                            onSignIn = {
                                settingsManager.setFirebaseConfiguredOrgs(configuredOrgs.take(1))
                                settingsManager.setFirebaseProjectId(projectId.trim())
                                settingsManager.setFirebaseApplicationId(applicationId.trim())
                                settingsManager.setFirebaseApiKey(apiKey.trim())
                                settingsManager.setFirebaseWebClientId(webClientId.trim())
                                settingsManager.setFirebaseWebClientSecret(webClientSecret.trim())
                                status = null
                                onRequestFirebaseSignIn { result ->
                                    when (result) {
                                        is com.eventmanager.app.data.remote.FirebaseAuthResult.Success -> {
                                            authEmail = result.email
                                            settingsManager.setFirebaseAuthEmail(result.email.orEmpty())
                                            status = null
                                        }
                                        is com.eventmanager.app.data.remote.FirebaseAuthResult.Error -> {
                                            status = result.message
                                        }
                                    }
                                }
                            },
                            compact = true,
                            signInFeedback = status?.takeIf { authEmail.isNullOrBlank() },
                        )
                    }
                    MigrationDirection.FIREBASE_TO_SHEETS -> {
                        SheetsMigrationCredentialsSection(
                            spreadsheetId = spreadsheetId,
                            onSpreadsheetIdChange = { spreadsheetId = it },
                            settingsManager = settingsManager,
                            platformContext = platformContext,
                            uploadStatus = sheetsUploadStatus,
                            onUploadStatus = { sheetsUploadStatus = it },
                            onConfiguredChanged = { serviceAccountReady = it },
                        )
                    }
                }
            }

            GuidedStepCard(
                title = stringResource(Res.string.migration_wizard_step_run),
                body = stringResource(Res.string.migration_wizard_step_run_body),
            ) {
                if (busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                status?.let {
                    Text(
                        it,
                        color = if (succeeded) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }

                // A finished migration must not leave "Start migration" and "Cancel" as the only
                // controls: dismissing through Cancel reads like a rollback and left admins
                // unsure whether the switch had happened.
                if (succeeded) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(Res.string.migration_wizard_done))
                    }
                    return@GuidedStepCard
                }

                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            if (direction == MigrationDirection.SHEETS_TO_FIREBASE) {
                                settingsManager.setFirebaseConfiguredOrgs(configuredOrgs.take(1))
                                settingsManager.setFirebaseProjectId(projectId.trim())
                                settingsManager.setFirebaseApplicationId(applicationId.trim())
                                settingsManager.setFirebaseApiKey(apiKey.trim())
                                settingsManager.setFirebaseWebClientId(webClientId.trim())
                                settingsManager.setFirebaseWebClientSecret(webClientSecret.trim())
                                val auth = com.eventmanager.app.data.remote.createFirebaseAuthService(platformContext)
                                if (!auth.isSignedIn()) {
                                    when (val restored = auth.restoreSession()) {
                                        is com.eventmanager.app.data.remote.FirebaseAuthResult.Success -> {
                                            authEmail = restored.email
                                            settingsManager.setFirebaseAuthEmail(restored.email.orEmpty())
                                        }
                                        else -> {
                                            busy = false
                                            status = needSignInMsg
                                            onRequestFirebaseSignIn { result ->
                                                when (result) {
                                                    is com.eventmanager.app.data.remote.FirebaseAuthResult.Success -> {
                                                        authEmail = result.email
                                                        settingsManager.setFirebaseAuthEmail(result.email.orEmpty())
                                                        status = null
                                                    }
                                                    is com.eventmanager.app.data.remote.FirebaseAuthResult.Error -> {
                                                        status = result.message
                                                    }
                                                }
                                            }
                                            return@launch
                                        }
                                    }
                                }
                            }
                            val result = when (direction) {
                                MigrationDirection.SHEETS_TO_FIREBASE -> onMigrateToFirebase(activeOrgId().trim())
                                MigrationDirection.FIREBASE_TO_SHEETS -> onMigrateToSheets(spreadsheetId.trim())
                            }
                            busy = false
                            succeeded = result is SyncResult.Success
                            status = when (result) {
                                is SyncResult.Success -> result.message
                                is SyncResult.Error -> result.message
                            }
                        }
                    },
                    enabled = !busy && canStart,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.migration_wizard_start))
                }
                if (busy) {
                    TextButton(
                        onClick = {
                            onCancelInFlight()
                            status = cancelHint
                        },
                    ) {
                        Text(stringResource(Res.string.migration_wizard_cancel_inflight))
                    }
                } else {
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(Res.string.migration_wizard_cancel))
                    }
                }
            }
        }
    }
}

@Composable
fun BackendMismatchBanner(message: String) {
    GuidedStatusBanner(
        title = stringResource(Res.string.backend_mismatch_title),
        subtitle = message,
        ready = false,
        warning = true,
        modifier = Modifier.padding(12.dp),
    )
}
