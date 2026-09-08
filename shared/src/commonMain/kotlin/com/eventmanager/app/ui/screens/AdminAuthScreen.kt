package com.eventmanager.app.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.security.LocalAdminAccessResult
import com.eventmanager.app.data.security.isSuspiciousMissingAdminAfterSync
import com.eventmanager.app.data.security.memberRosterCount
import com.eventmanager.app.data.security.profileBelongsToAdminOrg
import com.eventmanager.app.data.security.shouldOfferFirstAdminSetupAfterSync
import com.eventmanager.app.data.security.crypto.SensitiveFieldCodec
import com.eventmanager.app.data.nfc.NfcUid
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.platform.PlatformBackHandler
import com.eventmanager.app.platform.createBiometricAuth
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.components.QRScannerDialog
import com.eventmanager.app.ui.components.ScannerMatch
import com.eventmanager.app.ui.platform.NfcUidListenerEffect
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

sealed class AuthState {
    data object Syncing : AuthState()
    data object Ready : AuthState()
    /** Members loaded but no admin — likely incomplete sync; do not offer admin creation. */
    data object IncompleteRoster : AuthState()
    data class SyncFailed(val message: String) : AuthState()
    data class AccessGranted(val name: String) : AuthState()
    data class AccessDenied(val name: String) : AuthState()
    data class NotFound(val uid: String = "") : AuthState()
    data class Error(val message: String) : AuthState()
}

