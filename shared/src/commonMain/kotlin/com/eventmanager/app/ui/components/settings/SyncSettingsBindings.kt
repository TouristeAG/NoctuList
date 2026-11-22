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
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.sync_read_only_notice
import com.eventmanager.app.resources.sync_signed_out_notice
import com.eventmanager.app.resources.sync_team_domains_publish_failed
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import org.jetbrains.compose.resources.stringResource

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
    /** Why the last publish of [allowedEmailDomains] to the org failed, or null. */
    val allowedEmailDomainsError: String?,
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
    /** False on the Sheets backend, where temporary guests have no credit accounts. */
    val temporaryGuestCreditsAvailable: Boolean,
    val temporaryGuestCreditsEnabled: Boolean,
    val onTemporaryGuestCreditsEnabledChange: (Boolean) -> Unit,
    val volunteerPortalEnabled: Boolean,
    val portalServerStatus: PortalServerStatus?,
    val onVolunteerPortalEnabledChange: (Boolean) -> Unit,
    val volunteerPortalUrl: String,
    val volunteerPortalGoogleEnabled: Boolean,
    val onVolunteerPortalGoogleEnabledChange: (Boolean) -> Unit,
    val volunteerPortalShowLogo: Boolean,
    val onVolunteerPortalShowLogoChange: (Boolean) -> Unit,
    val volunteerPortalQrEnabled: Boolean,
    val onVolunteerPortalQrEnabledChange: (Boolean) -> Unit,
    val volunteerPortalCertificateEnabled: Boolean,
    val onVolunteerPortalCertificateEnabledChange: (Boolean) -> Unit,
    /** Data and actions of the certificate editor, read only while it is open. */
    val certificateEditor: CertificateEditorHost,
    val volunteerPortalGoogleAnyAccount: Boolean,
    val onVolunteerPortalGoogleAnyAccountChange: (Boolean) -> Unit,
    val volunteerPortalGoogleDomains: List<String>,
    val onAddVolunteerPortalGoogleDomain: suspend (String) -> DomainCheck,
    val onRemoveVolunteerPortalGoogleDomain: (String) -> Unit,
    val guestPortalEnabled: Boolean,
    val onGuestPortalEnabledChange: (Boolean) -> Unit,
    val temporaryGuestPortalEnabled: Boolean,
    val onTemporaryGuestPortalEnabledChange: (Boolean) -> Unit,
    val volunteerPortalAutoAccess: Boolean,
    val onVolunteerPortalAutoAccessChange: (Boolean) -> Unit,
    val volunteerPortalGoogleAuto: Boolean,
    val onVolunteerPortalGoogleAutoChange: (Boolean) -> Unit,
    val volunteerPortalGoogleTemplate: String,
    val onVolunteerPortalGoogleTemplateChange: (String) -> Unit,
    // Added for the pages.
    /** [FirebaseOrgAdminAccess.currentUserIsOrgAdmin], checked on the Firebase backend only. */
    val isOrgAdmin: Boolean,
    /** True once the first [isOrgAdmin] check has finished (at once in Sheets mode). */
    val adminChecked: Boolean,
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
 * - [onOpenLogoSettings]: shows the association logo setting (the certificate editor links to it).
 *
 * The org-admin role is only checked on the Firebase backend; in Sheets mode [SyncSettingsBindings.isOrgAdmin]
 * stays false, [SyncSettingsBindings.adminChecked] is true at once, and no Firebase call runs.
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
    onOpenLogoSettings: (() -> Unit)? = null,
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
    val temporaryGuestCreditsEnabled by viewModel.temporaryGuestCreditsEnabled.collectAsState()
    val volunteerPortalEnabled by viewModel.volunteerPortalEnabled.collectAsState()
    val volunteerPortalGoogleEnabled by viewModel.volunteerPortalGoogleEnabled.collectAsState()
    val volunteerPortalShowLogo by viewModel.volunteerPortalShowLogo.collectAsState()
    val volunteerPortalQrEnabled by viewModel.volunteerPortalQrEnabled.collectAsState()
    val volunteerPortalCertificateEnabled by viewModel.volunteerPortalCertificateEnabled.collectAsState()
    val volunteerPortalGoogleAnyAccount by viewModel.volunteerPortalGoogleAnyAccount.collectAsState()
    val volunteerPortalGoogleDomains by viewModel.volunteerPortalGoogleDomains.collectAsState()
    val guestPortalEnabled by viewModel.guestPortalEnabled.collectAsState()
    val temporaryGuestPortalEnabled by viewModel.temporaryGuestPortalEnabled.collectAsState()
    val volunteerPortalAutoAccess by viewModel.volunteerPortalAutoAccess.collectAsState()
    val volunteerPortalGoogleAuto by viewModel.volunteerPortalGoogleAuto.collectAsState()
    val volunteerPortalGoogleTemplate by viewModel.volunteerPortalGoogleTemplate.collectAsState()
    val portalServerStatus by viewModel.portalServerStatus.collectAsState()
    val lastSyncAt by viewModel.lastSyncTime.collectAsState()

    var isOrgAdmin by remember { mutableStateOf(false) }
    var adminChecked by remember { mutableStateOf(false) }
    val writableOrgId = settingsManager.resolveWritableFirebaseOrgId()
    val firebaseBackend = settingsManager.getBackendType() == BackendType.FIREBASE
    LaunchedEffect(authEmail, writableOrgId, settingsManager, firebaseBackend) {
        if (!firebaseBackend) {
            isOrgAdmin = false
            adminChecked = true
            return@LaunchedEffect
        }
        isOrgAdmin = FirebaseOrgAdminAccess.currentUserIsOrgAdmin(platformContext, settingsManager)
        adminChecked = true
    }
    var allowedEmailDomainsError by remember { mutableStateOf<String?>(null) }
    val domainsPublishFailed = stringResource(Res.string.sync_team_domains_publish_failed, "%1\$s")

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
            allowedEmailDomainsError = null
            scope.launch {
                val org = settingsManager.resolveWritableFirebaseOrgId()
                if (org.isBlank()) return@launch
                val gateway = createFirestoreGateway(platformContext, settingsManager)
                try {
                    MemberRoleAdmin.publishAllowedEmailDomains(
                        gateway = gateway,
                        orgId = org,
                        domains = domains,
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.simpleName.orEmpty()
                    allowedEmailDomainsError = domainsPublishFailed.replace("%1\$s", detail)
                    onStatusMessage(e.message)
                }
            }
        },
        allowedEmailDomainsError = allowedEmailDomainsError,
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
        temporaryGuestCreditsAvailable = viewModel.isTemporaryGuestFeaturesEnabled(),
        temporaryGuestCreditsEnabled = temporaryGuestCreditsEnabled,
        onTemporaryGuestCreditsEnabledChange = { viewModel.setTemporaryGuestCreditsEnabled(it) },
        volunteerPortalEnabled = volunteerPortalEnabled,
        portalServerStatus = portalServerStatus,
        onVolunteerPortalEnabledChange = { viewModel.setVolunteerPortalEnabled(it) },
        volunteerPortalUrl = viewModel.volunteerPortalUrl(),
        volunteerPortalGoogleEnabled = volunteerPortalGoogleEnabled,
        onVolunteerPortalGoogleEnabledChange = { viewModel.setVolunteerPortalGoogleEnabled(it) },
        volunteerPortalShowLogo = volunteerPortalShowLogo,
        onVolunteerPortalShowLogoChange = { viewModel.setVolunteerPortalShowLogo(it) },
        volunteerPortalQrEnabled = volunteerPortalQrEnabled,
        onVolunteerPortalQrEnabledChange = { viewModel.setVolunteerPortalQrEnabled(it) },
        volunteerPortalCertificateEnabled = volunteerPortalCertificateEnabled,
        onVolunteerPortalCertificateEnabledChange = { viewModel.setVolunteerPortalCertificateEnabled(it) },
        certificateEditor = remember(viewModel, onOpenLogoSettings) { CertificateEditorHost(viewModel, onOpenLogoSettings) },
        volunteerPortalGoogleAnyAccount = volunteerPortalGoogleAnyAccount,
        onVolunteerPortalGoogleAnyAccountChange = { viewModel.setVolunteerPortalGoogleAnyAccount(it) },
        volunteerPortalGoogleDomains = volunteerPortalGoogleDomains,
        onAddVolunteerPortalGoogleDomain = { viewModel.addVolunteerPortalGoogleDomain(it) },
        onRemoveVolunteerPortalGoogleDomain = { viewModel.removeVolunteerPortalGoogleDomain(it) },
        guestPortalEnabled = guestPortalEnabled,
        onGuestPortalEnabledChange = { viewModel.setGuestPortalEnabled(it) },
        temporaryGuestPortalEnabled = temporaryGuestPortalEnabled,
        onTemporaryGuestPortalEnabledChange = { viewModel.setTemporaryGuestPortalEnabled(it) },
        volunteerPortalAutoAccess = volunteerPortalAutoAccess,
        onVolunteerPortalAutoAccessChange = { viewModel.setVolunteerPortalAutoAccess(it) },
        volunteerPortalGoogleAuto = volunteerPortalGoogleAuto,
        onVolunteerPortalGoogleAutoChange = { viewModel.setVolunteerPortalGoogleAuto(it) },
        volunteerPortalGoogleTemplate = volunteerPortalGoogleTemplate,
        onVolunteerPortalGoogleTemplateChange = { viewModel.setVolunteerPortalGoogleTemplate(it) },
        isOrgAdmin = isOrgAdmin,
        adminChecked = adminChecked,
        onOpenFirebaseGuide = onOpenFirebaseGuide,
        onOpenMigration = onOpenMigration,
        lastSyncAt = lastSyncAt,
        onSyncNow = onSyncNowOverride ?: { viewModel.performFullSync() },
        scope = scope,
    )
}

