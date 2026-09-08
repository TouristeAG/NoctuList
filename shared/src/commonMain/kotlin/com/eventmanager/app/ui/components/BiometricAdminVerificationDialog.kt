package com.eventmanager.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.eventmanager.app.data.security.LocalAdminAccessResult
import com.eventmanager.app.data.security.profileBelongsToAdminOrg
import com.eventmanager.app.data.security.crypto.SensitiveFieldCodec
import com.eventmanager.app.data.nfc.NfcUid
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.platform.NfcUidListenerEffect
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import com.eventmanager.app.data.sync.BiometricAdminProfileLink
import com.eventmanager.app.data.sync.BiometricAdminProfileType

fun ScannerMatch.toBiometricAdminProfileLink(): BiometricAdminProfileLink = when (this) {
    is ScannerMatch.VolunteerMatch -> BiometricAdminProfileLink(
        type = BiometricAdminProfileType.VOLUNTEER,
        profileId = volunteer.id
    )
    is ScannerMatch.GuestMatch -> BiometricAdminProfileLink(
        type = BiometricAdminProfileType.GUEST,
        profileId = guest.nanoId
    )
}

@Composable
fun BiometricAdminVerificationDialog(
    platformContext: PlatformContext,
    viewModel: EventManagerViewModel,
    onVerified: (ScannerMatch) -> Unit,
    onDismiss: () -> Unit,
    targetOrgId: String? = null,
    embedded: Boolean = false,
) {
    val body: @Composable () -> Unit = {
        BiometricAdminVerificationBody(
            platformContext = platformContext,
            viewModel = viewModel,
            onVerified = onVerified,
            onDismiss = onDismiss,
            targetOrgId = targetOrgId,
        )
    }
    if (embedded) {
        body()
    } else {
        Dialog(
            onDismissRequest = onDismiss,
            properties = phoneFractionDialogProperties(),
        ) {
            DialogFractionSizer(profile = FractionalDialogProfile.Card) { maxDialogWidth, maxDialogHeight ->
                Card(
                    modifier = Modifier
                        .widthIn(max = maxDialogWidth)
                        .heightIn(max = maxDialogHeight)
                        .padding(16.dp),
                    shape = RoundedCornerShape(24.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                ) {
                    body()
                }
            }
        }
    }
}

@Composable
private fun BiometricAdminVerificationBody(
    platformContext: PlatformContext,
    viewModel: EventManagerViewModel,
    onVerified: (ScannerMatch) -> Unit,
    onDismiss: () -> Unit,
    targetOrgId: String? = null,
) {
    val verifyScope = rememberCoroutineScope()
    val guests by viewModel.guests.collectAsState()
    val volunteers by viewModel.volunteers.collectAsState()
    var showQRScanner by remember { mutableStateOf(false) }
    var verificationMessage by remember { mutableStateOf<String?>(null) }
    var verifiedMatch by remember { mutableStateOf<ScannerMatch?>(null) }

    val scopedOrgId = targetOrgId?.trim().orEmpty().ifBlank { viewModel.resolveAdminAuthTargetOrgId() }
    val strictMultiOrg = viewModel.isFirebaseStrictMultiOrgMode()
    val scopeByOrg = viewModel.shouldScopeAdminByOrg() && scopedOrgId.isNotBlank()

    val notFoundMessage = stringResource(Res.string.admin_auth_not_found)
    val permanentGuests = remember(guests) { guests.filter { !it.isVolunteerBenefit && !it.isTemporaryGuest } }
    val scopedVolunteers = remember(volunteers, scopedOrgId, scopeByOrg, strictMultiOrg) {
        if (!scopeByOrg) volunteers
        else volunteers.filter { profileBelongsToAdminOrg(it.firebaseOrgId, scopedOrgId, strictMultiOrg) }
    }
    val scopedPermanentGuests = remember(permanentGuests, scopedOrgId, scopeByOrg, strictMultiOrg) {
        if (!scopeByOrg) permanentGuests
        else permanentGuests.filter { profileBelongsToAdminOrg(it.firebaseOrgId, scopedOrgId, strictMultiOrg) }
    }

    fun applyAccessResult(result: LocalAdminAccessResult) {
        when (result) {
            is LocalAdminAccessResult.Granted -> verifiedMatch = result.match
            is LocalAdminAccessResult.Denied -> {
                verifyScope.launch {
                    verificationMessage = getString(Res.string.admin_auth_denied, result.displayName)
                }
            }
            LocalAdminAccessResult.NotFound -> verificationMessage = notFoundMessage
        }
    }

    fun applyVerifiedAdminFromCandidates(candidates: List<ScannerMatch>) {
        verifyScope.launch {
            val result = viewModel.verifyLocalAdminAccess(candidates, scopedOrgId)
            withContext(Dispatchers.Main) { applyAccessResult(result) }
        }
    }

    fun resolveUidMatch(rawUid: String) {
        val uid = NfcUid.normalize(rawUid)
        if (uid.isBlank()) return
        val allMatches: List<ScannerMatch> =
            scopedVolunteers.filter { SensitiveFieldCodec.matchesNfcUid(it, uid) }
                .map { ScannerMatch.VolunteerMatch(it) } +
                scopedPermanentGuests.filter { SensitiveFieldCodec.matchesNfcUid(it, uid) }
                    .map { ScannerMatch.GuestMatch(it) }
        applyVerifiedAdminFromCandidates(allMatches)
    }

    LaunchedEffect(verifiedMatch) {
        verifiedMatch?.let { match ->
            delay(300)
            onVerified(match)
            verifiedMatch = null
        }
    }

    NfcUidListenerEffect(
        platformContext = platformContext,
        enabled = true,
        onUidRead = ::resolveUidMatch
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
                Icon(
                    Icons.Default.Fingerprint,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                Text(
                    text = stringResource(Res.string.biometric_enrollment_verify_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )

                Text(
                    text = stringResource(Res.string.biometric_enrollment_verify_description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                HorizontalDivider()

                Icon(
                    Icons.Default.Nfc,
                    contentDescription = null,
                    modifier = Modifier.size(40.dp),
                    tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.7f)
                )

                Text(
                    text = stringResource(Res.string.admin_auth_ready),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                if (verificationMessage != null) {
                    Text(
                        text = verificationMessage!!,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }

                OutlinedButton(
                    onClick = { showQRScanner = true },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.admin_auth_scan_qr), fontWeight = FontWeight.SemiBold)
                }

                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.biometric_warning_cancel))
                }
    }

    if (showQRScanner) {
        QRScannerDialog(
            platformContext = platformContext,
            onDismiss = { showQRScanner = false },
            onMatchFound = { match ->
                showQRScanner = false
                applyVerifiedAdminFromCandidates(listOf(match))
            },
            volunteers = scopedVolunteers,
            guests = scopedPermanentGuests
        )
    }
}
