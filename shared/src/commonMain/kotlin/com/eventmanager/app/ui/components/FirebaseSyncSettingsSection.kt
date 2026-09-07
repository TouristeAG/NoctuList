package com.eventmanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.remote.FirebaseOrgAdminAccess
import com.eventmanager.app.data.remote.FirebaseTeamActions
import com.eventmanager.app.data.remote.FirebaseTeamMemberListing
import com.eventmanager.app.data.remote.MemberRole
import com.eventmanager.app.data.security.canRevealFirebaseProjectSecrets
import com.eventmanager.app.data.security.firebaseOAuthCredentialsReady
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.resources.Res
import kotlinx.coroutines.launch
import com.eventmanager.app.resources.firebase_checklist_auth
import com.eventmanager.app.resources.firebase_checklist_org
import com.eventmanager.app.resources.firebase_checklist_project
import com.eventmanager.app.resources.firebase_checklist_title
import com.eventmanager.app.resources.firebase_migrate_section_body
import com.eventmanager.app.resources.firebase_migrate_section_title
import com.eventmanager.app.resources.firebase_migrate_to_sheets
import com.eventmanager.app.resources.firebase_member_email_hint
import com.eventmanager.app.resources.firebase_member_email_label
import com.eventmanager.app.resources.firebase_member_uid_hint
import com.eventmanager.app.resources.firebase_member_uid_label
import com.eventmanager.app.resources.firebase_member_unknown_email
import com.eventmanager.app.resources.firebase_role_admin
import com.eventmanager.app.resources.firebase_role_member
import com.eventmanager.app.resources.firebase_settings_intro
import com.eventmanager.app.resources.firebase_settings_section_access
import com.eventmanager.app.resources.firebase_settings_section_devices
import com.eventmanager.app.resources.firebase_settings_section_optional
import com.eventmanager.app.resources.institution_settings_firebase_admin_only
import com.eventmanager.app.resources.firebase_status_need_config
import com.eventmanager.app.resources.firebase_status_need_sign_in
import com.eventmanager.app.resources.firebase_status_ready
import com.eventmanager.app.resources.firebase_status_signed_in_as
import com.eventmanager.app.resources.firebase_team_admins_empty
import com.eventmanager.app.resources.firebase_team_admins_heading
import com.eventmanager.app.resources.firebase_team_assign_busy
import com.eventmanager.app.resources.firebase_team_assign_success_admin
import com.eventmanager.app.resources.firebase_team_assign_success_member
import com.eventmanager.app.resources.firebase_team_body
import com.eventmanager.app.resources.firebase_team_error_blank_uid
import com.eventmanager.app.resources.firebase_team_error_email_required
import com.eventmanager.app.resources.firebase_team_error_no_org
import com.eventmanager.app.resources.firebase_team_error_not_ready
import com.eventmanager.app.resources.firebase_team_error_permission
import com.eventmanager.app.resources.firebase_team_load_error
import com.eventmanager.app.resources.firebase_team_members_heading
import com.eventmanager.app.resources.firebase_team_not_domains
import com.eventmanager.app.resources.firebase_team_refresh
import com.eventmanager.app.resources.firebase_team_role_admin_desc
import com.eventmanager.app.resources.firebase_team_role_member_desc
import com.eventmanager.app.resources.firebase_team_roles_heading
import com.eventmanager.app.resources.firebase_team_steps
import com.eventmanager.app.resources.firebase_team_title
import com.eventmanager.app.resources.firebase_team_working_org
import com.eventmanager.app.resources.sheets_migrate_card_body
import com.eventmanager.app.resources.sheets_migrate_card_title
import com.eventmanager.app.resources.sheets_migrate_to_firebase
import com.eventmanager.app.resources.firebase_tutorial_help_cd
import com.eventmanager.app.resources.profile_photos_enable
import com.eventmanager.app.resources.profile_photos_settings_body
import com.eventmanager.app.resources.profile_photos_settings_title
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import org.jetbrains.compose.resources.stringResource

