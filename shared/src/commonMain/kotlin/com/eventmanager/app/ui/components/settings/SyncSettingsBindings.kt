package com.eventmanager.app.ui.components.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import com.eventmanager.app.data.remote.BackendType
import com.eventmanager.app.data.remote.FirebaseAuthResult
import com.eventmanager.app.data.remote.FirebaseConfiguredOrg
import com.eventmanager.app.data.remote.FirebaseMemberSignIn
import com.eventmanager.app.data.remote.FirebaseOrgAdminAccess
import com.eventmanager.app.data.remote.MemberRoleAdmin
import com.eventmanager.app.data.remote.MigrationDirection
import com.eventmanager.app.data.remote.createFirebaseAuthService
import com.eventmanager.app.data.remote.createFirestoreGateway
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.portal.DomainCheck
import com.eventmanager.app.portal.PortalServerStatus
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Everything the sync settings pages read and do. Built once per screen by
 * [rememberSyncSettingsBindings] so the desktop and Android screens share one copy of the
 * sign-in, sign-out, allowed-domains publish, project field and organisation callbacks.
 */
class SyncSettingsBindings(
    val configuredOrgs: List<FirebaseConfiguredOrg>,
    val onConfiguredOrgsChange: (List<FirebaseConfiguredOrg>) -> Unit,
    val onOrgIdCommitted: (String) -> Unit,
    val authEmail: String?,
    val onSignIn: () -> Unit,
    val onSignOut: () -> Unit,
    val onMigrateToSheets: () -> Unit,
    val onMirrorExport: suspend () -> Unit,
    val platformContext: PlatformContext,
    val onMirrorSettingsChanged: () -> Unit,
    val settingsManager: SettingsManager,
    val projectId: String,
    val apiKey: String,
    val applicationId: String,
    val webClientId: String,
    val webClientSecret: String,
    val onProjectIdChange: (String) -> Unit,
    val onApiKeyChange: (String) -> Unit,
    val onApplicationIdChange: (String) -> Unit,
    val onWebClientIdChange: (String) -> Unit,
    val onWebClientSecretChange: (String) -> Unit,
    val allowedEmailDomains: List<String>,
    val onAllowedEmailDomainsChange: (List<String>) -> Unit,
    val signInFeedback: String?,
    val profilePhotosEnabled: Boolean,
    val onProfilePhotosEnabledChange: (Boolean) -> Unit,
    val driveImportEnabled: Boolean,
    val onDriveImportEnabledChange: (Boolean) -> Unit,
    val guestFormsEnabled: Boolean,
    val onGuestFormsEnabledChange: (Boolean) -> Unit,
    val guestFormsBilleterieValidateEnabled: Boolean,
    val onGuestFormsBilleterieValidateEnabledChange: (Boolean) -> Unit,
    val guestFormSiteOrigin: String,
    val volunteerPortalEnabled: Boolean,
    val portalServerStatus: PortalServerStatus?,
    val onVolunteerPortalEnabledChange: (Boolean) -> Unit,
    val volunteerPortalUrl: String,
    val volunteerPortalGoogleEnabled: Boolean,
    val onVolunteerPortalGoogleEnabledChange: (Boolean) -> Unit,
    val volunteerPortalShowLogo: Boolean,
    val onVolunteerPortalShowLogoChange: (Boolean) -> Unit,
    val volunteerPortalGoogleDomains: List<String>,
    val onAddVolunteerPortalGoogleDomain: suspend (String) -> DomainCheck,
    val onRemoveVolunteerPortalGoogleDomain: (String) -> Unit,
    // Added for the pages.
    /** [FirebaseOrgAdminAccess.currentUserIsOrgAdmin], checked on the Firebase backend only. */
    val isOrgAdmin: Boolean,
    val onOpenFirebaseGuide: () -> Unit,
    val onOpenMigration: (MigrationDirection) -> Unit,
    /** Sheets only. */
    val lastSyncAt: Long,
    /** Sheets only. */
    val onSyncNow: () -> Unit,
    /** Screen-level scope for long network actions, so they outlive a dialog or a page. */
    val scope: CoroutineScope,
)

/**
 * Builds [SyncSettingsBindings]. The code moved here comes from the desktop and Android settings
 * screens, which wrote it twice; its behaviour is unchanged.
 *
 * Platform hooks (all optional):
 * - [authEmailState] and [signInFeedbackState]: the screen's own state when other parts of the
 *   screen read or write the signed-in e-mail (migration sign-in, Android activity result).
 * - [onStatusMessage]: the screen's status line (`uploadStatus`), which receives the same error
 *   messages as before.
 * - [scope]: pass the screen-level scope so a sign-in, sign-out or domains publish is not
 *   cancelled when the sync section leaves the composition.
 * - [onSignInOverride]: replaces the shared sign-in flow. Android passes its activity-result
 *   launcher flow here; desktop uses the shared flow.
 * - [onSyncNowOverride]: replaces the Sheets "sync now" action (Android keeps its own call).
 *
 * The org-admin role is only checked on the Firebase backend; in Sheets mode [SyncSettingsBindings.isOrgAdmin]
 * stays false and no Firebase call runs.
 */
