package com.eventmanager.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.setup_close
import com.eventmanager.app.resources.setup_phone_note
import com.eventmanager.app.resources.setup_progress
import com.eventmanager.app.resources.setup_tab_automatic
import com.eventmanager.app.resources.setup_tab_manual
import com.eventmanager.app.resources.setup_title
import com.eventmanager.app.setup.SetupStepId
import com.eventmanager.app.setup.StepState
import com.eventmanager.app.setup.createSetupAutomation
import org.jetbrains.compose.resources.stringResource

/**
 * The Firebase setup page: an Automatic tab (desktop only) where the app runs each step, and a
 * Manual tab with the same steps written out. Replaces the old Firebase guide everywhere.
 */
@Composable
fun FirebaseSetupAssistant(onDismiss: () -> Unit, projectId: String = "") {
    val platform = LocalPlatformContext.current
    val automation = remember { createSetupAutomation(platform, SettingsManager(platform)) }
    var tab by remember { mutableStateOf(if (automation.available) 0 else 1) }
    var manualTarget by remember { mutableStateOf<SetupStepId?>(null) }
    val states by automation.states.collectAsState()
    val progress by automation.progress.collectAsState()
    val counted = requiredSteps + optionalSteps.filter { it in progress.extras }
    val doneCount = counted.count { states[it] == StepState.Done }
    val autoList = rememberLazyListState()
    // Closing the page in any way (button, Esc, click outside) stops running commands.
    DisposableEffect(automation) { onDispose { automation.close() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth(0.95f).fillMaxHeight(0.92f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(Res.string.setup_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.setup_close)) }
                }
                if (automation.available) {
                    Text(
                        stringResource(Res.string.setup_progress, doneCount, counted.size),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        progress = { if (counted.isEmpty()) 0f else doneCount.toFloat() / counted.size },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TabRow(selectedTabIndex = tab) {
                        Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(Res.string.setup_tab_automatic)) })
                        Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(Res.string.setup_tab_manual)) })
                    }
                } else {
                    Text(stringResource(Res.string.setup_phone_note), style = MaterialTheme.typography.bodyMedium)
                }
                if (tab == 0 && automation.available) {
                    AutomaticTab(
                        automation = automation,
                        projectId = projectId,
                        listState = autoList,
                        onShowManual = { manualTarget = it; tab = 1 },
                    )
                } else {
                    ManualTab(projectId = progress.projectId.ifBlank { projectId }, scrollTo = manualTarget)
                }
            }
        }
    }
}
