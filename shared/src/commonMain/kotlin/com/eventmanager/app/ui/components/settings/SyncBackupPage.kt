package com.eventmanager.app.ui.components.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.remote.MigrationDirection
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.platform.ServiceAccountKeyUploadButton
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Sauvegarde page (Firebase): the optional one-way copy of the data into a Google Sheets
 * spreadsheet, and the way to switch the live storage to Google Sheets. The copy is organization
 * level, so only org admins can change it; others see a read-only notice. The switch stays open
 * to everyone because the migration wizard handles its own sign-in.
 */
@Composable
fun SyncBackupPage(b: SyncSettingsBindings, showTitle: Boolean = true) {
    SettingsPage(
        showTitle = showTitle,
        title = stringResource(Res.string.sync_backup_page_title),
        subtitle = stringResource(Res.string.sync_backup_page_subtitle),
        readOnlyNotice = syncPageNoticeText(b),
    ) {
        MirrorBlock(b, canEdit = b.isOrgAdmin)
        StorageBlock(b)
    }
}

@Composable
private fun MirrorBlock(b: SyncSettingsBindings, canEdit: Boolean) {
    val sm = b.settingsManager
    val title = stringResource(Res.string.sheets_mirror_section_title)
    var mirrorEnabled by remember { mutableStateOf(sm.isSheetsMirrorEnabled()) }
    var tick by remember { mutableIntStateOf(0) }
    var exporting by remember { mutableStateOf(false) }
    var uploadStatus by remember { mutableStateOf<String?>(null) }
    var spreadsheetId by remember { mutableStateOf(sm.getSheetsMirrorSpreadsheetId()) }
    var intervalMinutes by remember { mutableIntStateOf(sm.getSheetsMirrorIntervalMinutes()) }
    val keyReady = remember(tick) { sm.isConfigured() }
    val lastExportAt = remember(tick, mirrorEnabled) { sm.getSheetsMirrorLastExportAt() }

    SettingsBlock(
        title = title,
        help = HelpContent(title = title, body = stringResource(Res.string.sheets_mirror_section_body)),
    ) {
        SettingsSwitchRow(
            title = stringResource(Res.string.sync_backup_switch_title),
            subtitle = null,
            checked = mirrorEnabled,
            enabled = canEdit,
            onCheckedChange = {
                mirrorEnabled = it
                sm.setSheetsMirrorEnabled(it)
                b.onMirrorSettingsChanged()
            },
            expanded = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = SettingsKitDefaults.RowHorizontalPadding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    SaveOnBlurField(
                        value = spreadsheetId,
                        onValueChange = { spreadsheetId = it },
                        label = stringResource(Res.string.sheets_mirror_spreadsheet_id_label),
                        enabled = canEdit,
                        onCommit = {
                            sm.setSheetsMirrorSpreadsheetId(spreadsheetId.trim())
                            b.onMirrorSettingsChanged()
                        },
                    )
                }
                FrequencyRow(
                    stored = intervalMinutes,
                    enabled = canEdit,
                    onPick = {
                        intervalMinutes = it.minutes
                        sm.setSheetsMirrorIntervalMinutes(it.minutes)
                        b.onMirrorSettingsChanged()
                    },
                )
                SettingsRow(
                    title = stringResource(Res.string.sync_backup_export_now),
                    subtitle = lastExportText(lastExportAt, rememberMinuteClock()),
                    enabled = canEdit && keyReady && spreadsheetId.isNotBlank() && !exporting,
                    onClick = {
                        exporting = true
                        val typedId = spreadsheetId.trim()
                        if (typedId != sm.getSheetsMirrorSpreadsheetId()) {
                            sm.setSheetsMirrorSpreadsheetId(typedId)
                            b.onMirrorSettingsChanged()
                        }
                        b.scope.launch {
                            try {
                                b.onMirrorExport()
                            } finally {
                                exporting = false
                                tick++
                            }
                        }
                    },
                )
                SettingsRow(
                    title = stringResource(Res.string.sync_sheets_key_title),
                    subtitle = if (keyReady) {
                        stringResource(Res.string.migration_service_account_ok)
                    } else {
                        stringResource(Res.string.sync_backup_key_missing)
                    },
                )
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = SettingsKitDefaults.RowHorizontalPadding,
                            end = SettingsKitDefaults.RowHorizontalPadding,
                            bottom = SettingsKitDefaults.RowVerticalPadding,
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ServiceAccountKeyUploadButton(
                        platformContext = b.platformContext,
                        onStatusUpdate = { message ->
                            uploadStatus = message
                            tick++
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    uploadStatus?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SheetNames(b, canEdit)
            },
        )
    }
}

@Composable
private fun lastExportText(lastExportAt: Long, nowMillis: Long): String? {
    if (lastExportAt <= 0L) return null
    val age = exportAge(lastExportAt, nowMillis)
    return when (age.unit) {
        ExportAgeUnit.NOW -> stringResource(Res.string.sync_backup_last_export_now)
        ExportAgeUnit.MINUTES -> stringResource(Res.string.sync_backup_last_export_minutes, age.count)
        ExportAgeUnit.HOURS -> stringResource(Res.string.sync_backup_last_export_hours, age.count)
        ExportAgeUnit.DAYS -> stringResource(Res.string.sync_backup_last_export_days, age.count)
    }
}

/**
 * Shows the nearest option for [stored]; nothing is written until the user picks one (an odd stored
 * value such as 45 stays as it is).
 */