@Composable
fun rememberSyncSettingsBindings(
    viewModel: EventManagerViewModel,
    settingsManager: SettingsManager,
    platformContext: PlatformContext,
    onOpenFirebaseGuide: () -> Unit,
    onOpenMigration: (MigrationDirection) -> Unit,
    authEmailState: MutableState<String> = remember(settingsManager) {
        mutableStateOf(settingsManager.getFirebaseAuthEmail())
    },
    signInFeedbackState: MutableState<String?> = remember { mutableStateOf(null) },
    onStatusMessage: (String?) -> Unit = {},
    onSignInOverride: (() -> Unit)? = null,
    scope: CoroutineScope = rememberCoroutineScope(),
    onSyncNowOverride: (() -> Unit)? = null,
): SyncSettingsBindings {
    var authEmail by authEmailState
    var signInFeedback by signInFeedbackState

    var configuredOrgs by remember(settingsManager) { mutableStateOf(settingsManager.getFirebaseConfiguredOrgs()) }
    var allowedEmailDomains by remember(settingsManager) { mutableStateOf(settingsManager.getAllowedEmailDomains()) }
    // Project fields are read from the settings on every recomposition (as Android did); the
    // counter only forces a recomposition after a change (what desktop's local state did).
    var projectRevision by remember { mutableIntStateOf(0) }
    val (projectId, apiKey, applicationId, webClientId, webClientSecret) = projectRevision.let {
        listOf(
            settingsManager.getFirebaseProjectId(),
            settingsManager.getFirebaseApiKey(),
            settingsManager.getFirebaseApplicationId(),
            settingsManager.getFirebaseWebClientId(),
            settingsManager.getFirebaseWebClientSecret(),
        )
    }
    var driveImportEnabled by remember(viewModel) { mutableStateOf(viewModel.isDriveImportEnabled()) }

    val profilePhotosEnabled by viewModel.profilePhotosUploadEnabled.collectAsState()
    val guestFormsEnabled by viewModel.guestFormsEnabled.collectAsState()
    val guestFormsBilleterieValidateEnabled by viewModel.guestFormsBilleterieValidateEnabled.collectAsState()
    val volunteerPortalEnabled by viewModel.volunteerPortalEnabled.collectAsState()
    val volunteerPortalGoogleEnabled by viewModel.volunteerPortalGoogleEnabled.collectAsState()
    val volunteerPortalShowLogo by viewModel.volunteerPortalShowLogo.collectAsState()
    val volunteerPortalGoogleDomains by viewModel.volunteerPortalGoogleDomains.collectAsState()
    val portalServerStatus by viewModel.portalServerStatus.collectAsState()
    val lastSyncAt by viewModel.lastSyncTime.collectAsState()

    var isOrgAdmin by remember { mutableStateOf(false) }
    val writableOrgId = settingsManager.resolveWritableFirebaseOrgId()
    val firebaseBackend = settingsManager.getBackendType() == BackendType.FIREBASE
    LaunchedEffect(authEmail, writableOrgId, settingsManager, firebaseBackend) {
        isOrgAdmin = firebaseBackend &&
            FirebaseOrgAdminAccess.currentUserIsOrgAdmin(platformContext, settingsManager)
    }

    val sharedSignIn: () -> Unit = {
        scope.launch {
            signInFeedback = null
            when (val result = createFirebaseAuthService(platformContext).signInWithGoogle()) {
                is FirebaseAuthResult.Success -> {
                    signInFeedback = null
                    authEmail = result.email.orEmpty()
                    settingsManager.setFirebaseAuthEmail(result.email.orEmpty())
                    val org = settingsManager.getFirebaseOrgId()
                    if (org.isNotBlank() && result.uid.isNotBlank()) {
                        val gateway = createFirestoreGateway(platformContext, settingsManager)
                        runCatching {
                            FirebaseMemberSignIn.afterGoogleSignIn(
                                gateway = gateway,
                                settings = settingsManager,
                                uid = result.uid,
                                email = result.email,
                                joinWithBootstrapCode = settingsManager.getFirebaseBootstrapCode().isNotBlank(),
                            )
                        }.onFailure { e ->
                            signInFeedback = e.message
                            onStatusMessage(e.message)
                        }
                    }
                }
                is FirebaseAuthResult.Error -> {
                    signInFeedback = result.message
                    onStatusMessage(result.message)
                }
            }
        }
    }

    return SyncSettingsBindings(
        configuredOrgs = configuredOrgs,
        onConfiguredOrgsChange = {
            configuredOrgs = it
            viewModel.updateFirebaseConfiguredOrgsLocal(it)
        },
        onOrgIdCommitted = viewModel::provisionFirebaseOrg,
        authEmail = authEmail.ifBlank { null },
        onSignIn = onSignInOverride ?: sharedSignIn,
        onSignOut = {
            scope.launch {
                createFirebaseAuthService(platformContext).signOut()
                authEmail = ""
                settingsManager.setFirebaseAuthEmail("")
                signInFeedback = null
            }
        },
        onMigrateToSheets = { onOpenMigration(MigrationDirection.FIREBASE_TO_SHEETS) },
        onMirrorExport = { viewModel.exportSheetsMirrorNow() },
        platformContext = platformContext,
        onMirrorSettingsChanged = { viewModel.onSheetsMirrorSettingsChanged() },
        settingsManager = settingsManager,
        projectId = projectId,
        apiKey = apiKey,
        applicationId = applicationId,
        webClientId = webClientId,
        webClientSecret = webClientSecret,
        onProjectIdChange = {
            settingsManager.setFirebaseProjectId(it)
            projectRevision++
        },
        onApiKeyChange = {
            settingsManager.setFirebaseApiKey(it)
            projectRevision++
        },
        onApplicationIdChange = {
            settingsManager.setFirebaseApplicationId(it)
            projectRevision++
        },
        onWebClientIdChange = {
            settingsManager.setFirebaseWebClientId(it)
            projectRevision++
        },
        onWebClientSecretChange = {
            settingsManager.setFirebaseWebClientSecret(it)
            projectRevision++
        },
        allowedEmailDomains = allowedEmailDomains,
        onAllowedEmailDomainsChange = { domains ->
            allowedEmailDomains = domains
            settingsManager.setAllowedEmailDomains(domains)
            scope.launch {
                val org = settingsManager.resolveWritableFirebaseOrgId()
                if (org.isBlank()) return@launch
                val gateway = createFirestoreGateway(platformContext, settingsManager)
                runCatching {
                    MemberRoleAdmin.publishAllowedEmailDomains(
                        gateway = gateway,
                        orgId = org,
                        domains = domains,
                    )
                }.onFailure { onStatusMessage(it.message) }
            }
        },
        signInFeedback = signInFeedback,
        profilePhotosEnabled = profilePhotosEnabled,
        onProfilePhotosEnabledChange = { viewModel.setProfilePhotosEnabled(it) },
        driveImportEnabled = driveImportEnabled,
        onDriveImportEnabledChange = {
            driveImportEnabled = it
            viewModel.setDriveImportEnabled(it)
        },
        guestFormsEnabled = guestFormsEnabled,
        onGuestFormsEnabledChange = { viewModel.setGuestFormsEnabled(it) },
        guestFormsBilleterieValidateEnabled = guestFormsBilleterieValidateEnabled,
        onGuestFormsBilleterieValidateEnabledChange = { viewModel.setGuestFormsBilleterieValidateEnabled(it) },
        guestFormSiteOrigin = viewModel.guestFormSiteOrigin(),
        volunteerPortalEnabled = volunteerPortalEnabled,
        portalServerStatus = portalServerStatus,
        onVolunteerPortalEnabledChange = { viewModel.setVolunteerPortalEnabled(it) },
        volunteerPortalUrl = viewModel.volunteerPortalUrl(),
        volunteerPortalGoogleEnabled = volunteerPortalGoogleEnabled,
        onVolunteerPortalGoogleEnabledChange = { viewModel.setVolunteerPortalGoogleEnabled(it) },
        volunteerPortalShowLogo = volunteerPortalShowLogo,
        onVolunteerPortalShowLogoChange = { viewModel.setVolunteerPortalShowLogo(it) },
        volunteerPortalGoogleDomains = volunteerPortalGoogleDomains,
        onAddVolunteerPortalGoogleDomain = { viewModel.addVolunteerPortalGoogleDomain(it) },
        onRemoveVolunteerPortalGoogleDomain = { viewModel.removeVolunteerPortalGoogleDomain(it) },
        isOrgAdmin = isOrgAdmin,
        onOpenFirebaseGuide = onOpenFirebaseGuide,
        onOpenMigration = onOpenMigration,
        lastSyncAt = lastSyncAt,
        onSyncNow = onSyncNowOverride ?: { viewModel.performFullSync() },
        scope = scope,
    )
}
