package com.eventmanager.app.ui.setup

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.setup_btn_details
import com.eventmanager.app.resources.setup_err_copy_report
import com.eventmanager.app.resources.setup_err_open_page
import com.eventmanager.app.resources.setup_err_report_copied
import com.eventmanager.app.resources.setup_err_repairs_header
import com.eventmanager.app.resources.setup_err_retry
import com.eventmanager.app.resources.setup_err_retry_password
import com.eventmanager.app.resources.setup_btn_hide_details
import com.eventmanager.app.resources.setup_btn_manual
import com.eventmanager.app.resources.setup_btn_run_again
import com.eventmanager.app.resources.setup_state_done
import com.eventmanager.app.resources.setup_state_error
import com.eventmanager.app.resources.setup_state_running
import com.eventmanager.app.resources.setup_state_todo
import com.eventmanager.app.resources.setup_state_waiting
import com.eventmanager.app.setup.RepairNote
import com.eventmanager.app.setup.SetupStepId
import com.eventmanager.app.setup.SetupErrorId
import com.eventmanager.app.setup.StepState
import org.jetbrains.compose.resources.stringResource

/** One step of the assistant: number, title, one sentence, status, then the step's own controls. */
@Composable
fun StepCard(
    step: SetupStepId,
    state: StepState,
    title: String,
    purpose: String,
    onShowManual: (SetupStepId) -> Unit,
    modifier: Modifier = Modifier,
    rerunnable: Boolean = false,
    repairs: List<RepairNote> = emptyList(),
    onRetry: (() -> Unit)? = null,
    retryEnabled: Boolean = true,
    onOpenLink: (String) -> Unit = {},
    report: () -> String = { "" },
    content: @Composable () -> Unit,
) {
    val done = state == StepState.Done
    var reopened by remember(done) { mutableStateOf(false) }
    Card(
        modifier = modifier.fillMaxWidth().animateContentSize(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (done) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            else MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (done) 0.dp else 2.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberBadge(step.number, done)
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                StateChip(state)
            }
            Text(purpose, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!done || reopened) {
                content()
            } else if (rerunnable) {
                TextButton(onClick = { reopened = true }) { Text(stringResource(Res.string.setup_btn_run_again)) }
            }
            when (state) {
                is StepState.Running -> LogArea(state.log)
                is StepState.Error -> ErrorBox(state, step, repairs, onRetry, retryEnabled, onOpenLink, report, onShowManual = { onShowManual(step) })
                else -> Unit
            }
        }
    }
}

@Composable
private fun NumberBadge(number: Int, done: Boolean) {
    Box(
        Modifier.size(32.dp).clip(CircleShape).background(
            if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (done) "✓" else number.toString(),
            color = if (done) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun StateChip(state: StepState) {
    val (label, color) = when (state) {
        StepState.Done -> stringResource(Res.string.setup_state_done) to MaterialTheme.colorScheme.primary
        is StepState.Running -> stringResource(Res.string.setup_state_running) to MaterialTheme.colorScheme.tertiary
        is StepState.WaitingRetry -> stringResource(
            Res.string.setup_state_waiting,
            "${state.secondsLeft / 60}:${(state.secondsLeft % 60).toString().padStart(2, '0')}",
        ) to MaterialTheme.colorScheme.tertiary
        is StepState.Error -> stringResource(Res.string.setup_state_error) to MaterialTheme.colorScheme.error
        else -> stringResource(Res.string.setup_state_todo) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (state is StepState.Running) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Surface(shape = RoundedCornerShape(50), color = color.copy(alpha = 0.12f)) {
            Text(label, color = color, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
        }
    }
}

@Composable
private fun LogArea(lines: List<String>) {
    if (lines.isEmpty()) return
    Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 160.dp).verticalScroll(rememberScrollState(Int.MAX_VALUE)).padding(10.dp),
        ) {
            lines.takeLast(40).forEach {
                Text(it, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ErrorBox(
    state: StepState.Error,
    step: SetupStepId,
    repairs: List<RepairNote>,
    onRetry: (() -> Unit)?,
    retryEnabled: Boolean,
    onOpenLink: (String) -> Unit,
    report: () -> String,
    onShowManual: () -> Unit,
) {
    val error = state.error
    val ink = MaterialTheme.colorScheme.onErrorContainer
    var showDetails by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val unknown = error.id == SetupErrorId.UNKNOWN
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.errorContainer) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (unknown && error.headline.isNotBlank()) error.headline else stringResource(errorTitle(error.id)),
                color = ink,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(stringResource(errorCause(error.id)), color = ink, style = MaterialTheme.typography.bodyMedium)
            if (repairs.isNotEmpty()) {
                Text(stringResource(Res.string.setup_err_repairs_header), color = ink, style = MaterialTheme.typography.labelLarge)
                repairs.forEach { note ->
                    val done = stringResource(repairDone(note.repair))
                    Text(
                        "- " + if (note.detail.isBlank()) done else "$done (${note.detail})",
                        color = ink,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Text(stringResource(errorSteps(error.id, step)), color = ink, style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                error.link?.let { link ->
                    OutlinedButton(onClick = { onOpenLink(link) }) { Text(stringResource(Res.string.setup_err_open_page)) }
                }
                onRetry?.let { Button(onClick = it, enabled = retryEnabled) { Text(stringResource(Res.string.setup_err_retry)) } }
                OutlinedButton(onClick = {
                    clipboard.setText(AnnotatedString(report()))
                    copied = true
                }) { Text(stringResource(if (copied) Res.string.setup_err_report_copied else Res.string.setup_err_copy_report)) }
            }
            if (!retryEnabled && step == SetupStepId.SERVER) {
                Text(stringResource(Res.string.setup_err_retry_password), color = ink, style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onShowManual) { Text(stringResource(Res.string.setup_btn_manual)) }
                if (error.details.isNotBlank() || error.headline.isNotBlank()) {
                    TextButton(onClick = { showDetails = !showDetails }) {
                        Text(stringResource(if (showDetails) Res.string.setup_btn_hide_details else Res.string.setup_btn_details))
                    }
                }
            }
            if (showDetails) {
                val shown = buildString {
                    if (!unknown && error.headline.isNotBlank()) appendLine(error.headline).appendLine()
                    append(error.details.takeLast(4_000))
                }
                Text(shown, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, color = ink)
            }
        }
    }
}
