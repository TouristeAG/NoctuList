package com.eventmanager.app.ui.components.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.remote.FirebaseEmailDomainPolicy
import com.eventmanager.app.data.remote.FirebaseTeamActions
import com.eventmanager.app.data.remote.FirebaseTeamMemberListing
import com.eventmanager.app.data.remote.MemberRole
import com.eventmanager.app.data.remote.removeMemberByEmail
import com.eventmanager.app.data.remote.setRoleByEmail
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.components.firebaseJoinConfigCode
import com.eventmanager.app.ui.components.orgHasInvitationCodeOnServer
import com.eventmanager.app.ui.components.rotateFirebaseInvitationCode
import com.eventmanager.app.utils.QRCodeUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import org.jetbrains.compose.resources.stringResource

/**
 * Équipe page (Firebase): add a person with the join QR, choose who may join, and manage the
 * members' roles. Role changes and removal are offered only by [memberActionsAllowed]; the
 * Firestore rules stay the real barrier. Network actions run in [SyncSettingsBindings.scope].
 */
@Composable
fun SyncTeamPage(b: SyncSettingsBindings, showTitle: Boolean = true) {
    val signedIn = !b.authEmail.isNullOrBlank()
    var showAddPerson by remember { mutableStateOf(false) }
    val invitation = remember(b.settingsManager) { InvitationCodeState(b.settingsManager.getFirebaseBootstrapCode()) }

    SettingsPage(
        showTitle = showTitle,
        title = stringResource(Res.string.sync_team_page_title),
        subtitle = stringResource(Res.string.sync_team_page_subtitle),
        readOnlyNotice = if (!b.isOrgAdmin && signedIn) stringResource(Res.string.sync_read_only_notice) else null,
    ) {
        AddPersonBlock(b = b, onOpen = { showAddPerson = true })
        WhoCanJoinBlock(b = b)
        MembersBlock(b = b)
    }

    if (showAddPerson && b.isOrgAdmin) {
        AddPersonDialog(b = b, invitation = invitation, onDismiss = { showAddPerson = false })
    }
}

// region Ajouter une personne

@Composable
private fun AddPersonBlock(b: SyncSettingsBindings, onOpen: () -> Unit) {
    val title = stringResource(Res.string.sync_team_add_person)
    SettingsBlock(
        title = title,
        help = HelpContent(title = title, body = stringResource(Res.string.firebase_join_qr_body)),
    ) {
        if (b.isOrgAdmin) {
            SettingsRow(title = stringResource(Res.string.sync_team_show_qr), onClick = onOpen)
        } else {
            // Only org admins may see the QR: it carries the project secrets and the invitation code.
            SettingsRow(
                title = stringResource(Res.string.sync_team_show_qr),
                subtitle = stringResource(Res.string.firebase_join_qr_restricted),
                enabled = false,
            )
        }
    }
}

/** Invitation code of this device, kept above the dialog so a creation in flight is not lost. */
@Stable
private class InvitationCodeState(initialCode: String) {
    var code by mutableStateOf(initialCode)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)

    /** True once the server said the org already has a code that this device does not hold. */
    var orgHasCode by mutableStateOf(false)
}

/**
 * Called when this device holds no invitation code. Creates one only if the org has none on the
 * server. If the org already has a code, or the server cannot be read, nothing is created: a new
 * code would disable every QR already shared, so that needs the user's explicit confirmation.
 */
