package com.eventmanager.app.ui.components.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.remote.FirebaseConfiguredOrg
import com.eventmanager.app.data.remote.FirebaseConfiguredOrgCodec
import com.eventmanager.app.data.remote.FirebaseOrgBootstrap
import com.eventmanager.app.data.security.canRevealFirebaseProjectSecrets
import com.eventmanager.app.data.security.firebaseOAuthCredentialsReady
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.add
import com.eventmanager.app.resources.cancel
import com.eventmanager.app.resources.close
import com.eventmanager.app.resources.firebase_configured_org_add
import com.eventmanager.app.resources.firebase_configured_org_color_cd
import com.eventmanager.app.resources.firebase_configured_org_duplicate
import com.eventmanager.app.resources.firebase_configured_orgs_body
import com.eventmanager.app.resources.firebase_configured_orgs_title
import com.eventmanager.app.resources.firebase_org_id_hint
import com.eventmanager.app.resources.firebase_org_id_invalid
import com.eventmanager.app.resources.firebase_org_id_label
import com.eventmanager.app.resources.firebase_sign_in
import com.eventmanager.app.resources.firebase_sign_out
import com.eventmanager.app.resources.firebase_status_not_signed_in
import com.eventmanager.app.resources.firebase_step_signin_body
import com.eventmanager.app.resources.remove
import com.eventmanager.app.resources.setup_firebase_project_title
import com.eventmanager.app.resources.sync_connected_as
import com.eventmanager.app.resources.sync_connection_page_subtitle
import com.eventmanager.app.resources.sync_connection_page_title
import com.eventmanager.app.resources.sync_configure
import com.eventmanager.app.resources.sync_connection_status_title
import com.eventmanager.app.resources.sync_firebase_guide_subtitle
import com.eventmanager.app.resources.sync_firebase_guide_title
import com.eventmanager.app.resources.sync_missing_org
import com.eventmanager.app.resources.sync_missing_project
import com.eventmanager.app.resources.sync_missing_sign_in
import com.eventmanager.app.resources.sync_org_remove_body
import com.eventmanager.app.resources.sync_org_remove_title
import com.eventmanager.app.resources.sync_reconfigure
import com.eventmanager.app.resources.sync_state_needs_config
import com.eventmanager.app.resources.sync_state_ready
import com.eventmanager.app.data.remote.FirebaseOrgInvitation
import com.eventmanager.app.data.remote.FirebaseOrgInvitationCodec
import com.eventmanager.app.resources.sync_org_add_message_hint
import com.eventmanager.app.resources.sync_org_add_message_label
import com.eventmanager.app.resources.sync_org_invite_join
import com.eventmanager.app.ui.components.FirebaseAdminProjectConfigCard
import com.eventmanager.app.ui.components.FirebaseOrgInvitationDialog
import com.eventmanager.app.ui.components.OrgColorSwatchRow
import com.eventmanager.app.ui.components.firebaseConfiguredOrgsReady
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import com.eventmanager.app.resources.save
import com.eventmanager.app.resources.sync_org_add_scope_device
import com.eventmanager.app.resources.sync_org_add_scope_device_body
import com.eventmanager.app.resources.sync_org_add_scope_org
import com.eventmanager.app.resources.sync_org_add_scope_org_body
import com.eventmanager.app.resources.sync_org_id_this_device_only
import com.eventmanager.app.resources.sync_org_id_value
import com.eventmanager.app.resources.sync_org_rename_cd
import com.eventmanager.app.resources.sync_org_rename_hint_local
import com.eventmanager.app.resources.sync_org_rename_hint_shared
import com.eventmanager.app.resources.sync_org_rename_label
import com.eventmanager.app.resources.sync_org_rename_title
import com.eventmanager.app.resources.sync_org_this_device_only
import org.jetbrains.compose.resources.stringResource

/**
 * Connexion page (Firebase): connection state with the right next action, the setup guide,
 * and the organisations of this device. The project settings open in [FirebaseProjectDialog].
 */
