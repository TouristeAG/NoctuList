package com.eventmanager.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.remote.BackendType
import com.eventmanager.app.data.models.VenueAccess
import com.eventmanager.app.data.models.VenueAccessCatalog
import com.eventmanager.app.data.remote.FirebaseOrgAdminAccess
import com.eventmanager.app.data.security.canEditSyncedInstitutionSettings
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import org.jetbrains.compose.resources.stringResource

/**
 * Everything the venue editor needs to show a venue's temporary guest accesses. Null on the Sheets
 * backend, where the feature does not exist.
 */
data class VenueAccessBindings(
    val accesses: List<VenueAccess>,
    val canEdit: Boolean,
    val onAddAccess: (venueName: String, name: String) -> Unit,
    val onRemoveAccess: (accessId: String) -> Unit,
)

/**
 * Venue accesses are an institution setting: on Firebase only org admins may change them.
 * Starts false until the admin check returns, so a non-admin never sees editable controls.
 */
@Composable
fun rememberCanEditInstitutionSettings(): Boolean {
    val platformContext = LocalPlatformContext.current
    val settingsManager = remember(platformContext) { settingsManagerFor(platformContext) }
    var canEdit by remember { mutableStateOf(false) }
    LaunchedEffect(settingsManager) {
        val backend = settingsManager.getBackendType()
        val isOrgAdmin = backend == BackendType.FIREBASE &&
            FirebaseOrgAdminAccess.currentUserIsOrgAdmin(platformContext, settingsManager)
        canEdit = canEditSyncedInstitutionSettings(backend, isOrgAdmin)
    }
    return canEdit
}

/**
 * Pending access changes for one venue, applied only when the venue dialog is confirmed so that
 * Cancel really discards them.
 */
class VenueAccessDraft(private val saved: List<VenueAccess>) {
    var removedIds by mutableStateOf(emptySet<String>())
        private set
    var addedNames by mutableStateOf(emptyList<String>())
        private set

    val visible: List<Pair<String, String>>
        get() = saved.filter { it.id !in removedIds }.map { it.id to it.name } +
            addedNames.map { NEW_PREFIX + it to it }

    fun contains(name: String): Boolean =
        visible.any { it.second.equals(name, ignoreCase = true) }

    fun add(name: String) {
        if (name.isEmpty() || contains(name)) return
        addedNames = addedNames + name
    }

    fun remove(key: String) {
        if (key.startsWith(NEW_PREFIX)) {
            addedNames = addedNames - key.removePrefix(NEW_PREFIX)
        } else {
            removedIds = removedIds + key
        }
    }

    fun apply(venueName: String, bindings: VenueAccessBindings) {
        removedIds.forEach(bindings.onRemoveAccess)
        addedNames.forEach { bindings.onAddAccess(venueName, it) }
    }

    private companion object {
        const val NEW_PREFIX = "new:"
    }
}

/** The special accesses (stage, backstage, VIP...) a temporary guest can be granted at one venue. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VenueAccessEditor(
    venueName: String,
    draft: VenueAccessDraft,
    canEdit: Boolean,
    modifier: Modifier = Modifier,
) {
    var adding by remember { mutableStateOf(false) }
    var pendingRemoval by remember { mutableStateOf<Pair<String, String>?>(null) }
    val items = draft.visible

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(Res.string.temp_guest_access_settings_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = stringResource(Res.string.temp_guest_access_settings_description),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (items.isEmpty() && !canEdit) {
            Text(
                text = stringResource(Res.string.temp_guest_access_venue_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items.forEach { item ->
                AssistChip(
                    onClick = { if (canEdit) pendingRemoval = item },
                    label = { Text(item.second) },
                    trailingIcon = if (canEdit) {
                        {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(Res.string.delete),
                                modifier = Modifier
                                    .size(16.dp)
                                    .clickable { pendingRemoval = item },
                            )
                        }
                    } else {
                        null
                    },
                )
            }
            if (canEdit && items.size < VenueAccessCatalog.MAX_PER_VENUE) {
                AssistChip(
                    onClick = { adding = true },
                    leadingIcon = {
                        Icon(
                            Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    },
                    label = { Text(stringResource(Res.string.temp_guest_access_add)) },
                    colors = AssistChipDefaults.assistChipColors(
                        labelColor = MaterialTheme.colorScheme.primary,
                        leadingIconContentColor = MaterialTheme.colorScheme.primary,
                    ),
                )
            }
        }
        if (!canEdit) {
            Text(
                text = stringResource(Res.string.institution_settings_firebase_admin_only),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (adding) {
        var text by remember { mutableStateOf("") }
        val normalized = VenueAccessCatalog.normalizeName(text)
        val duplicate = draft.contains(normalized)
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text(stringResource(Res.string.temp_guest_access_add_title, venueName)) },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.take(VenueAccessCatalog.MAX_NAME_LENGTH) },
                    label = { Text(stringResource(Res.string.temp_guest_access_name_label)) },
                    singleLine = true,
                    isError = duplicate,
                    supportingText = if (duplicate) {
                        { Text(stringResource(Res.string.temp_guest_access_duplicate)) }
                    } else {
                        null
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        draft.add(normalized)
                        adding = false
                    },
                    enabled = normalized.isNotEmpty() && !duplicate,
                ) { Text(stringResource(Res.string.add)) }
            },
            dismissButton = {
                TextButton(onClick = { adding = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }

    pendingRemoval?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingRemoval = null },
            title = { Text(stringResource(Res.string.temp_guest_access_remove_title)) },
            text = { Text(stringResource(Res.string.temp_guest_access_remove_message, item.second)) },
            confirmButton = {
                Button(onClick = {
                    draft.remove(item.first)
                    pendingRemoval = null
                }) { Text(stringResource(Res.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { pendingRemoval = null }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}

/** Null when temporary guest features are off (Sheets backend). */
@Composable
fun rememberVenueAccessBindings(
    viewModel: com.eventmanager.app.ui.viewmodel.EventManagerViewModel,
): VenueAccessBindings? {
    if (!viewModel.isTemporaryGuestFeaturesEnabled()) return null
    val accesses by viewModel.temporaryGuestVenueAccesses.collectAsState()
    val canEdit = rememberCanEditInstitutionSettings()
    return VenueAccessBindings(
        accesses = accesses,
        canEdit = canEdit,
        onAddAccess = { venueName, name -> viewModel.addTemporaryGuestVenueAccess(venueName, name) },
        onRemoveAccess = { viewModel.removeTemporaryGuestVenueAccess(it) },
    )
}