/**
 * Admin Full settings — Firebase sync (ordered: org → project → sign-in → devices → access → optional).
 */
@Composable
fun FirebaseSyncSettingsSection(
    configuredOrgs: List<com.eventmanager.app.data.remote.FirebaseConfiguredOrg>,
    onConfiguredOrgsChange: (List<com.eventmanager.app.data.remote.FirebaseConfiguredOrg>) -> Unit,
    onOrgIdCommitted: (String) -> Unit = {},
    authEmail: String?,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit,
    onMigrateToSheets: () -> Unit,
    onMirrorExport: suspend () -> Unit,
    platformContext: PlatformContext? = null,
    onMirrorSettingsChanged: () -> Unit = {},
    settingsManager: com.eventmanager.app.data.sync.SettingsManager? = null,
    projectId: String = "",
    apiKey: String = "",
    applicationId: String = "",
    webClientId: String = "",
    webClientSecret: String = "",
    onProjectIdChange: (String) -> Unit = {},
    onApiKeyChange: (String) -> Unit = {},
    onApplicationIdChange: (String) -> Unit = {},
    onWebClientIdChange: (String) -> Unit = {},
    onWebClientSecretChange: (String) -> Unit = {},
    allowedEmailDomains: List<String> = emptyList(),
    onAllowedEmailDomainsChange: (List<String>) -> Unit = {},
    signInFeedback: String? = null,
    profilePhotosEnabled: Boolean = false,
    onProfilePhotosEnabledChange: (Boolean) -> Unit = {},
    guestFormsEnabled: Boolean = false,
    onGuestFormsEnabledChange: (Boolean) -> Unit = {},
    guestFormSiteOrigin: String = "",
) {
    val ready = firebaseConnectionReady(configuredOrgs, projectId, applicationId, apiKey, authEmail)
    val projectReady = projectId.isNotBlank() && applicationId.isNotBlank() && apiKey.isNotBlank()
    val orgReady = firebaseConfiguredOrgsReady(configuredOrgs)
    val authReady = !authEmail.isNullOrBlank()
    var isFirebaseOrgAdmin by remember { mutableStateOf(false) }
    val writableOrgId = settingsManager?.resolveWritableFirebaseOrgId().orEmpty()
    LaunchedEffect(authEmail, writableOrgId, settingsManager) {
        isFirebaseOrgAdmin = if (settingsManager == null) {
            false
        } else {
            FirebaseOrgAdminAccess.currentUserIsOrgAdmin(platformContext, settingsManager)
        }
    }
    val allowProjectSecrets = canRevealFirebaseProjectSecrets(
        isFirebaseOrgAdmin = isFirebaseOrgAdmin,
        projectConfigured = projectReady,
        oauthCredentialsReady = firebaseOAuthCredentialsReady(webClientId, webClientSecret),
    )
    var bootstrapCode by remember(settingsManager) {
        mutableStateOf(settingsManager?.getFirebaseBootstrapCode().orEmpty())
    }

    val statusTitle = when {
        ready -> stringResource(Res.string.firebase_status_ready)
        projectReady && orgReady -> stringResource(Res.string.firebase_status_need_sign_in)
        else -> stringResource(Res.string.firebase_status_need_config)
    }
    val statusSubtitle = authEmail?.let { stringResource(Res.string.firebase_status_signed_in_as, it) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(Res.string.firebase_settings_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        GuidedStatusBanner(
            title = statusTitle,
            subtitle = statusSubtitle,
            ready = ready,
            warning = !ready && orgReady,
        )

        // —— Setup (steps 1–3) ——
        FirebaseConfiguredOrgsSection(
            configuredOrgs = configuredOrgs,
            onConfiguredOrgsChange = onConfiguredOrgsChange,
            onOrgIdCommitted = onOrgIdCommitted,
        )

        if (settingsManager != null) {
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
                allowProjectSecrets = allowProjectSecrets,
            )
        }

        FirebaseSignInStep(
            authEmail = authEmail,
            onSignIn = onSignIn,
            onSignOut = onSignOut,
            signInFeedback = signInFeedback,
        )

        GuidedStepCard(title = stringResource(Res.string.firebase_checklist_title)) {
            GuidedChecklistItem(
                label = stringResource(Res.string.firebase_checklist_org),
                done = orgReady,
            )
            GuidedChecklistItem(
                label = stringResource(Res.string.firebase_checklist_project),
                done = projectReady,
            )
            GuidedChecklistItem(
                label = stringResource(Res.string.firebase_checklist_auth),
                done = authReady,
            )
        }

        FirebaseSettingsSectionHeader(stringResource(Res.string.firebase_settings_section_devices))
        if (settingsManager != null) {
            FirebaseAdminJoinQrCard(
                orgId = configuredOrgs.firstOrNull { it.orgId.isNotBlank() }?.orgId.orEmpty(),
                projectId = projectId,
                applicationId = applicationId,
                apiKey = apiKey,
                webClientId = webClientId,
                webClientSecret = webClientSecret,
                bootstrapCode = bootstrapCode,
                allowProjectSecrets = isFirebaseOrgAdmin,
                onBootstrapCodeChange = { code ->
                    bootstrapCode = code
                    settingsManager.setFirebaseBootstrapCode(code)
                },
                onRotateBootstrapCode = if (isFirebaseOrgAdmin && platformContext != null) {
                    {
                        val org = settingsManager.resolveWritableFirebaseOrgId()
                        val gateway = com.eventmanager.app.data.remote.createFirestoreGateway(
                            platformContext,
                            settingsManager,
                        )
                        val code = com.eventmanager.app.data.remote.MemberRoleAdmin.rotateBootstrapCode(
                            gateway = gateway,
                            orgId = org,
                            allowedEmailDomains = settingsManager.getAllowedEmailDomains(),
                        )
                        settingsManager.setFirebaseBootstrapCode(code)
                        code
                    }
                } else {
                    null
                },
            )
        }

        FirebaseSettingsSectionHeader(stringResource(Res.string.firebase_settings_section_access))
        if (!isFirebaseOrgAdmin && authReady) {
            Text(
                stringResource(Res.string.institution_settings_firebase_admin_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FirebaseAllowedEmailDomainsSection(
            domains = allowedEmailDomains,
            onDomainsChange = onAllowedEmailDomainsChange,
            enabled = isFirebaseOrgAdmin,
        )

        if (settingsManager != null) {
            FirebaseTeamAccessCard(
                platformContext = platformContext,
                settingsManager = settingsManager,
            )
        }

        FirebaseSettingsSectionHeader(stringResource(Res.string.firebase_settings_section_optional))
        if (settingsManager != null) {
            GuestFormSettingsCard(
                enabled = guestFormsEnabled,
                onEnabledChange = onGuestFormsEnabledChange,
                siteOrigin = guestFormSiteOrigin,
                canEdit = isFirebaseOrgAdmin,
            )
            GuidedStepCard(
                title = stringResource(Res.string.profile_photos_settings_title),
                body = stringResource(Res.string.profile_photos_settings_body),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        stringResource(Res.string.profile_photos_enable),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                    Switch(
                        checked = profilePhotosEnabled,
                        onCheckedChange = { enabled ->
                            onProfilePhotosEnabledChange(enabled)
                        },
                        enabled = isFirebaseOrgAdmin,
                    )
                }
            }
            FirebaseSheetsMirrorSettingsSection(
                settingsManager = settingsManager,
                platformContext = platformContext,
                onMirrorExport = onMirrorExport,
                onMirrorSettingsChanged = onMirrorSettingsChanged,
                enabled = isFirebaseOrgAdmin,
            )
        }

        GuidedStepCard(
            title = stringResource(Res.string.firebase_migrate_section_title),
            body = stringResource(Res.string.firebase_migrate_section_body),
        ) {
            Button(onClick = onMigrateToSheets, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.firebase_migrate_to_sheets))
            }
        }
    }
}

