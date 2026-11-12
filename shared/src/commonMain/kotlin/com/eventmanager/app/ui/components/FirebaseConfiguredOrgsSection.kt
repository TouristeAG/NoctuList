package com.eventmanager.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.remote.FirebaseConfiguredOrg
import com.eventmanager.app.data.remote.FirebaseConfiguredOrgCodec
import com.eventmanager.app.data.remote.FirebaseOrgBootstrap
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.firebase_configured_org_add
import com.eventmanager.app.resources.firebase_configured_org_color_cd
import com.eventmanager.app.resources.firebase_configured_org_duplicate
import com.eventmanager.app.resources.firebase_configured_org_migration_body
import com.eventmanager.app.resources.firebase_configured_org_migration_title
import com.eventmanager.app.resources.firebase_configured_org_remove_cd
import com.eventmanager.app.resources.firebase_configured_orgs_body
import com.eventmanager.app.resources.firebase_configured_orgs_title
import com.eventmanager.app.resources.firebase_org_id_hint
import com.eventmanager.app.resources.firebase_org_id_invalid
import com.eventmanager.app.resources.firebase_org_id_label
import com.eventmanager.app.resources.firebase_org_id_migration_hint
import org.jetbrains.compose.resources.stringResource

@Composable
fun FirebaseConfiguredOrgsSection(
    configuredOrgs: List<FirebaseConfiguredOrg>,
    onConfiguredOrgsChange: (List<FirebaseConfiguredOrg>) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    showGuidedCard: Boolean = true,
    /**
     * Sheets → Firebase migration must target exactly one org. Extra orgs are added later in Admin.
     * When false, only the first entry is shown and "Add organization" is hidden.
     */
    allowMultipleOrgs: Boolean = true,
    onOrgIdCommitted: (String) -> Unit = {},
) {
    var duplicateWarning by remember { mutableStateOf(false) }
    val displayedOrgs = if (allowMultipleOrgs) configuredOrgs else configuredOrgs.take(1).ifEmpty {
        listOf(
            FirebaseConfiguredOrg(
                orgId = "",
                colorArgb = FirebaseConfiguredOrgCodec.defaultColorForIndex(0),
            ),
        )
    }

    fun publish(updated: List<FirebaseConfiguredOrg>) {
        onConfiguredOrgsChange(if (allowMultipleOrgs) updated else updated.take(1))
    }

    fun updateOrg(index: Int, orgId: String) {
        duplicateWarning = false
        val updated = displayedOrgs.toMutableList()
        if (index in updated.indices) {
            updated[index] = updated[index].copy(orgId = orgId)
            publish(updated)
        }
    }

    fun updateColor(index: Int, colorArgb: Long) {
        val updated = displayedOrgs.toMutableList()
        if (index in updated.indices) {
            updated[index] = updated[index].copy(colorArgb = colorArgb)
            publish(updated)
        }
    }

    fun removeOrg(index: Int) {
        if (!allowMultipleOrgs || displayedOrgs.size <= 1) return
        publish(displayedOrgs.filterIndexed { i, _ -> i != index })
    }

    fun addOrg() {
        if (!allowMultipleOrgs) return
        val usedColors = displayedOrgs.map { it.colorArgb }
        val color = FirebaseConfiguredOrgCodec.nextAvailableColor(usedColors)
        publish(displayedOrgs + FirebaseConfiguredOrg(orgId = "", colorArgb = color))
    }

    val content: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            displayedOrgs.forEachIndexed { index, entry ->
                val trimmedIds = displayedOrgs.map { it.orgId.trim() }
                val isDuplicate = allowMultipleOrgs &&
                    entry.orgId.trim().isNotBlank() &&
                    trimmedIds.count { it == entry.orgId.trim() } > 1
                val isMalformed = entry.orgId.trim().isNotBlank() &&
                    !FirebaseOrgBootstrap.isValidOrgId(entry.orgId)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = entry.orgId,
                            onValueChange = {
                                duplicateWarning = false
                                // Spaces and accents cannot appear in a Firestore path segment.
                                updateOrg(index, FirebaseOrgBootstrap.sanitizeOrgId(it))
                            },
                            label = { Text(stringResource(Res.string.firebase_org_id_label)) },
                            supportingText = {
                                if (isMalformed) {
                                    Text(
                                        stringResource(Res.string.firebase_org_id_invalid),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                } else if (index == 0) {
                                    Text(
                                        stringResource(
                                            if (allowMultipleOrgs) {
                                                Res.string.firebase_org_id_hint
                                            } else {
                                                Res.string.firebase_org_id_migration_hint
                                            },
                                        ),
                                    )
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .onFocusChanged { focus ->
                                    if (!focus.isFocused && !readOnly) {
                                        val trimmed = entry.orgId.trim()
                                        if (FirebaseOrgBootstrap.isValidOrgId(trimmed)) {
                                            onOrgIdCommitted(trimmed)
                                        }
                                    }
                                },
                            singleLine = true,
                            readOnly = readOnly,
                            enabled = !readOnly,
                            isError = isDuplicate || isMalformed,
                        )
                        if (allowMultipleOrgs && !readOnly && displayedOrgs.size > 1) {
                            IconButton(onClick = { removeOrg(index) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = stringResource(Res.string.firebase_configured_org_remove_cd),
                                )
                            }
                        }
                    }
                    OrgColorSwatchRow(
                        selectedColorArgb = entry.colorArgb,
                        onColorSelected = { if (!readOnly) updateColor(index, it) },
                        enabled = !readOnly,
                    )
                    if (isDuplicate) {
                        duplicateWarning = true
                        Text(
                            stringResource(Res.string.firebase_configured_org_duplicate),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            if (allowMultipleOrgs && !readOnly) {
                OutlinedButton(
                    onClick = { addOrg() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text(stringResource(Res.string.firebase_configured_org_add))
                }
            }
        }
    }

    if (showGuidedCard) {
        GuidedStepCard(
            title = stringResource(
                if (allowMultipleOrgs) {
                    Res.string.firebase_configured_orgs_title
                } else {
                    Res.string.firebase_configured_org_migration_title
                },
            ),
            body = stringResource(
                if (allowMultipleOrgs) {
                    Res.string.firebase_configured_orgs_body
                } else {
                    Res.string.firebase_configured_org_migration_body
                },
            ),
            modifier = modifier,
        ) {
            content()
        }
    } else {
        Column(modifier = modifier) {
            content()
        }
    }
}

@Composable
internal fun OrgColorSwatchRow(
    selectedColorArgb: Long,
    onColorSelected: (Long) -> Unit,
    enabled: Boolean,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FirebaseConfiguredOrgCodec.paletteArgb.forEach { argb ->
            val color = Color(argb)
            val selected = argb == selectedColorArgb
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(color)
                    .then(
                        if (selected) {
                            Modifier.border(2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                        } else {
                            Modifier
                        },
                    )
                    .clickable(enabled = enabled) { onColorSelected(argb) },
                contentAlignment = Alignment.Center,
            ) {
                if (selected) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onSurface),
                    )
                }
            }
        }
    }
}

/**
 * A merely non-blank org ID is not enough: `ensureOrgBootstrappedIfNeeded` rejects anything
 * [FirebaseOrgBootstrap.isValidOrgId] refuses, so gating on non-blank only moves the failure to
 * the middle of a migration or a join.
 */
fun firebaseConfiguredOrgsReady(orgs: List<FirebaseConfiguredOrg>): Boolean =
    orgs.any { FirebaseOrgBootstrap.isValidOrgId(it.orgId) }
