package com.eventmanager.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.remote.FirestoreRulesClipboardContent
import com.eventmanager.app.data.remote.InstitutionGoogleWebOAuth
import com.eventmanager.app.data.remote.StorageRulesClipboardContent
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.platform.isDesktop
import com.eventmanager.app.resources.*
import com.eventmanager.app.setup.SetupStepId
import com.eventmanager.app.ui.components.CopyableTerminalBlock
import com.eventmanager.app.ui.components.GuestFormHostingGuide
import com.eventmanager.app.ui.components.ManualSetupCommands
import com.eventmanager.app.ui.components.portalServerCleanupCommand
import com.eventmanager.app.ui.components.portalServerDeployCommands
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

private fun manualBody(step: SetupStepId): StringResource = when (step) {
    SetupStepId.TOOLS -> Res.string.setup_manual_tools
    SetupStepId.LOGIN -> Res.string.setup_manual_login
    SetupStepId.PROJECT -> Res.string.setup_manual_project
    SetupStepId.DATABASE -> Res.string.setup_manual_database
    SetupStepId.RULES -> Res.string.setup_manual_rules
    SetupStepId.PROVIDERS -> Res.string.setup_manual_providers
    SetupStepId.CONNECT -> Res.string.setup_manual_connect
    SetupStepId.SITE -> Res.string.setup_manual_site
    SetupStepId.PHOTOS -> Res.string.setup_manual_photos
    SetupStepId.SERVER -> Res.string.setup_manual_server
}

/** The same steps as the Automatic tab, written out for doing them by hand. */
@Composable
fun ManualTab(projectId: String, scrollTo: SetupStepId?) {
    val steps = SetupStepId.entries
    val listState = rememberLazyListState()
    LaunchedEffect(scrollTo) {
        if (scrollTo != null) listState.animateScrollToItem(steps.indexOf(scrollTo) + 1)
    }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        item {
            Text(stringResource(Res.string.setup_manual_intro), style = MaterialTheme.typography.bodyMedium)
        }
        itemsIndexed(steps, key = { _, s -> s.name }) { _, step ->
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${step.number}. ${stringResource(stepTitle(step))}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(stringResource(manualBody(step)), style = MaterialTheme.typography.bodyMedium)
                    ManualExtras(step, projectId)
                }
            }
        }
    }
}

@Composable
private fun ManualExtras(step: SetupStepId, projectId: String) {
    when (step) {
        SetupStepId.TOOLS -> CopyableTerminalBlock(ManualSetupCommands.TOOLS)
        SetupStepId.LOGIN -> CopyableTerminalBlock(ManualSetupCommands.LOGIN)
        SetupStepId.PROJECT -> CopyableTerminalBlock(ManualSetupCommands.createProject(projectId))
        SetupStepId.DATABASE -> CopyableTerminalBlock(ManualSetupCommands.createDatabase(projectId))
        SetupStepId.RULES -> RulesCopyButton(
            idle = Res.string.firebase_tutorial_copy_rules,
            done = Res.string.firebase_tutorial_copy_rules_done,
            error = Res.string.firebase_tutorial_copy_rules_error,
        ) { FirestoreRulesClipboardContent.load() }
        SetupStepId.PROVIDERS -> Unit
        SetupStepId.CONNECT -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CopyableTerminalBlock(ManualSetupCommands.webConfig(projectId))
            RedirectUris()
        }
        SetupStepId.SITE -> GuestFormHostingGuide(
            projectId = projectId,
            isDesktop = LocalPlatformContext.current.isDesktop,
            canExport = true,
            showExportButton = true,
        )
        SetupStepId.PHOTOS -> RulesCopyButton(
            idle = Res.string.firebase_tutorial_copy_storage_rules,
            done = Res.string.firebase_tutorial_copy_storage_rules_done,
            error = Res.string.firebase_tutorial_copy_storage_rules_error,
        ) { StorageRulesClipboardContent.load() }
        SetupStepId.SERVER -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CopyableTerminalBlock(portalServerDeployCommands(projectId))
            Text(stringResource(Res.string.setup_manual_server_cleanup), style = MaterialTheme.typography.bodyMedium)
            CopyableTerminalBlock(portalServerCleanupCommand(projectId))
            Text(stringResource(Res.string.firebase_tutorial_server_optional_status), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** The localhost addresses desktop Google sign-in returns to; they go in the Web client. */
@Composable
internal fun RedirectUris() {
    Text(stringResource(Res.string.setup_redirect_note), style = MaterialTheme.typography.bodyMedium)
    RulesCopyButton(
        idle = Res.string.firebase_tutorial_copy_redirect_uris,
        done = Res.string.firebase_tutorial_copy_redirect_uris_done,
        error = Res.string.firebase_tutorial_copy_redirect_uris,
    ) { InstitutionGoogleWebOAuth.loopbackRedirectUrisClipboardText() }
}

@Composable
private fun RulesCopyButton(idle: StringResource, done: StringResource, error: StringResource, load: suspend () -> String) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(idle) }
    OutlinedButton(
        onClick = {
            scope.launch {
                val text = runCatching { load() }.getOrNull()
                if (text.isNullOrBlank()) {
                    status = error
                } else {
                    clipboard.setText(AnnotatedString(text))
                    status = done
                }
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) { Text(stringResource(status)) }
}