@Composable
private fun FirebaseSettingsSectionHeader(title: String) {
    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun SheetsMigrateToFirebaseButton(
    onClick: () -> Unit,
    projectId: String = "",
) {
    var showTutorial by remember { mutableStateOf(false) }
    GuidedStepCard(
        title = stringResource(Res.string.sheets_migrate_card_title),
        body = stringResource(Res.string.sheets_migrate_card_body),
        modifier = Modifier.padding(vertical = 8.dp),
        onHelpClick = { showTutorial = true },
        helpContentDescription = stringResource(Res.string.firebase_tutorial_help_cd),
    ) {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(Res.string.sheets_migrate_to_firebase))
        }
    }
    if (showTutorial) {
        FirebaseSetupTutorialDialog(
            onDismiss = { showTutorial = false },
            projectId = projectId,
        )
    }
}

@Composable
private fun FirebaseTeamAccessCard(
    platformContext: PlatformContext?,
    settingsManager: SettingsManager,
) {
    val scope = rememberCoroutineScope()
    var memberUid by remember { mutableStateOf("") }
    var memberEmail by remember { mutableStateOf("") }
    var members by remember { mutableStateOf<List<FirebaseTeamMemberListing>>(emptyList()) }
    var loadingMembers by remember { mutableStateOf(true) }
    var assigning by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var statusIsError by remember { mutableStateOf(false) }
    var membersError by remember { mutableStateOf<String?>(null) }
    val writableOrgId = settingsManager.resolveWritableFirebaseOrgId()

    val noOrg = stringResource(Res.string.firebase_team_error_no_org)
    val blankUid = stringResource(Res.string.firebase_team_error_blank_uid)
    val emailRequired = stringResource(Res.string.firebase_team_error_email_required)
    val permissionDenied = stringResource(Res.string.firebase_team_error_permission)
    val notReady = stringResource(Res.string.firebase_team_error_not_ready)
    val loadError = stringResource(Res.string.firebase_team_load_error)
    val successAdmin = stringResource(Res.string.firebase_team_assign_success_admin)
    val successMember = stringResource(Res.string.firebase_team_assign_success_member)

    fun mapError(throwable: Throwable): String {
        val raw = throwable.message.orEmpty()
        return when {
            raw.contains("NO_ORG") -> noOrg
            raw.contains("BLANK_UID") -> blankUid
            raw.contains("EMAIL_REQUIRED") -> emailRequired
            raw.contains("NOT_READY") || raw.contains("not initialized", ignoreCase = true) -> notReady
            raw.contains("PERMISSION") || raw.contains("permission-denied", ignoreCase = true) ->
                permissionDenied
            else -> raw.ifBlank { throwable::class.simpleName.orEmpty() }
        }
    }

    suspend fun reloadMembers() {
        loadingMembers = true
        membersError = null
        FirebaseTeamActions.loadMembers(platformContext, settingsManager).fold(
            onSuccess = { members = it },
            onFailure = {
                members = emptyList()
                membersError = mapError(it).ifBlank { loadError }
            },
        )
        loadingMembers = false
    }

    suspend fun assign(role: MemberRole) {
        assigning = true
        status = null
        try {
            val result = FirebaseTeamActions.assignRole(
                platformContext = platformContext,
                settings = settingsManager,
                uid = memberUid.trim(),
                email = memberEmail.trim().ifBlank { null },
                role = role,
            )
            result.fold(
                onSuccess = {
                    statusIsError = false
                    status = if (role == MemberRole.ADMIN) successAdmin else successMember
                    reloadMembers()
                },
                onFailure = {
                    statusIsError = true
                    status = mapError(it)
                },
            )
        } finally {
            assigning = false
        }
    }

    LaunchedEffect(writableOrgId) {
        reloadMembers()
    }

    GuidedStepCard(
        title = stringResource(Res.string.firebase_team_title),
        body = stringResource(Res.string.firebase_team_body),
    ) {
        Text(
            stringResource(Res.string.firebase_team_not_domains),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (writableOrgId.isNotBlank()) {
            Text(
                stringResource(Res.string.firebase_team_working_org, writableOrgId),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
            )
        }

        Text(
            stringResource(Res.string.firebase_team_admins_heading),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        when {
            loadingMembers -> {
                CircularProgressIndicator(modifier = Modifier.size(22.dp))
            }
            membersError != null -> {
                Text(
                    membersError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            else -> {
                val admins = members.filter { MemberRole.fromStorage(it.role) == MemberRole.ADMIN }
                val others = members.filter { MemberRole.fromStorage(it.role) != MemberRole.ADMIN }
                if (admins.isEmpty()) {
                    Text(
                        stringResource(Res.string.firebase_team_admins_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    admins.forEach { member ->
                        FirebaseMemberRow(member, admin = true)
                    }
                }
                if (others.isNotEmpty()) {
                    Text(
                        stringResource(Res.string.firebase_team_members_heading),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    others.forEach { member ->
                        FirebaseMemberRow(member, admin = false)
                    }
                }
            }
        }
        TextButton(
            onClick = {
                scope.launch { reloadMembers() }
            },
            enabled = !loadingMembers && !assigning,
        ) {
            Text(stringResource(Res.string.firebase_team_refresh))
        }

        Text(
            stringResource(Res.string.firebase_team_steps),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = memberUid,
            onValueChange = { memberUid = it },
            label = { Text(stringResource(Res.string.firebase_member_uid_label)) },
            supportingText = { Text(stringResource(Res.string.firebase_member_uid_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !assigning,
        )
        OutlinedTextField(
            value = memberEmail,
            onValueChange = { memberEmail = it },
            label = { Text(stringResource(Res.string.firebase_member_email_label)) },
            supportingText = { Text(stringResource(Res.string.firebase_member_email_hint)) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            enabled = !assigning,
        )
        Text(
            stringResource(Res.string.firebase_team_roles_heading),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(Res.string.firebase_team_role_admin_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(Res.string.firebase_team_role_member_desc),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { scope.launch { assign(MemberRole.ADMIN) } },
                enabled = memberUid.trim().isNotBlank() && !assigning,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(Res.string.firebase_role_admin)) }
            OutlinedButton(
                onClick = { scope.launch { assign(MemberRole.MEMBER) } },
                enabled = memberUid.trim().isNotBlank() && !assigning,
                modifier = Modifier.weight(1f),
            ) { Text(stringResource(Res.string.firebase_role_member)) }
        }
        if (assigning) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(
                    stringResource(Res.string.firebase_team_assign_busy),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        status?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                color = if (statusIsError) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

@Composable
private fun FirebaseMemberRow(member: FirebaseTeamMemberListing, admin: Boolean) {
    val title = member.email?.takeIf { it.isNotBlank() }
        ?: stringResource(Res.string.firebase_member_unknown_email)
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (admin) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(
            if (admin) {
                stringResource(Res.string.firebase_role_admin)
            } else {
                stringResource(Res.string.firebase_role_member)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

fun shouldShowSyncInterval(backendType: com.eventmanager.app.data.remote.BackendType): Boolean =
    backendType == com.eventmanager.app.data.remote.BackendType.SHEETS

fun shouldShowSheetsManualSyncControls(backendType: com.eventmanager.app.data.remote.BackendType): Boolean =
    backendType == com.eventmanager.app.data.remote.BackendType.SHEETS