@Composable
fun SyncConnectionPage(b: SyncSettingsBindings, showTitle: Boolean = true) {
    val projectReady = b.projectId.isNotBlank() && b.applicationId.isNotBlank() && b.apiKey.isNotBlank()
    val orgReady = firebaseConfiguredOrgsReady(b.configuredOrgs)
    val signedIn = !b.authEmail.isNullOrBlank()
    val state = syncConnectionState(orgReady = orgReady, projectReady = projectReady, signedIn = signedIn)

    var showProjectDialog by remember { mutableStateOf(false) }
    var showAddOrgDialog by remember { mutableStateOf(false) }

    SettingsPage(
        showTitle = showTitle,
        title = stringResource(Res.string.sync_connection_page_title),
        subtitle = stringResource(Res.string.sync_connection_page_subtitle),
    ) {
        ConnectionStateBlock(
            b = b,
            state = state,
            projectReady = projectReady,
            onReconfigure = { showProjectDialog = true },
            onAddOrganisation = { showAddOrgDialog = true },
        )
        FirebaseGuideButton(onClick = b.onOpenFirebaseGuide)
        OrganisationsBlock(b = b, onAddOrganisation = { showAddOrgDialog = true })
    }

    if (showProjectDialog) {
        FirebaseProjectDialog(b = b, onDismiss = { showProjectDialog = false })
    }
    if (showAddOrgDialog) {
        val existing = b.configuredOrgs.filter { it.orgId.isNotBlank() }
        AddOrganisationDialog(
            existingOrgIds = existing.map { it.orgId },
            // Only an org admin can push the org list, so only an admin gets the choice.
            showScopeChoice = b.isOrgAdmin,
            onDismiss = { showAddOrgDialog = false },
            onAdd = { orgId, shareWithOrg, message ->
                b.onAddOrg(orgId, shareWithOrg, message)
                showAddOrgDialog = false
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConnectionStateBlock(
    b: SyncSettingsBindings,
    state: SyncConnectionState,
    projectReady: Boolean,
    onReconfigure: () -> Unit,
    onAddOrganisation: () -> Unit,
) {
    val title = stringResource(Res.string.sync_connection_status_title)
    SettingsBlock(
        title = title,
        help = HelpContent(title = title, body = stringResource(Res.string.firebase_step_signin_body)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(
                horizontal = SettingsKitDefaults.RowHorizontalPadding,
                vertical = 16.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (state) {
                SyncConnectionState.READY ->
                    StatusPill(stringResource(Res.string.sync_state_ready), PillTone.OK)
                SyncConnectionState.NEEDS_SIGN_IN ->
                    StatusPill(stringResource(Res.string.firebase_status_not_signed_in), PillTone.ATTENTION)
                SyncConnectionState.NEEDS_CONFIG ->
                    StatusPill(stringResource(Res.string.sync_state_needs_config), PillTone.ATTENTION)
            }
            val line = when {
                state == SyncConnectionState.READY -> stringResource(
                    Res.string.sync_connected_as,
                    connectedOrgId(b),
                    b.authEmail.orEmpty(),
                )
                state == SyncConnectionState.NEEDS_SIGN_IN -> stringResource(Res.string.sync_missing_sign_in)
                !projectReady -> stringResource(Res.string.sync_missing_project)
                else -> stringResource(Res.string.sync_missing_org)
            }
            Text(line, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            if (!b.signInFeedback.isNullOrBlank()) {
                Text(
                    b.signInFeedback,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val signedIn = !b.authEmail.isNullOrBlank()
                // Nothing typed yet: "Configurer…"; a project exists (even partly): "Reconfigurer…".
                val projectStarted = b.projectId.isNotBlank() || b.applicationId.isNotBlank() || b.apiKey.isNotBlank()
                when {
                    state == SyncConnectionState.NEEDS_SIGN_IN ->
                        Button(onClick = b.onSignIn) { Text(stringResource(Res.string.firebase_sign_in)) }
                    state == SyncConnectionState.NEEDS_CONFIG && !projectReady ->
                        Button(onClick = onReconfigure) {
                            Text(stringResource(if (projectStarted) Res.string.sync_reconfigure else Res.string.sync_configure))
                        }
                    state == SyncConnectionState.NEEDS_CONFIG ->
                        Button(onClick = onAddOrganisation) {
                            Text(stringResource(Res.string.firebase_configured_org_add))
                        }
                    else -> Unit
                }
                if (signedIn) {
                    OutlinedButton(onClick = b.onSignOut) { Text(stringResource(Res.string.firebase_sign_out)) }
                }
                if (projectReady) {
                    OutlinedButton(onClick = onReconfigure) { Text(stringResource(Res.string.sync_reconfigure)) }
                }
            }
        }
    }
}

/** The organisation shown in "Connecté à …": the one this device writes to, by its name. */
private fun connectedOrgId(b: SyncSettingsBindings): String {
    val id = b.settingsManager.resolveWritableFirebaseOrgId().ifBlank {
        b.configuredOrgs.firstOrNull { FirebaseOrgBootstrap.isValidOrgId(it.orgId) }?.orgId.orEmpty()
    }
    return b.configuredOrgs.firstOrNull { it.orgId == id }?.label ?: id
}

/** Large full-width entry to the Firebase setup assistant, as prominent as before. */
@Composable
private fun FirebaseGuideButton(onClick: () -> Unit) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
        // Same corners as the blocks around it.
        shape = SettingsKitDefaults.BlockShape,
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
    ) {
        Icon(
            Icons.AutoMirrored.Outlined.HelpOutline,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                stringResource(Res.string.sync_firebase_guide_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(Res.string.sync_firebase_guide_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun OrganisationsBlock(b: SyncSettingsBindings, onAddOrganisation: () -> Unit) {
    val title = stringResource(Res.string.firebase_configured_orgs_title)
    val orgs = b.configuredOrgs.filter { it.orgId.isNotBlank() }
    var orgToRemove by remember { mutableStateOf<String?>(null) }
    var orgToRename by remember { mutableStateOf<FirebaseConfiguredOrg?>(null) }
    var invitationToJoin by remember { mutableStateOf<FirebaseOrgInvitation?>(null) }

    SettingsBlock(
        title = title,
        help = HelpContent(title = title, body = stringResource(Res.string.firebase_configured_orgs_body)),
    ) {
        orgs.forEach { org ->
            val malformed = !FirebaseOrgBootstrap.isValidOrgId(org.orgId)
            val renamed = org.label != org.orgId
            SettingsRow(
                title = org.label,
                // The ID is only repeated when the name differs from it.
                subtitle = when {
                    malformed -> stringResource(Res.string.firebase_org_id_invalid)
                    renamed && org.localOnly -> stringResource(Res.string.sync_org_id_this_device_only, org.orgId)
                    renamed -> stringResource(Res.string.sync_org_id_value, org.orgId)
                    org.localOnly -> stringResource(Res.string.sync_org_this_device_only)
                    else -> null
                },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OrgColorButton(
                            colorArgb = org.colorArgb,
                            onColorSelected = { argb ->
                                b.onConfiguredOrgsChange(
                                    orgs.map { if (it.orgId == org.orgId) it.copy(colorArgb = argb) else it },
                                )
                            },
                        )
                        // Declined in the popup earlier: joining stays one tap away here.
                        b.declinedInvitationFor(org.orgId)?.let { invitation ->
                            TextButton(onClick = { invitationToJoin = invitation }) {
                                Text(stringResource(Res.string.sync_org_invite_join))
                            }
                        }
                        if (b.isOrgAdmin && !malformed) {
                            IconButton(onClick = { orgToRename = org }) {
                                Icon(
                                    Icons.Outlined.Edit,
                                    contentDescription = stringResource(Res.string.sync_org_rename_cd, org.label),
                                )
                            }
                        }
                        // The last valid organisation cannot be removed, as before; a malformed
                        // ID can always be removed.
                        if (malformed || orgs.size > 1) {
                            TextButton(onClick = { orgToRemove = org.orgId }) {
                                Text(stringResource(Res.string.remove))
                            }
                        }
                    }
                },
            )
            SettingsDivider()
        }
        SettingsRow(
            title = stringResource(Res.string.firebase_configured_org_add),
            onClick = onAddOrganisation,
        )
        b.orgNotice?.let { notice ->
            Text(
                notice,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(
                    horizontal = SettingsKitDefaults.RowHorizontalPadding,
                    vertical = 12.dp,
                ),
            )
        }
    }

    invitationToJoin?.let { invitation ->
        FirebaseOrgInvitationDialog(
            invitation = invitation,
            sourceLabel = b.settingsManager.getFirebaseOrgLabel(invitation.sourceOrgId),
            onJoin = { b.onJoinFromInvitation(invitation) },
            onDecline = { invitationToJoin = null },
            onJoined = {
                invitationToJoin = null
                b.onJoinedFromInvitation(invitation)
            },
        )
    }

    orgToRename?.let { org ->
        RenameOrganisationDialog(
            org = org,
            onDismiss = { orgToRename = null },
            onRename = { name ->
                b.onRenameOrg(org.orgId, name)
                orgToRename = null
            },
        )
    }

    orgToRemove?.let { orgId ->
        AlertDialog(
            onDismissRequest = { orgToRemove = null },
            title = { Text(stringResource(Res.string.sync_org_remove_title, orgId)) },
            text = { Text(stringResource(Res.string.sync_org_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    b.onConfiguredOrgsChange(orgs.filter { it.orgId != orgId })
                    orgToRemove = null
                }) { Text(stringResource(Res.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { orgToRemove = null }) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
}

/** The organisation colour; tapping it opens the same palette as before. */
@Composable
private fun OrgColorButton(colorArgb: Long, onColorSelected: (Long) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val description = stringResource(Res.string.firebase_configured_org_color_cd)
    Box {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .clickable(role = Role.Button) { open = true }
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color(colorArgb)),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                OrgColorSwatchRow(
                    selectedColorArgb = colorArgb,
                    onColorSelected = {
                        onColorSelected(it)
                        open = false
                    },
                    enabled = true,
                )
            }
        }
    }
}

/**
 * Changes the name an org is shown with. The org ID, which is also its Firestore path, stays.
 * An empty name goes back to showing the ID.
 */
@Composable
private fun RenameOrganisationDialog(
    org: FirebaseConfiguredOrg,
    onDismiss: () -> Unit,
    onRename: (String) -> Unit,
) {
    var value by remember(org.orgId) { mutableStateOf(org.label) }
    val submit = { onRename(value) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.sync_org_rename_title)) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it.take(FirebaseConfiguredOrgCodec.DISPLAY_NAME_MAX_LENGTH) },
                label = { Text(stringResource(Res.string.sync_org_rename_label)) },
                supportingText = {
                    Text(
                        stringResource(
                            if (org.localOnly) {
                                Res.string.sync_org_rename_hint_local
                            } else {
                                Res.string.sync_org_rename_hint_shared
                            },
                            org.orgId,
                        ),
                    )
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { submit() }) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

/**
 * Small window to add an organisation, with the checks of the former inline list: the ID is
 * sanitised as typed, must be well formed and must not already be in the list.
 *
 * With [showScopeChoice] (org admins), the admin picks whether the org stays on this device or
 * goes into the org list every device of the current org receives. Without it, it stays here.
 */
@Composable
fun AddOrganisationDialog(
    existingOrgIds: List<String>,
    onDismiss: () -> Unit,
    onAdd: (orgId: String, shareWithOrg: Boolean, message: String) -> Unit,
    showScopeChoice: Boolean = false,
) {
    var value by remember { mutableStateOf("") }
    var shareWithOrg by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    val trimmed = value.trim()
    val malformed = trimmed.isNotBlank() && !FirebaseOrgBootstrap.isValidOrgId(trimmed)
    val duplicate = trimmed.isNotBlank() && existingOrgIds.any { it.trim() == trimmed }
    val canAdd = trimmed.isNotBlank() && !malformed && !duplicate
    val share = showScopeChoice && shareWithOrg
    val submit = { if (canAdd) onAdd(trimmed, share, if (share) message else "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.firebase_configured_org_add)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = value,
                    // Spaces and accents cannot appear in a Firestore path segment.
                    onValueChange = { value = FirebaseOrgBootstrap.sanitizeOrgId(it) },
                    label = { Text(stringResource(Res.string.firebase_org_id_label)) },
                    supportingText = {
                        when {
                            malformed -> Text(stringResource(Res.string.firebase_org_id_invalid))
                            duplicate -> Text(stringResource(Res.string.firebase_configured_org_duplicate))
                            else -> Text(stringResource(Res.string.firebase_org_id_hint))
                        }
                    },
                    isError = malformed || duplicate,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (showScopeChoice) {
                    Column(modifier = Modifier.selectableGroup().padding(top = 8.dp)) {
                        ScopeOption(
                            selected = !shareWithOrg,
                            title = stringResource(Res.string.sync_org_add_scope_device),
                            body = stringResource(Res.string.sync_org_add_scope_device_body),
                            onSelect = { shareWithOrg = false },
                        )
                        ScopeOption(
                            selected = shareWithOrg,
                            title = stringResource(Res.string.sync_org_add_scope_org),
                            body = stringResource(Res.string.sync_org_add_scope_org_body),
                            onSelect = { shareWithOrg = true },
                        )
                    }
                    if (shareWithOrg) {
                        // Shown to the other devices with the invitation to join.
                        OutlinedTextField(
                            value = message,
                            onValueChange = { message = it.take(FirebaseOrgInvitationCodec.MESSAGE_MAX_LENGTH) },
                            label = { Text(stringResource(Res.string.sync_org_add_message_label)) },
                            supportingText = { Text(stringResource(Res.string.sync_org_add_message_hint)) },
                            minLines = 3,
                            maxLines = 6,
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { submit() }, enabled = canAdd) { Text(stringResource(Res.string.add)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
        },
    )
}

/**
 * "Projet Firebase" window opened by "Reconfigurer…": the existing project card, unchanged,
 * with the same secret masking rule as before.
 */
@Composable
fun FirebaseProjectDialog(b: SyncSettingsBindings, onDismiss: () -> Unit) {
    val projectReady = b.projectId.isNotBlank() && b.applicationId.isNotBlank() && b.apiKey.isNotBlank()
    val allowProjectSecrets = canRevealFirebaseProjectSecrets(
        isFirebaseOrgAdmin = b.isOrgAdmin,
        projectConfigured = projectReady,
        oauthCredentialsReady = firebaseOAuthCredentialsReady(b.webClientId, b.webClientSecret),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.setup_firebase_project_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                FirebaseAdminProjectConfigCard(
                    settingsManager = b.settingsManager,
                    projectId = b.projectId,
                    applicationId = b.applicationId,
                    apiKey = b.apiKey,
                    webClientId = b.webClientId,
                    webClientSecret = b.webClientSecret,
                    onProjectIdChange = b.onProjectIdChange,
                    onApplicationIdChange = b.onApplicationIdChange,
                    onApiKeyChange = b.onApiKeyChange,
                    onWebClientIdChange = b.onWebClientIdChange,
                    onWebClientSecretChange = b.onWebClientSecretChange,
                    allowProjectSecrets = allowProjectSecrets,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) }
        },
    )
}

@Composable
private fun ScopeOption(selected: Boolean, title: String, body: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onSelect)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
