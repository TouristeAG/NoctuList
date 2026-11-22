package com.eventmanager.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.eventmanager.app.data.remote.FirebaseConfiguredOrg
import com.eventmanager.app.data.sync.BiometricAdminOrgEnrollment
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.biometric_enrollment_org_select_continue
import com.eventmanager.app.resources.biometric_enrollment_org_select_description
import com.eventmanager.app.resources.biometric_enrollment_org_select_title
import com.eventmanager.app.resources.biometric_enrollment_org_verify_step
import com.eventmanager.app.resources.biometric_warning_cancel
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import org.jetbrains.compose.resources.stringResource

private enum class BiometricOrgWizardStep {
    SelectOrgs,
    VerifyOrg,
}

@Composable
fun BiometricOrgEnrollmentWizard(
    platformContext: PlatformContext,
    viewModel: EventManagerViewModel,
    configuredOrgs: List<FirebaseConfiguredOrg>,
    onCompleted: (List<BiometricAdminOrgEnrollment>) -> Unit,
    onDismiss: () -> Unit,
) {
    var step by remember { mutableStateOf(BiometricOrgWizardStep.SelectOrgs) }
    val selectedOrgIds = remember { mutableStateListOf<String>() }
    var verifyIndex by remember { mutableStateOf(0) }
    val completedEnrollments = remember { mutableStateListOf<BiometricAdminOrgEnrollment>() }

    val selectedOrgs = remember(selectedOrgIds.toList(), configuredOrgs) {
        configuredOrgs.filter { it.orgId in selectedOrgIds }
    }
    val currentOrg = selectedOrgs.getOrNull(verifyIndex)

    Dialog(
        onDismissRequest = onDismiss,
        properties = phoneFractionDialogProperties(),
    ) {
        DialogFractionSizer(profile = FractionalDialogProfile.Card) { maxDialogWidth, maxDialogHeight ->
            when (step) {
                BiometricOrgWizardStep.SelectOrgs -> {
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
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Icon(
                                Icons.Default.Fingerprint,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(Res.string.biometric_enrollment_org_select_title),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = stringResource(Res.string.biometric_enrollment_org_select_description),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                            HorizontalDivider()
                            configuredOrgs.forEach { org ->
                                OrgSelectionRow(
                                    org = org,
                                    checked = org.orgId in selectedOrgIds,
                                    onToggle = { checked ->
                                        if (checked) {
                                            if (org.orgId !in selectedOrgIds) selectedOrgIds.add(org.orgId)
                                        } else {
                                            selectedOrgIds.remove(org.orgId)
                                        }
                                    },
                                )
                            }
                            Button(
                                onClick = {
                                    verifyIndex = 0
                                    completedEnrollments.clear()
                                    step = BiometricOrgWizardStep.VerifyOrg
                                },
                                enabled = selectedOrgIds.isNotEmpty(),
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(Res.string.biometric_enrollment_org_select_continue))
                            }
                            TextButton(onClick = onDismiss) {
                                Text(stringResource(Res.string.biometric_warning_cancel))
                            }
                        }
                    }
                }
                BiometricOrgWizardStep.VerifyOrg -> {
                    if (currentOrg == null) {
                        onCompleted(completedEnrollments.toList())
                        return@DialogFractionSizer
                    }
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
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .clip(CircleShape)
                                        .background(Color(currentOrg.colorArgb)),
                                )
                                Text(
                                    text = stringResource(
                                        Res.string.biometric_enrollment_org_verify_step,
                                        currentOrg.label,
                                        verifyIndex + 1,
                                        selectedOrgs.size,
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    textAlign = TextAlign.Center,
                                )
                            }
                            key(currentOrg.orgId) {
                                BiometricAdminVerificationDialog(
                                    platformContext = platformContext,
                                    viewModel = viewModel,
                                    targetOrgId = currentOrg.orgId,
                                    embedded = true,
                                    onVerified = { match ->
                                        completedEnrollments.add(
                                            BiometricAdminOrgEnrollment(
                                                orgId = currentOrg.orgId,
                                                link = match.toBiometricAdminProfileLink(),
                                            )
                                        )
                                        if (verifyIndex + 1 >= selectedOrgs.size) {
                                            onCompleted(completedEnrollments.toList())
                                        } else {
                                            verifyIndex += 1
                                        }
                                    },
                                    onDismiss = onDismiss,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OrgSelectionRow(
    org: FirebaseConfiguredOrg,
    checked: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    val orgColor = Color(org.colorArgb)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = 1.dp,
                color = if (checked) orgColor.copy(alpha = 0.6f) else MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                shape = RoundedCornerShape(12.dp),
            )
            .clickable { onToggle(!checked) }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onToggle)
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(16.dp)
                .clip(CircleShape)
                .background(orgColor),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = org.label,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal,
        )
        if (checked) {
            Spacer(Modifier.weight(1f))
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = null,
                tint = orgColor,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
