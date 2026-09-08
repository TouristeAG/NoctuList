package com.eventmanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Nfc
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.security.LocalAdminAccessResult
import com.eventmanager.app.data.security.LocalAdminGrantResult
import com.eventmanager.app.data.security.LocalAdminTargetKind
import com.eventmanager.app.data.security.shouldShowLocalAdminRightsSection
import com.eventmanager.app.data.security.crypto.SensitiveFieldCodec
import com.eventmanager.app.data.nfc.NfcUid
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.platform.NfcUidListenerEffect
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

private enum class GrantLocalAdminUiStep {
    EXPLAIN,
    SCAN,
    CHECKING,
    DONE,
}

@Composable
fun LocalAdminRightsSection(
    viewModel: EventManagerViewModel,
    isAdmin: Boolean,
    displayName: String,
    kind: LocalAdminTargetKind,
    targetId: String,
    modifier: Modifier = Modifier,
    guest: Guest? = null,
    volunteer: Volunteer? = null,
    readOnly: Boolean = false,
) {
    if (!shouldShowLocalAdminRightsSection(
            backendType = viewModel.getActiveBackendType(),
            readOnly = readOnly,
            guest = guest,
            volunteer = volunteer,
        )
    ) {
        return
    }

    var showDialog by remember { mutableStateOf(false) }
    var grantMakeAdmin by remember { mutableStateOf(true) }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.AdminPanelSettings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = stringResource(Res.string.local_admin_section_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                text = stringResource(Res.string.local_admin_section_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    if (isAdmin) Icons.Default.VerifiedUser else Icons.Default.Security,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (isAdmin) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                Text(
                    text = if (isAdmin) {
                        stringResource(Res.string.local_admin_status_yes, displayName)
                    } else {
                        stringResource(Res.string.local_admin_status_no, displayName)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
            if (isAdmin) {
                OutlinedButton(
                    onClick = {
                        grantMakeAdmin = false
                        showDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(Res.string.local_admin_revoke_button))
                }
            } else {
                Button(
                    onClick = {
                        grantMakeAdmin = true
                        showDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(Res.string.local_admin_grant_button))
                }
            }
        }
    }

    if (showDialog) {
        GrantLocalAdminDialog(
            viewModel = viewModel,
            targetName = displayName,
            kind = kind,
            targetId = targetId,
            makeAdmin = grantMakeAdmin,
            onDismiss = { showDialog = false }
        )
    }
}

@Composable
private fun GrantLocalAdminDialog(
    viewModel: EventManagerViewModel,
    targetName: String,
    kind: LocalAdminTargetKind,
    targetId: String,
    makeAdmin: Boolean,
    onDismiss: () -> Unit,
) {
    val platformContext = LocalPlatformContext.current
    val scope = rememberCoroutineScope()
    val guests by viewModel.guests.collectAsState()
    val volunteers by viewModel.volunteers.collectAsState()
    var step by remember { mutableStateOf(GrantLocalAdminUiStep.EXPLAIN) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var grantorName by remember { mutableStateOf("") }
    var showQRScanner by remember { mutableStateOf(false) }

    val notFoundMessage = stringResource(Res.string.admin_auth_not_found)
    val title = if (makeAdmin) {
        stringResource(Res.string.local_admin_grant_title)
    } else {
        stringResource(Res.string.local_admin_revoke_title)
    }

    val permanentGuests = remember(guests) {
        guests.filter { !it.isVolunteerBenefit && !it.isTemporaryGuest }
    }

    fun applyGrant(grantor: ScannerMatch) {
        step = GrantLocalAdminUiStep.CHECKING
        errorMessage = null
        viewModel.setLocalAdminRights(
            kind = kind,
            targetId = targetId,
            makeAdmin = makeAdmin,
            grantorMatch = grantor,
        ) { result ->
            if (result is LocalAdminGrantResult.Success) {
                step = GrantLocalAdminUiStep.DONE
            } else {
                scope.launch {
                    errorMessage = when (result) {
                        LocalAdminGrantResult.NotFirebase ->
                            getString(Res.string.local_admin_error_not_firebase)
                        LocalAdminGrantResult.SoftLocked ->
                            getString(Res.string.local_admin_error_soft_locked)
                        LocalAdminGrantResult.TargetNotEligible ->
                            getString(Res.string.local_admin_error_not_eligible)
                        LocalAdminGrantResult.TargetNotFound ->
                            getString(Res.string.local_admin_error_target_missing)
                        LocalAdminGrantResult.GrantorNotLocalAdmin ->
                            getString(Res.string.local_admin_error_grantor_not_admin)
                        LocalAdminGrantResult.FirebaseNotSignedIn ->
                            getString(Res.string.local_admin_error_not_signed_in)
                        LocalAdminGrantResult.FirebaseNotAdmin ->
                            getString(Res.string.local_admin_error_firebase_not_admin)
                        LocalAdminGrantResult.LastLocalAdmin ->
                            getString(Res.string.local_admin_error_last_admin)
                        is LocalAdminGrantResult.Error -> result.message
                        LocalAdminGrantResult.Success -> ""
                    }
                    step = GrantLocalAdminUiStep.SCAN
                }
            }
        }
    }

    fun applyAccessResult(result: LocalAdminAccessResult) {
        when (result) {
            is LocalAdminAccessResult.Granted -> {
                grantorName = result.displayName
                applyGrant(result.match)
            }
            is LocalAdminAccessResult.Denied -> {
                scope.launch {
                    errorMessage = getString(Res.string.admin_auth_denied, result.displayName)
                    step = GrantLocalAdminUiStep.SCAN
                }
            }
            LocalAdminAccessResult.NotFound -> {
                errorMessage = notFoundMessage
                step = GrantLocalAdminUiStep.SCAN
            }
        }
    }

    fun applyVerifiedAdminFromCandidates(candidates: List<ScannerMatch>) {
        scope.launch {
            val result = viewModel.verifyLocalAdminAccess(candidates)
            withContext(Dispatchers.Main) { applyAccessResult(result) }
        }
    }

    fun resolveUidMatch(rawUid: String) {
        if (step != GrantLocalAdminUiStep.SCAN) return
        val uid = NfcUid.normalize(rawUid)
        if (uid.isBlank()) return
        val allMatches = volunteers
            .filter { SensitiveFieldCodec.matchesNfcUid(it, uid) }
            .map { ScannerMatch.VolunteerMatch(it) } +
            permanentGuests
                .filter { SensitiveFieldCodec.matchesNfcUid(it, uid) }
                .map { ScannerMatch.GuestMatch(it) }
        applyVerifiedAdminFromCandidates(allMatches)
    }

    NfcUidListenerEffect(
        platformContext = platformContext,
        enabled = step == GrantLocalAdminUiStep.SCAN,
        onUidRead = ::resolveUidMatch
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = phoneFractionDialogProperties(dismissOnClickOutside = step != GrantLocalAdminUiStep.CHECKING),
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
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        Icons.Default.AdminPanelSettings,
                        contentDescription = null,
                        modifier = Modifier.size(44.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Text(
                        text = if (makeAdmin) {
                            stringResource(Res.string.local_admin_grant_subtitle, targetName)
                        } else {
                            stringResource(Res.string.local_admin_revoke_subtitle, targetName)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    HorizontalDivider()

                    when (step) {
                        GrantLocalAdminUiStep.EXPLAIN -> {
                            GrantStepLine(
                                number = "1",
                                text = stringResource(Res.string.local_admin_step1)
                            )
                            GrantStepLine(
                                number = "2",
                                text = stringResource(Res.string.local_admin_step2)
                            )
                            Text(
                                text = stringResource(Res.string.local_admin_scan_own_card_hint),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(4.dp))
                            Button(
                                onClick = {
                                    errorMessage = null
                                    step = GrantLocalAdminUiStep.SCAN
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(Res.string.local_admin_continue_scan))
                            }
                        }
                        GrantLocalAdminUiStep.SCAN -> {
                            Icon(
                                Icons.Default.Nfc,
                                contentDescription = null,
                                modifier = Modifier.size(36.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = stringResource(Res.string.local_admin_scan_prompt),
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                            errorMessage?.let {
                                Text(
                                    text = it,
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
                        }
                        GrantLocalAdminUiStep.CHECKING -> {
                            CircularProgressIndicator()
                            Text(
                                text = if (grantorName.isNotBlank()) {
                                    stringResource(Res.string.local_admin_checking_firebase_named, grantorName)
                                } else {
                                    stringResource(Res.string.local_admin_checking_firebase)
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.Center
                            )
                        }
                        GrantLocalAdminUiStep.DONE -> {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(44.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = if (makeAdmin) {
                                    stringResource(Res.string.local_admin_grant_success, targetName)
                                } else {
                                    stringResource(Res.string.local_admin_revoke_success, targetName)
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.SemiBold,
                                textAlign = TextAlign.Center
                            )
                            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(Res.string.done))
                            }
                        }
                    }

                    if (step != GrantLocalAdminUiStep.DONE && step != GrantLocalAdminUiStep.CHECKING) {
                        TextButton(onClick = onDismiss) {
                            Text(stringResource(Res.string.cancel))
                        }
                    }
                }
            }
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
            volunteers = volunteers,
            guests = guests
        )
    }
}

@Composable
private fun GrantStepLine(number: String, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = number,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}