private fun ensureInvitation(
    b: SyncSettingsBindings,
    invitation: InvitationCodeState,
    texts: TeamErrorTexts,
    checkFailed: String,
) {
    if (invitation.busy) return
    invitation.busy = true
    invitation.error = null
    b.scope.launch {
        var checked = false
        try {
            val orgHasCode = orgHasInvitationCodeOnServer(b.platformContext, b.settingsManager)
            checked = true
            invitation.orgHasCode = orgHasCode
            if (!orgHasCode) {
                invitation.code = rotateFirebaseInvitationCode(b.platformContext, b.settingsManager)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            invitation.error = mapTeamError(
                e,
                texts,
                email = null,
                allowedDomains = emptyList(),
                unknown = if (checked) null else checkFailed,
            )
        } finally {
            invitation.busy = false
        }
    }
}

/** Creates or replaces the invitation code in the screen scope (older QR codes stop working). */
private fun rotateInvitation(b: SyncSettingsBindings, invitation: InvitationCodeState, texts: TeamErrorTexts) {
    if (invitation.busy) return
    invitation.busy = true
    invitation.error = null
    b.scope.launch {
        runCatching { rotateFirebaseInvitationCode(b.platformContext, b.settingsManager) }
            .onSuccess {
                invitation.code = it
                invitation.orgHasCode = false
            }
            .onFailure { invitation.error = mapTeamError(it, texts, email = null, allowedDomains = emptyList()) }
        invitation.busy = false
    }
}

/**
 * The join QR (240 dp) with its configuration code to copy, and a menu to disable older QR codes.
 * When this device has no invitation code, it checks the org on the server first and creates a code
 * only if the org has none (org admins only); see [ensureInvitation].
 */
@Composable
private fun AddPersonDialog(b: SyncSettingsBindings, invitation: InvitationCodeState, onDismiss: () -> Unit) {
    val texts = rememberTeamErrorTexts()
    val checkFailed = stringResource(Res.string.sync_team_invite_check_failed)
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDisable by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        if (invitation.code.isBlank() && b.isOrgAdmin) ensureInvitation(b, invitation, texts, checkFailed)
    }

    val orgId = b.configuredOrgs.firstOrNull { it.orgId.isNotBlank() }?.orgId.orEmpty()
    val encoded = remember(orgId, b.projectId, b.applicationId, b.apiKey, b.webClientId, b.webClientSecret, invitation.code) {
        if (invitation.code.isBlank()) {
            null
        } else {
            firebaseJoinConfigCode(
                orgId = orgId,
                projectId = b.projectId,
                applicationId = b.applicationId,
                apiKey = b.apiKey,
                webClientId = b.webClientId,
                webClientSecret = b.webClientSecret,
                bootstrapCode = invitation.code,
            )
        }
    }
    val qrBitmap = remember(encoded) { encoded?.let { QRCodeUtils.generateQrImageBitmap(it, 512) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(Res.string.sync_team_add_person), modifier = Modifier.weight(1f))
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = stringResource(Res.string.settings_more_options_cd),
                        )
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.sync_team_disable_old_qr)) },
                            enabled = !invitation.busy && invitation.code.isNotBlank(),
                            onClick = {
                                menuOpen = false
                                confirmDisable = true
                            },
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                when {
                    invitation.busy -> Box(Modifier.size(240.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                    invitation.code.isBlank() -> if (invitation.orgHasCode) {
                        Text(
                            stringResource(Res.string.sync_team_invite_not_on_device),
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    encoded == null || qrBitmap == null -> Text(
                        stringResource(Res.string.firebase_join_qr_need_config),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    else -> {
                        Image(
                            bitmap = qrBitmap,
                            contentDescription = stringResource(Res.string.firebase_join_qr_title),
                            modifier = Modifier.size(240.dp),
                        )
                        Text(
                            stringResource(Res.string.sync_team_no_camera),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        CopyField(encoded)
                    }
                }
                invitation.error?.let { message ->
                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (invitation.code.isBlank() && !invitation.busy) {
                        TextButton(onClick = { ensureInvitation(b, invitation, texts, checkFailed) }) {
                            Text(stringResource(Res.string.sync_team_retry))
                        }
                    }
                }
                // Replacing the org's code is always an explicit, confirmed choice.
                if (invitation.code.isBlank() && !invitation.busy && (invitation.orgHasCode || invitation.error != null)) {
                    OutlinedButton(onClick = { confirmDisable = true }) {
                        Text(stringResource(Res.string.sync_team_invite_create_new))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) }
        },
    )

    if (confirmDisable) {
        AlertDialog(
            onDismissRequest = { confirmDisable = false },
            title = { Text(stringResource(Res.string.sync_team_disable_old_qr)) },
            text = { Text(stringResource(Res.string.sync_team_disable_old_qr_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDisable = false
                    rotateInvitation(b, invitation, texts)
                }) { Text(stringResource(Res.string.sync_team_disable_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisable = false }) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
}

// endregion

// region Qui peut rejoindre l'équipe ?

@Composable
private fun WhoCanJoinBlock(b: SyncSettingsBindings) {
    val title = stringResource(Res.string.sync_team_who_can_join)
    val domains = b.allowedEmailDomains
    // The second card stays selected after the user picks it, even before a domain is added.
    var pickedRestricted by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val selected = if (domains.isNotEmpty() || pickedRestricted) 1 else 0
    val invalidDomain = stringResource(Res.string.firebase_email_domains_hint)

    SettingsBlock(
        title = title,
        help = HelpContent(title = title, body = stringResource(Res.string.firebase_email_domains_body)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(SettingsKitDefaults.RowHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ChoiceCards(
                options = listOf(
                    ChoiceOption(stringResource(Res.string.sync_team_join_anyone), ""),
                    ChoiceOption(stringResource(Res.string.sync_team_join_domains), ""),
                ),
                selected = selected,
                enabled = b.isOrgAdmin,
                onSelect = { index ->
                    when {
                        index == 1 -> pickedRestricted = true
                        domains.isNotEmpty() -> confirmClear = true
                        else -> pickedRestricted = false
                    }
                },
            )
            AnimatedVisibility(visible = selected == 1) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (domains.isEmpty()) {
                        Text(
                            stringResource(Res.string.sync_team_domains_none),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    DomainChips(
                        domains = domains,
                        enabled = b.isOrgAdmin,
                        onRemove = { domain ->
                            pickedRestricted = true
                            b.onAllowedEmailDomainsChange(domains.filterNot { it == domain })
                        },
                        onAdd = { raw ->
                            val normalized = FirebaseEmailDomainPolicy.normalizeDomain(raw)
                            when {
                                normalized.isBlank() || !normalized.contains('.') -> invalidDomain
                                domains.any { it == normalized } -> null
                                else -> {
                                    b.onAllowedEmailDomainsChange(domains + normalized)
                                    null
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(Res.string.sync_team_clear_domains_title)) },
            text = { Text(stringResource(Res.string.sync_team_clear_domains_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    pickedRestricted = false
                    // Same path as removing every domain: stored, then published to the org.
                    b.onAllowedEmailDomainsChange(emptyList())
                }) { Text(stringResource(Res.string.confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
}

// endregion

// region Membres

/** Localised team errors, resolved in composition so a coroutine can map a failure later. */
private class TeamErrorTexts(
    val noOrg: String,
    val emailRequired: String,
    val permission: String,
    val notReady: String,
    val notFound: String,
    val ambiguous: String,
    val self: String,
    val domainTemplate: (String) -> String,
)

@Composable
private fun rememberTeamErrorTexts(): TeamErrorTexts {
    val noOrg = stringResource(Res.string.firebase_team_error_no_org)
    val emailRequired = stringResource(Res.string.firebase_team_error_email_required)
    val permission = stringResource(Res.string.firebase_team_error_permission)
    val notReady = stringResource(Res.string.firebase_team_error_not_ready)
    val notFound = stringResource(Res.string.sync_team_error_not_found)
    val ambiguous = stringResource(Res.string.sync_team_error_ambiguous)
    val self = stringResource(Res.string.sync_team_error_self)
    // "%1$s" placeholder kept so the e-mail can be filled in later.
    val domain = stringResource(Res.string.sync_team_error_domain, "%1\$s")
    return TeamErrorTexts(noOrg, emailRequired, permission, notReady, notFound, ambiguous, self) { email ->
        domain.replace("%1\$s", email)
    }
}

/**
 * Same mapping as the former team card, plus the by-e-mail errors. A role change refused because
 * the address is outside the allowed domains (an [IllegalArgumentException] from the domain
 * policy) gets its own message: removal still works for that person.
 */
private fun mapTeamError(
    throwable: Throwable,
    texts: TeamErrorTexts,
    email: String?,
    allowedDomains: List<String>,
    unknown: String? = null,
): String {
    val raw = throwable.message.orEmpty()
    return when {
        email != null && throwable is IllegalArgumentException &&
            !FirebaseEmailDomainPolicy.isEmailAllowed(email, allowedDomains) -> texts.domainTemplate(email)
        raw == "NOT_FOUND" -> texts.notFound
        raw == "AMBIGUOUS" -> texts.ambiguous
        raw == "SELF" -> texts.self
        raw.contains("NO_ORG") -> texts.noOrg
        raw.contains("EMAIL_REQUIRED") -> texts.emailRequired
        raw.contains("NOT_READY") || raw.contains("not initialized", ignoreCase = true) -> texts.notReady
        raw.contains("PERMISSION") || raw.contains("permission-denied", ignoreCase = true) -> texts.permission
        else -> unknown ?: raw.ifBlank { throwable::class.simpleName.orEmpty() }
    }
}

@Stable
private class TeamMembersState {
    var members by mutableStateOf<List<FirebaseTeamMemberListing>>(emptyList())
    var loading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)

    /** The load in flight; a new load cancels it so an older result never overwrites a newer one. */
    var job: Job? = null
    var generation = 0
}

/**
 * Reloads the member list in the screen scope. Any load still running is cancelled, and only the
 * latest load may write its result. Failures with no specific message show [loadError] rather
 * than raw Firestore text.
 */
private fun reloadMembers(b: SyncSettingsBindings, state: TeamMembersState, texts: TeamErrorTexts, loadError: String) {
    state.job?.cancel()
    val generation = ++state.generation
    state.loading = true
    state.error = null
    state.job = b.scope.launch {
        val result = FirebaseTeamActions.loadMembers(b.platformContext, b.settingsManager)
        if (generation != state.generation) return@launch
        result.fold(
            onSuccess = { state.members = it },
            onFailure = {
                state.members = emptyList()
                state.error = mapTeamError(it, texts, email = null, allowedDomains = emptyList(), unknown = loadError)
            },
        )
        state.loading = false
    }
}

/** Role change or removal in flight for the open [MemberDialog]. */
@Stable
private class MemberActionState {
    var email by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
}

@Composable
private fun MembersBlock(b: SyncSettingsBindings) {
    val title = stringResource(Res.string.sync_team_members_title)
    val texts = rememberTeamErrorTexts()
    val loadError = stringResource(Res.string.firebase_team_load_error)
    val writableOrgId = b.settingsManager.resolveWritableFirebaseOrgId()
    val state = remember(writableOrgId) { TeamMembersState() }
    val action = remember(writableOrgId) { MemberActionState() }

    LaunchedEffect(writableOrgId, b.authEmail) {
        reloadMembers(b, state, texts, loadError)
    }

    fun runAction(email: String, block: suspend () -> Result<Unit>) {
        if (action.busy) return
        action.busy = true
        action.error = null
        b.scope.launch {
            block().fold(
                onSuccess = {
                    action.busy = false
                    action.email = null
                    reloadMembers(b, state, texts, loadError)
                },
                onFailure = {
                    action.error = mapTeamError(it, texts, email, b.allowedEmailDomains)
                    action.busy = false
                    // The member is gone on the server: refresh so the list matches.
                    if (it.message == "NOT_FOUND") reloadMembers(b, state, texts, loadError)
                },
            )
        }
    }

    val roleHelp = stringResource(Res.string.firebase_team_role_admin_desc) + "\n\n" +
        stringResource(Res.string.firebase_team_role_member_desc)
    SettingsBlock(
        title = title,
        help = HelpContent(title = title, body = roleHelp),
        headerAction = {
            TextButton(
                onClick = { reloadMembers(b, state, texts, loadError) },
                enabled = !state.loading,
            ) { Text(stringResource(Res.string.refresh)) }
        },
    ) {
        val sorted = state.members.sortedWith(
            compareBy<FirebaseTeamMemberListing> { MemberRole.fromStorage(it.role) != MemberRole.ADMIN }
                .thenBy { it.email?.lowercase().orEmpty() },
        )
        when {
            state.loading && sorted.isEmpty() -> Box(
                modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
            state.error != null -> MembersMessage(state.error.orEmpty(), isError = true)
            sorted.isEmpty() -> MembersMessage(stringResource(Res.string.sync_team_members_empty), isError = false)
            else -> sorted.forEachIndexed { index, member ->
                if (index > 0) SettingsDivider()
                val email = member.email?.trim().orEmpty()
                val allowed = memberActionsAllowed(b.isOrgAdmin, member.email, b.authEmail)
                MemberRow(
                    member = member,
                    isSelf = email.isNotEmpty() && email.equals(b.authEmail?.trim(), ignoreCase = true),
                    onClick = if (allowed) {
                        {
                            action.error = null
                            action.email = email
                        }
                    } else {
                        null
                    },
                )
            }
        }
    }

    val openEmail = action.email
    val openMember = openEmail?.let { e -> state.members.firstOrNull { it.email?.trim().equals(e, ignoreCase = true) } }
    if (openEmail != null && openMember != null && memberActionsAllowed(b.isOrgAdmin, openEmail, b.authEmail)) {
        MemberDialog(
            email = openEmail,
            isAdmin = MemberRole.fromStorage(openMember.role) == MemberRole.ADMIN,
            outsideAllowedDomains = !FirebaseEmailDomainPolicy.isEmailAllowed(openEmail, b.allowedEmailDomains),
            outsideDomainsMessage = texts.domainTemplate(openEmail),
            busy = action.busy,
            error = action.error,
            onSetRole = { role ->
                runAction(openEmail) { FirebaseTeamActions.setRoleByEmail(b.platformContext, b.settingsManager, openEmail, role) }
            },
            onRemove = {
                runAction(openEmail) { FirebaseTeamActions.removeMemberByEmail(b.platformContext, b.settingsManager, openEmail) }
            },
            onDismiss = {
                action.email = null
                action.error = null
            },
        )
    }
}

@Composable
private fun MembersMessage(text: String, isError: Boolean) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(
            horizontal = SettingsKitDefaults.RowHorizontalPadding,
            vertical = SettingsKitDefaults.RowVerticalPadding,
        ),
    )
}

@Composable
private fun MemberRow(member: FirebaseTeamMemberListing, isSelf: Boolean, onClick: (() -> Unit)?) {
    val email = member.email?.takeIf { it.isNotBlank() }
    val isAdmin = MemberRole.fromStorage(member.role) == MemberRole.ADMIN
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = 56.dp)
            .padding(
                horizontal = SettingsKitDefaults.RowHorizontalPadding,
                vertical = SettingsKitDefaults.RowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        InitialsCircle(memberInitials(email))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                email ?: stringResource(Res.string.firebase_member_unknown_email),
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (isSelf) {
                Text(
                    stringResource(Res.string.sync_team_you),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        StatusPill(
            text = stringResource(if (isAdmin) Res.string.firebase_role_admin else Res.string.firebase_role_member),
            tone = if (isAdmin) PillTone.OK else PillTone.NEUTRAL,
        )
        if (onClick != null) {
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

@Composable
private fun InitialsCircle(initials: String) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initials,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

/** Two letters from the e-mail's local part ("jean.dupont@…" gives "JD"), or "?" when unknown. */
private fun memberInitials(email: String?): String {
    val local = email?.substringBefore('@')?.trim().orEmpty()
    if (local.isEmpty()) return "?"
    val parts = local.split('.', '_', '-', '+').filter { it.isNotEmpty() }
    val letters = if (parts.size >= 2) {
        "${parts[0].first()}${parts[1].first()}"
    } else {
        local.take(2)
    }
    return letters.uppercase()
}

/**
 * A member's role (segmented Membre / Admin) and "Retirer de l'équipe". Making someone admin and
 * removing them both ask for confirmation. When the address is outside the allowed domains the
 * role cannot change (the domain policy refuses it), and the dialog says so; removal still works.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemberDialog(
    email: String,
    isAdmin: Boolean,
    outsideAllowedDomains: Boolean,
    outsideDomainsMessage: String,
    busy: Boolean,
    error: String?,
    onSetRole: (MemberRole) -> Unit,
    onRemove: () -> Unit,
    onDismiss: () -> Unit,
) {
    var confirmAdmin by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(email, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = !isAdmin,
                        onClick = { if (isAdmin) onSetRole(MemberRole.MEMBER) },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                        enabled = !busy && !outsideAllowedDomains,
                    ) { Text(stringResource(Res.string.firebase_role_member)) }
                    SegmentedButton(
                        selected = isAdmin,
                        onClick = { if (!isAdmin) confirmAdmin = true },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                        enabled = !busy && !outsideAllowedDomains,
                    ) { Text(stringResource(Res.string.firebase_role_admin)) }
                }
                if (outsideAllowedDomains) {
                    Text(outsideDomainsMessage, style = MaterialTheme.typography.bodyMedium)
                }
                if (busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
                TextButton(
                    onClick = { confirmRemove = true },
                    enabled = !busy,
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(Res.string.sync_team_remove)) }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) }
        },
    )

    if (confirmAdmin) {
        AlertDialog(
            onDismissRequest = { confirmAdmin = false },
            title = { Text(stringResource(Res.string.sync_team_make_admin_title)) },
            text = { Text(stringResource(Res.string.sync_team_make_admin_body, email)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmAdmin = false
                    onSetRole(MemberRole.ADMIN)
                }) { Text(stringResource(Res.string.sync_team_make_admin_action)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmAdmin = false }) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text(stringResource(Res.string.sync_team_remove_title, email)) },
            text = { Text(stringResource(Res.string.sync_team_remove_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmRemove = false
                        onRemove()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(Res.string.remove)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = false }) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
}

// endregion