private enum class SyncStepVisualState { Pending, Active, Done }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminAuthRoute(
    platformContext: PlatformContext,
    viewModel: EventManagerViewModel,
    onAuthSuccess: () -> Unit,
    onBack: () -> Unit,
) {
    val guests by viewModel.guests.collectAsState()
    val volunteers by viewModel.volunteers.collectAsState()
    val isSyncing by viewModel.isSyncing.collectAsState()
    LaunchedEffect(Unit) {
        viewModel.prepareForAdminAuthentication()
    }
    AdminAuthScreen(
        platformContext = platformContext,
        viewModel = viewModel,
        volunteers = volunteers,
        guests = guests,
        isSyncing = isSyncing,
        onAuthSuccess = onAuthSuccess,
        onBack = onBack,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdminAuthScreen(
    platformContext: PlatformContext,
    viewModel: EventManagerViewModel,
    volunteers: List<Volunteer>,
    guests: List<Guest>,
    isSyncing: Boolean,
    onAuthSuccess: () -> Unit,
    onBack: () -> Unit
) {
    val settingsManager = remember(platformContext) { settingsManagerFor(platformContext) }
    val biometricAuth = remember(platformContext) { createBiometricAuth(platformContext) }
    val scope = rememberCoroutineScope()
    val adminGateSyncSucceeded by viewModel.adminGateSyncSucceeded.collectAsState()
    val syncError by viewModel.syncError.collectAsState()
    val venues by viewModel.venues.collectAsState()
    val hasRoster = volunteers.isNotEmpty() || guests.isNotEmpty()
    val rosterMemberCount = memberRosterCount(guests, volunteers)

    var authState by remember {
        mutableStateOf(
            if (hasRoster || adminGateSyncSucceeded == true) AuthState.Ready else AuthState.Syncing
        )
    }
    var showQRScanner by remember { mutableStateOf(false) }
    var showNoAdminRecovery by remember { mutableStateOf(false) }

    val hasLocalAdmin = remember(guests, volunteers, viewModel) {
        viewModel.institutionHasLocalAdminForActiveOrg(guests, volunteers)
    }

    val suspiciousMissingAdmin = adminGateSyncSucceeded == true &&
        isSuspiciousMissingAdminAfterSync(true, hasLocalAdmin, rosterMemberCount)
    val canOfferEmptyInstitutionRecovery = adminGateSyncSucceeded == true &&
        shouldOfferFirstAdminSetupAfterSync(true, hasLocalAdmin, rosterMemberCount)

    val activeOrgId = remember(viewModel) { viewModel.resolveAdminAuthTargetOrgId() }
    val strictMultiOrg = remember(viewModel) { viewModel.isFirebaseStrictMultiOrgMode() }

    val permanentGuests = remember(guests) { guests.filter { !it.isVolunteerBenefit && !it.isTemporaryGuest } }
    val scopedVolunteers = remember(volunteers, activeOrgId, strictMultiOrg, viewModel) {
        if (!viewModel.shouldScopeAdminByOrg()) volunteers
        else volunteers.filter {
            profileBelongsToAdminOrg(it.firebaseOrgId, activeOrgId, strictMultiOrg)
        }
    }
    val scopedPermanentGuests = remember(permanentGuests, activeOrgId, strictMultiOrg, viewModel) {
        if (!viewModel.shouldScopeAdminByOrg()) permanentGuests
        else permanentGuests.filter {
            profileBelongsToAdminOrg(it.firebaseOrgId, activeOrgId, strictMultiOrg)
        }
    }

    fun applyAccessResult(result: LocalAdminAccessResult) {
        authState = when (result) {
            is LocalAdminAccessResult.Granted -> AuthState.AccessGranted(result.displayName)
            is LocalAdminAccessResult.Denied -> AuthState.AccessDenied(result.displayName)
            LocalAdminAccessResult.NotFound -> AuthState.NotFound()
        }
    }

    fun applyVerifiedAdminFromCandidates(candidates: List<ScannerMatch>) {
        scope.launch {
            val result = viewModel.verifyLocalAdminAccess(candidates, activeOrgId)
            withContext(Dispatchers.Main) { applyAccessResult(result) }
        }
    }

    fun resolveUidMatch(rawUid: String) {
        val uid = NfcUid.normalize(rawUid)
        if (uid.isBlank()) return
        val allMatches = scopedVolunteers
            .filter { SensitiveFieldCodec.matchesNfcUid(it, uid) }
            .map { ScannerMatch.VolunteerMatch(it) } +
            scopedPermanentGuests
                .filter { SensitiveFieldCodec.matchesNfcUid(it, uid) }
                .map { ScannerMatch.GuestMatch(it) }
        applyVerifiedAdminFromCandidates(allMatches)
    }

    val gateReady = authState is AuthState.Ready ||
        authState is AuthState.IncompleteRoster ||
        authState is AuthState.AccessDenied ||
        authState is AuthState.NotFound ||
        authState is AuthState.Error
    val canScanNfc = gateReady && hasLocalAdmin && !showNoAdminRecovery

    NfcUidListenerEffect(
        platformContext = platformContext,
        enabled = canScanNfc,
        onUidRead = ::resolveUidMatch
    )

    LaunchedEffect(isSyncing, adminGateSyncSucceeded, hasLocalAdmin, rosterMemberCount) {
        authState = when {
            authState is AuthState.AccessGranted ||
                authState is AuthState.AccessDenied ||
                authState is AuthState.NotFound ||
                authState is AuthState.Error -> authState
            hasLocalAdmin -> AuthState.Ready
            adminGateSyncSucceeded == false -> AuthState.SyncFailed(
                syncError ?: "Sync failed"
            )
            adminGateSyncSucceeded == null || isSyncing -> AuthState.Syncing
            suspiciousMissingAdmin -> AuthState.IncompleteRoster
            canOfferEmptyInstitutionRecovery -> AuthState.Ready
            else -> AuthState.Syncing
        }
    }

    LaunchedEffect(authState) {
        if (authState is AuthState.AccessGranted) {
            delay(800)
            onAuthSuccess()
        }
    }

    val showBiometric = remember(settingsManager, activeOrgId) {
        settingsManager.isBiometricAdminLoginEnabledForOrg(activeOrgId) && biometricAuth.isAvailable
    }
    val biometricTitle = stringResource(Res.string.admin_auth_biometric_prompt_title)
    val biometricSubtitle = stringResource(Res.string.admin_auth_biometric_prompt_subtitle)

    PlatformBackHandler {
        if (showQRScanner) {
            showQRScanner = false
        } else {
            onBack()
        }
    }

    Box(Modifier.fillMaxSize()) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (authState) {
                            is AuthState.Syncing, is AuthState.SyncFailed ->
                                stringResource(Res.string.admin_auth_syncing_title)
                            else -> stringResource(Res.string.admin_auth_title)
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(Res.string.setup_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.weight(1f))

            AnimatedContent(
                targetState = authState,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "adminAuthState",
                modifier = Modifier.fillMaxWidth()
            ) { state ->
                when (state) {
                    AuthState.Syncing -> AdminAuthSyncingPanel(modifier = Modifier.fillMaxWidth())
                    AuthState.IncompleteRoster -> AdminAuthIncompleteRosterPanel(
                        modifier = Modifier.fillMaxWidth(),
                        onRetry = { viewModel.retryAdminGateSync() },
                    )
                    AuthState.Ready -> if (hasLocalAdmin) {
                        AdminAuthReadyPanel(modifier = Modifier.fillMaxWidth())
                    } else if (canOfferEmptyInstitutionRecovery) {
                        AdminAuthNoAdminPanel(
                            modifier = Modifier.fillMaxWidth(),
                            onCreateAdmin = { showNoAdminRecovery = true },
                        )
                    } else {
                        AdminAuthSyncingPanel(modifier = Modifier.fillMaxWidth())
                    }
                    is AuthState.SyncFailed -> AdminAuthResultPanel(
                        icon = Icons.Default.CloudOff,
                        iconTint = MaterialTheme.colorScheme.error,
                        message = state.message,
                        messageColor = MaterialTheme.colorScheme.error,
                        onRetry = { viewModel.retryAdminGateSync() },
                    )
                    is AuthState.AccessGranted -> AdminAuthResultPanel(
                        icon = Icons.Default.CheckCircle,
                        iconTint = MaterialTheme.colorScheme.primary,
                        message = if (state.name.isNotBlank()) {
                            stringResource(Res.string.admin_auth_granted, state.name)
                        } else {
                            stringResource(Res.string.admin_auth_granted, stringResource(Res.string.admin_auth_title))
                        },
                        messageColor = MaterialTheme.colorScheme.primary,
                        messageStyle = MaterialTheme.typography.titleMedium,
                        messageFontWeight = FontWeight.Bold
                    )
                    is AuthState.AccessDenied -> AdminAuthResultPanel(
                        icon = Icons.Default.Error,
                        iconTint = MaterialTheme.colorScheme.error,
                        message = stringResource(Res.string.admin_auth_denied, state.name),
                        messageColor = MaterialTheme.colorScheme.error,
                        onRetry = { authState = AuthState.Ready }
                    )
                    is AuthState.NotFound -> AdminAuthResultPanel(
                        icon = Icons.Default.Error,
                        iconTint = MaterialTheme.colorScheme.error,
                        message = stringResource(Res.string.admin_auth_not_found),
                        messageColor = MaterialTheme.colorScheme.error,
                        onRetry = { authState = AuthState.Ready }
                    )
                    is AuthState.Error -> AdminAuthResultPanel(
                        icon = Icons.Default.Error,
                        iconTint = MaterialTheme.colorScheme.error,
                        message = state.message,
                        messageColor = MaterialTheme.colorScheme.error,
                        onRetry = { authState = AuthState.Ready }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (gateReady && hasLocalAdmin && authState !is AuthState.AccessGranted) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)
                ) {
                    if (showBiometric) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    val link = settingsManager.getBiometricEnrollmentForOrg(activeOrgId)
                                    if (link == null) {
                                        authState = AuthState.NotFound()
                                        return@launch
                                    }
                                    val ok = biometricAuth.authenticate(biometricTitle, biometricSubtitle)
                                    if (!ok) return@launch
                                    val fresh = viewModel.resolveFreshBiometricAdminLink(link)
                                    if (fresh == null) {
                                        authState = AuthState.NotFound()
                                        return@launch
                                    }
                                    val result = viewModel.verifyLocalAdminAccess(fresh, activeOrgId)
                                    applyAccessResult(result)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(14.dp),
                            enabled = canScanNfc
                        ) {
                            Icon(Icons.Default.Fingerprint, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(stringResource(Res.string.admin_auth_use_fingerprint), fontWeight = FontWeight.SemiBold)
                                Text(stringResource(Res.string.admin_auth_fingerprint_description), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    OutlinedButton(
                        onClick = { showQRScanner = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                        enabled = canScanNfc
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.admin_auth_scan_qr), fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }

    if (showNoAdminRecovery) {
        NoAdminRecoveryDialog(
            platformContext = platformContext,
            venues = venues,
            onCreateAdminGuest = { guest, cb ->
                viewModel.createAdminGuest(guest, forceRecovery = true, onResult = cb)
            },
            onCreateAdminVolunteer = { volunteer, cb ->
                viewModel.createAdminVolunteer(volunteer, forceRecovery = true, onResult = cb)
            },
            onUpdateAdminGuest = { guest, cb ->
                viewModel.updateGuest(guest)
                cb(true, guest, null)
            },
            onUpdateAdminVolunteer = { volunteer, cb ->
                viewModel.updateVolunteer(volunteer)
                cb(true, volunteer, null)
            },
            onAssignNfcUid = { adminType, entityId, uid ->
                viewModel.assignNfcUidToAdmin(
                    isGuest = adminType == AdminType.GUEST,
                    entityId = entityId,
                    uid = uid,
                )
            },
            onComplete = { showNoAdminRecovery = false },
            onDismiss = { showNoAdminRecovery = false },
        )
    }

    if (showQRScanner) {
        QRScannerDialog(
            platformContext = platformContext,
            onDismiss = { showQRScanner = false },
            onMatchFound = { match ->
                showQRScanner = false
                applyVerifiedAdminFromCandidates(listOf(match))
            },
            volunteers = volunteers,
            guests = guests
        )
    }
    }
}

@Composable
fun AdminStartupSyncBanner(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(22.dp),
                strokeWidth = 2.5.dp,
                color = scheme.primary
            )
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(Res.string.admin_auth_syncing_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(Res.string.admin_precheck_sync_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun AdminAuthSyncingPanel(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    var activeStepIndex by remember { mutableIntStateOf(0) }

    val stepLabels = listOf(
        stringResource(Res.string.admin_auth_sync_step_sheet),
        stringResource(Res.string.admin_auth_sync_step_download),
        stringResource(Res.string.admin_auth_sync_step_verify)
    )
    val stepIcons = listOf(
        Icons.Default.TableChart,
        Icons.Default.CloudDownload,
        Icons.Default.ManageAccounts
    )

    LaunchedEffect(Unit) {
        activeStepIndex = 0
        while (true) {
            delay(2400)
            activeStepIndex = (activeStepIndex + 1) % stepLabels.size
        }
    }

    val pulseTransition = rememberInfiniteTransition(label = "adminSyncPulse")
    val pulseScale by pulseTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "adminSyncPulseScale"
    )

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Surface(
                    modifier = Modifier.size(88.dp).scale(pulseScale),
                    shape = CircleShape,
                    color = scheme.primaryContainer.copy(alpha = 0.55f)
                ) {}
                Surface(
                    modifier = Modifier.size(68.dp),
                    shape = CircleShape,
                    color = scheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Lock,
                            contentDescription = null,
                            modifier = Modifier.size(34.dp),
                            tint = scheme.onPrimaryContainer
                        )
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(Res.string.admin_auth_syncing_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(Res.string.admin_auth_syncing_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }

            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = scheme.primary,
                trackColor = scheme.surfaceVariant
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                stepLabels.forEachIndexed { index, label ->
                    val visualState = when {
                        index < activeStepIndex -> SyncStepVisualState.Done
                        index == activeStepIndex -> SyncStepVisualState.Active
                        else -> SyncStepVisualState.Pending
                    }
                    AdminAuthSyncStepRow(
                        icon = stepIcons[index],
                        label = label,
                        state = visualState
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = scheme.secondaryContainer.copy(alpha = 0.65f),
                border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.35f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        Icons.Default.HourglassTop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = scheme.onSecondaryContainer
                    )
                    Text(
                        text = stringResource(Res.string.admin_auth_sync_wait_hint),
                        style = MaterialTheme.typography.labelLarge,
                        color = scheme.onSecondaryContainer,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

@Composable
private fun AdminAuthSyncStepRow(
    icon: ImageVector,
    label: String,
    state: SyncStepVisualState
) {
    val scheme = MaterialTheme.colorScheme
    val iconTint = when (state) {
        SyncStepVisualState.Active -> scheme.primary
        SyncStepVisualState.Done -> scheme.primary
        SyncStepVisualState.Pending -> scheme.onSurfaceVariant.copy(alpha = 0.55f)
    }
    val textColor = when (state) {
        SyncStepVisualState.Active -> scheme.onSurface
        SyncStepVisualState.Done -> scheme.onSurfaceVariant
        SyncStepVisualState.Pending -> scheme.onSurfaceVariant.copy(alpha = 0.7f)
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = iconTint)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            modifier = Modifier.weight(1f)
        )
        when (state) {
            SyncStepVisualState.Active -> {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = scheme.primary
                )
            }
            SyncStepVisualState.Done -> {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = scheme.primary
                )
            }
            SyncStepVisualState.Pending -> {
                Spacer(Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AdminAuthIncompleteRosterPanel(
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Default.CloudSync,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = scheme.primary,
            )
            Text(
                text = stringResource(Res.string.admin_auth_incomplete_roster_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(Res.string.admin_auth_incomplete_roster_message),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = scheme.onSurfaceVariant,
            )
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.admin_precheck_sync_retry))
            }
        }
    }
}

@Composable
private fun AdminAuthNoAdminPanel(
    modifier: Modifier = Modifier,
    onCreateAdmin: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.errorContainer.copy(alpha = 0.35f)),
        border = BorderStroke(1.dp, scheme.error.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                Icons.Default.Shield,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = scheme.error,
            )
            Text(
                text = stringResource(Res.string.no_admin_recovery_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(Res.string.no_admin_recovery_panel_hint),
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = scheme.onSurfaceVariant,
            )
            Button(onClick = onCreateAdmin, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.no_admin_recovery_create_admin))
            }
        }
    }
}

@Composable
private fun AdminAuthReadyPanel(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.45f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    color = scheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Nfc, contentDescription = null, modifier = Modifier.size(28.dp), tint = scheme.onPrimaryContainer)
                    }
                }
                Surface(
                    modifier = Modifier.size(56.dp),
                    shape = CircleShape,
                    color = scheme.secondaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(28.dp), tint = scheme.onSecondaryContainer)
                    }
                }
            }

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(Res.string.admin_auth_description),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(Res.string.admin_auth_ready),
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun AdminAuthResultPanel(
    icon: ImageVector,
    iconTint: androidx.compose.ui.graphics.Color,
    message: String,
    messageColor: androidx.compose.ui.graphics.Color,
    messageStyle: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyMedium,
    messageFontWeight: FontWeight? = null,
    onRetry: (() -> Unit)? = null
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(if (onRetry == null) 56.dp else 48.dp), tint = iconTint)
        Text(
            text = message,
            style = messageStyle,
            fontWeight = messageFontWeight,
            color = messageColor,
            textAlign = TextAlign.Center
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) {
                Text(stringResource(Res.string.admin_auth_try_again))
            }
        }
    }
}