/** The notice line of a Firebase org page (Équipe, Fonctions en ligne, Sauvegarde), see [syncPageNotice]. */
@Composable
internal fun syncPageNoticeText(b: SyncSettingsBindings): String? =
    when (syncPageNotice(signedIn = !b.authEmail.isNullOrBlank(), adminChecked = b.adminChecked, isOrgAdmin = b.isOrgAdmin)) {
        SyncPageNotice.SIGNED_OUT -> stringResource(Res.string.sync_signed_out_notice)
        SyncPageNotice.READ_ONLY -> stringResource(Res.string.sync_read_only_notice)
        null -> null
    }

/** What the certificate editor reads and writes; the lists are collected only while it is open. */
class CertificateEditorHost(
    private val viewModel: EventManagerViewModel,
    val onOpenLogoSettings: (() -> Unit)?,
) {
    val volunteers get() = viewModel.volunteers
    val jobs get() = viewModel.jobs
    val jobTypes get() = viewModel.jobTypeConfigs
    val logo get() = viewModel.institutionLogoPng
    fun template() = viewModel.volunteerPortalCertificateTemplate()
    fun associationName() = viewModel.certificateAssociationName()
    fun gmailAddress() = viewModel.certificateGmailAddress()
    fun save(template: com.eventmanager.app.portal.certificate.CertificateTemplate) =
        viewModel.saveVolunteerPortalCertificateTemplate(template)
}
