package com.eventmanager.app.ui.components.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.remote.MigrationDirection
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.setup.FirebaseSetupAssistant
import org.jetbrains.compose.resources.stringResource

/**
 * Connexion page in Google Sheets mode: the spreadsheet, its sheet names and the service account
 * key. The platform-specific parts come in as slots so the page stays shared:
 * - [keyActions]: the key details and upload button (desktop and Android pick the file differently);
 * - [sheetNameFields]: the sheet name fields (Android has one more sheet than desktop).
 * The spreadsheet ID and the sheet names are written when the user presses "Enregistrer" ([onSave]).
 */
@Composable
fun SheetsConnectionPage(
    spreadsheetId: String,
    onSpreadsheetIdChange: (String) -> Unit,
    keyReady: Boolean,
    keyDetails: String?,
    onSave: () -> Unit,
    onOpenGuide: () -> Unit,
    keyActions: @Composable ColumnScope.() -> Unit,
    sheetNameFields: @Composable ColumnScope.() -> Unit,
) {
    SettingsPage(
        title = stringResource(Res.string.sync_connection_page_title),
        subtitle = stringResource(Res.string.sync_sheets_connection_subtitle),
    ) {
        val spreadsheetTitle = stringResource(Res.string.sync_sheets_spreadsheet_title)
        SettingsBlock(
            title = spreadsheetTitle,
            help = HelpContent(spreadsheetTitle, stringResource(Res.string.spreadsheet_id_hint)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = SettingsKitDefaults.RowHorizontalPadding,
                        end = SettingsKitDefaults.RowHorizontalPadding,
                        top = SettingsKitDefaults.RowVerticalPadding,
                    ),
            ) {
                OutlinedTextField(
                    value = spreadsheetId,
                    onValueChange = onSpreadsheetIdChange,
                    label = { Text(stringResource(Res.string.spreadsheet_id_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            var namesOpen by remember { mutableStateOf(false) }
            SettingsRow(
                title = stringResource(Res.string.sync_backup_sheet_names),
                onClick = { namesOpen = !namesOpen },
            )
            AnimatedVisibility(visible = namesOpen) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(
                            start = SettingsKitDefaults.RowHorizontalPadding,
                            end = SettingsKitDefaults.RowHorizontalPadding,
                            bottom = SettingsKitDefaults.RowVerticalPadding,
                        ),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    content = sheetNameFields,
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        start = SettingsKitDefaults.RowHorizontalPadding,
                        end = SettingsKitDefaults.RowHorizontalPadding,
                        bottom = SettingsKitDefaults.RowVerticalPadding,
                    ),
            ) {
                Button(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(Res.string.save))
                }
            }
            SettingsDivider()
            SettingsRow(
                title = stringResource(Res.string.view_setup_instructions),
                onClick = onOpenGuide,
            )
        }

        val keyTitle = stringResource(Res.string.sync_sheets_key_title)
        SettingsBlock(
            title = keyTitle,
            help = HelpContent(keyTitle, stringResource(Res.string.upload_key_description)),
        ) {
            SettingsRow(
                title = if (keyReady) {
                    stringResource(Res.string.service_account_key_found)
                } else {
                    stringResource(Res.string.service_account_key_required)
                },
                subtitle = keyDetails,
                trailing = if (keyReady) null else {
                    { StatusPill(stringResource(Res.string.sync_state_needs_config), PillTone.ATTENTION) }
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
                content = keyActions,
            )
        }
    }
}

/**
 * Synchronisation page in Google Sheets mode: sync now (with the last sync time), the sync status
 * test, the automatic sync frequency, and the switch to Firebase. Sheets sync runs on a timer, so
 * these manual controls exist only in this mode.
 *
 * [syncIntervalMinutes] is the stored value; the menu shows its nearest option and
 * [onSyncIntervalChange] runs only when the user picks one.
 */
@Composable
fun SheetsSyncPage(
    b: SyncSettingsBindings,
    isSyncing: Boolean,
    syncIntervalMinutes: Int,
    onSyncIntervalChange: (Int) -> Unit,
    onTestSyncStatus: () -> Unit,
) {
    SettingsPage(
        title = stringResource(Res.string.sync_sheets_page_title),
        subtitle = stringResource(Res.string.sync_sheets_sync_subtitle),
    ) {
        SettingsBlock(title = stringResource(Res.string.sync_connection_status_title)) {
            SettingsRow(
                title = stringResource(Res.string.sync_sheets_sync_now),
                subtitle = lastSyncText(b.lastSyncAt),
                enabled = !isSyncing,
                onClick = b.onSyncNow,
                trailing = if (isSyncing) {
                    { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                } else {
                    null
                },
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(Res.string.test_sync_status),
                onClick = onTestSyncStatus,
            )
        }

        val autoTitle = stringResource(Res.string.sync_sheets_auto_title)
        SettingsBlock(
            title = autoTitle,
            help = HelpContent(autoTitle, stringResource(Res.string.sync_interval_hint)),
        ) {
            SheetsFrequencyRow(stored = syncIntervalMinutes, onPick = onSyncIntervalChange)
        }

        var showFirebaseGuide by remember { mutableStateOf(false) }
        SettingsBlock(
            title = stringResource(Res.string.sync_backup_storage_title),
            help = HelpContent(
                stringResource(Res.string.sheets_migrate_card_title),
                stringResource(Res.string.sheets_migrate_card_body),
            ),
        ) {
            SettingsRow(
                title = stringResource(Res.string.sheets_migrate_to_firebase),
                onClick = { b.onOpenMigration(MigrationDirection.SHEETS_TO_FIREBASE) },
            )
            SettingsDivider()
            SettingsRow(
                title = stringResource(Res.string.sync_firebase_guide_title),
                onClick = { showFirebaseGuide = true },
            )
        }
        if (showFirebaseGuide) {
            FirebaseSetupAssistant(
                onDismiss = { showFirebaseGuide = false },
                projectId = b.settingsManager.getFirebaseProjectId(),
            )
        }
    }
}

@Composable
private fun lastSyncText(lastSyncAt: Long): String {
    if (lastSyncAt <= 0L) return stringResource(Res.string.sync_sheets_never_synced)
    val age = exportAge(lastSyncAt, System.currentTimeMillis())
    return when (age.unit) {
        ExportAgeUnit.NOW -> stringResource(Res.string.sync_sheets_last_sync_now)
        ExportAgeUnit.MINUTES -> stringResource(Res.string.sync_sheets_last_sync_minutes, age.count)
        ExportAgeUnit.HOURS -> stringResource(Res.string.sync_sheets_last_sync_hours, age.count)
        ExportAgeUnit.DAYS -> stringResource(Res.string.sync_sheets_last_sync_days, age.count)
    }
}

@Composable
private fun SheetsFrequencyRow(stored: Int, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = nearestSheetsSyncMinutes(stored)
    SettingsRow(
        title = stringResource(Res.string.sync_backup_frequency),
        trailing = {
            TextButton(onClick = { open = true }) {
                Text(sheetsFrequencyLabel(current))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                SHEETS_SYNC_MINUTES.forEach { minutes ->
                    DropdownMenuItem(
                        text = { Text(sheetsFrequencyLabel(minutes)) },
                        onClick = {
                            open = false
                            onPick(minutes)
                        },
                    )
                }
            }
        },
    )
}

@Composable
private fun sheetsFrequencyLabel(minutes: Int): String = when (minutes) {
    1 -> stringResource(Res.string.sync_sheets_every_minute)
    60 -> stringResource(Res.string.sync_backup_freq_hourly)
    else -> stringResource(Res.string.sync_sheets_every_minutes, minutes)
}