@Composable
private fun FrequencyRow(stored: Int, enabled: Boolean, onPick: (MirrorFrequency) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = MirrorFrequency.fromMinutes(stored)
    SettingsRow(
        title = stringResource(Res.string.sync_backup_frequency),
        enabled = enabled,
        trailing = {
            TextButton(onClick = { open = true }, enabled = enabled) {
                Text(frequencyLabel(current))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                MirrorFrequency.entries.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(frequencyLabel(option)) },
                        onClick = {
                            open = false
                            onPick(option)
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun frequencyLabel(f: MirrorFrequency): String = when (f) {
    MirrorFrequency.HOURLY -> stringResource(Res.string.sync_backup_freq_hourly)
    MirrorFrequency.DAILY -> stringResource(Res.string.sync_backup_freq_daily)
    MirrorFrequency.MANUAL -> stringResource(Res.string.sync_backup_freq_manual)
}

/** The nine sheet names, collapsed by default. Each is saved when its field loses focus. */
@Composable
private fun SheetNames(b: SyncSettingsBindings, canEdit: Boolean) {
    val sm = b.settingsManager
    var open by remember { mutableStateOf(false) }
    var guestList by remember { mutableStateOf(sm.getGuestListSheet()) }
    var volunteers by remember { mutableStateOf(sm.getVolunteerSheet()) }
    var jobs by remember { mutableStateOf(sm.getJobsSheet()) }
    var jobTypes by remember { mutableStateOf(sm.getJobTypesSheet()) }
    var venues by remember { mutableStateOf(sm.getVenuesSheet()) }
    var salesItems by remember { mutableStateOf(sm.getSalesItemsSheet()) }
    var transfers by remember { mutableStateOf(sm.getTransfersSheet()) }
    var tempGuestList by remember { mutableStateOf(sm.getTempGuestListSheet()) }
    var settings by remember { mutableStateOf(sm.getSettingsSheet()) }

    SettingsRow(
        title = stringResource(Res.string.sync_backup_sheet_names),
        onClick = { open = !open },
    )
    AnimatedVisibility(visible = open) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(
                    start = SettingsKitDefaults.RowHorizontalPadding,
                    end = SettingsKitDefaults.RowHorizontalPadding,
                    bottom = SettingsKitDefaults.RowVerticalPadding,
                ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            fun commit(save: () -> Unit) {
                save()
                b.onMirrorSettingsChanged()
            }
            SaveOnBlurField(guestList, { guestList = it }, stringResource(Res.string.guest_list_sheet_label), canEdit) {
                commit { sm.saveGuestListSheet(guestList.trim()) }
            }
            SaveOnBlurField(volunteers, { volunteers = it }, stringResource(Res.string.volunteer_sheet_label), canEdit) {
                commit { sm.saveVolunteerSheet(volunteers.trim()) }
            }
            SaveOnBlurField(jobs, { jobs = it }, stringResource(Res.string.shifts_sheet_label), canEdit) {
                commit { sm.saveJobsSheet(jobs.trim()) }
            }
            SaveOnBlurField(jobTypes, { jobTypes = it }, stringResource(Res.string.shift_types_sheet_label), canEdit) {
                commit { sm.saveJobTypesSheet(jobTypes.trim()) }
            }
            SaveOnBlurField(venues, { venues = it }, stringResource(Res.string.venues_sheet_label), canEdit) {
                commit { sm.saveVenuesSheet(venues.trim()) }
            }
            SaveOnBlurField(salesItems, { salesItems = it }, stringResource(Res.string.sales_items_sheet_label), canEdit) {
                commit { sm.saveSalesItemsSheet(salesItems.trim()) }
            }
            SaveOnBlurField(transfers, { transfers = it }, stringResource(Res.string.transfers_sheet_label), canEdit) {
                commit { sm.saveTransfersSheet(transfers.trim()) }
            }
            SaveOnBlurField(tempGuestList, { tempGuestList = it }, stringResource(Res.string.temp_guest_list_sheet_label), canEdit) {
                commit { sm.saveTempGuestListSheet(tempGuestList.trim()) }
            }
            SaveOnBlurField(settings, { settings = it }, stringResource(Res.string.settings_sheet_label), canEdit) {
                commit { sm.saveSettingsSheet(settings.trim()) }
            }
        }
    }
}

/** A single-line field that calls [onCommit] once it loses focus after the user had it. */
@Composable
private fun SaveOnBlurField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    enabled: Boolean,
    onCommit: () -> Unit,
) {
    var hadFocus by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    val latestCommit by rememberUpdatedState(onCommit)
    // Leaving the page while the field is being edited must not drop the edit.
    DisposableEffect(Unit) {
        onDispose { if (hadFocus && dirty) latestCommit() }
    }
    OutlinedTextField(
        value = value,
        onValueChange = {
            dirty = true
            onValueChange(it)
        },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged {
                if (it.isFocused) {
                    hadFocus = true
                } else if (hadFocus) {
                    hadFocus = false
                    dirty = false
                    onCommit()
                }
            },
    )
}

@Composable
private fun StorageBlock(b: SyncSettingsBindings) {
    SettingsBlock(title = stringResource(Res.string.sync_backup_storage_title)) {
        // Open to everyone: the migration wizard asks for its own sign-in.
        SettingsRow(
            title = stringResource(Res.string.firebase_migrate_to_sheets),
            onClick = { b.onOpenMigration(MigrationDirection.FIREBASE_TO_SHEETS) },
        )
    }
}
