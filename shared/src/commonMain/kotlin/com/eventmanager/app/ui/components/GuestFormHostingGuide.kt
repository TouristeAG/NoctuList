package com.eventmanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.platform.PlatformFileManager
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.guest_form_export
import com.eventmanager.app.resources.guest_form_export_android
import com.eventmanager.app.resources.guest_form_export_done
import com.eventmanager.app.resources.guest_form_project_id_hint
import com.eventmanager.app.resources.guest_form_step1
import com.eventmanager.app.resources.guest_form_step2
import com.eventmanager.app.resources.guest_form_step3
import com.eventmanager.app.resources.guest_form_step4
import com.eventmanager.app.resources.guest_form_step5
import com.eventmanager.app.resources.guest_form_step6
import com.eventmanager.app.ui.guestform.GuestFormSiteBundle
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Hosting walkthrough shown only in the Firebase setup guide.
 */
@Composable
fun GuestFormHostingGuide(
    projectId: String,
    isDesktop: Boolean,
    canExport: Boolean,
    showExportButton: Boolean,
    modifier: Modifier = Modifier,
) {
    val platformContext = LocalPlatformContext.current
    val scope = rememberCoroutineScope()
    var exportStatus by remember { mutableStateOf<String?>(null) }
    val exportDone = stringResource(Res.string.guest_form_export_done)
    val commands = remember(projectId) { guestFormHostingCommands(projectId) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            stringResource(Res.string.guest_form_step1),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(Res.string.guest_form_step2),
            style = MaterialTheme.typography.bodySmall,
        )
        if (showExportButton) {
            if (isDesktop) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val ok = GuestFormSiteBundle.exportZip(PlatformFileManager(platformContext))
                            exportStatus = if (ok) exportDone else null
                        }
                    },
                    enabled = canExport,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(Res.string.guest_form_export))
                }
            } else {
                Text(
                    stringResource(Res.string.guest_form_export_android),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            exportStatus?.let { status ->
                Text(
                    status,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        Text(
            stringResource(Res.string.guest_form_step3),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(Res.string.guest_form_step4),
            style = MaterialTheme.typography.bodySmall,
        )
        Text(
            stringResource(Res.string.guest_form_step5),
            style = MaterialTheme.typography.bodySmall,
        )
        CopyableTerminalBlock(commands = commands)
        if (projectId.isBlank()) {
            Text(
                stringResource(Res.string.guest_form_project_id_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            stringResource(Res.string.guest_form_step6),
            style = MaterialTheme.typography.bodySmall,
        )
    }
}
