package com.eventmanager.app.ui.components.settings.certificate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import com.eventmanager.app.portal.certificate.CertificateDates
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.components.EventDatePickerDialog
import com.eventmanager.app.ui.components.formatEuropeanDate
import com.eventmanager.app.ui.components.formatEuropeanDateInput
import com.eventmanager.app.ui.components.parseEuropeanDate
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.jetbrains.compose.resources.stringResource

/** 20260305 -> 05/03/2026; "" for 0 or an impossible day. */
internal fun formatCertificateDay(day: Int): String = dateOfDay(day)?.let(::formatEuropeanDate).orEmpty()

internal fun dateOfDay(day: Int): LocalDate? =
    if (day <= 0) null else runCatching { LocalDate.of(day / 10000, (day / 100) % 100, day % 100) }.getOrNull()

internal fun dayOfDate(date: LocalDate): Int = date.year * 10000 + date.monthValue * 100 + date.dayOfMonth

internal fun formatCertificateMillis(millis: Long): String = if (millis <= 0) "" else formatCertificateDay(CertificateDates.dayOf(millis))

@Composable
private fun InfoRow(label: String, value: String, isError: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.widthIn(min = 120.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun Hint(text: String, isError: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** Who, where, when and what: read-only for a request, the period and types to choose in direct mode. */
@Composable
internal fun CertificateReviewHeader(model: CertificateReviewModel, enabled: Boolean) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val target = model.target
        InfoRow(
            stringResource(Res.string.certificate_review_address),
            model.recipient.ifEmpty { "-" },
            isError = !model.recipientValid,
        )
        if (model.isDirect && !model.recipientValid) {
            Hint(stringResource(Res.string.certificate_reason_no_email), isError = true)
        }
        if (target is CertificateReviewTarget.Request) {
            val request = target.request
            InfoRow(stringResource(Res.string.certificate_review_requested), formatCertificateMillis(request.requestedAt))
            InfoRow(
                stringResource(Res.string.certificate_review_period),
                stringResource(Res.string.certificate_review_period_value, formatCertificateDay(request.fromDay), formatCertificateDay(request.toDay)),
            )
            InfoRow(stringResource(Res.string.certificate_review_types), request.jobTypes.sorted().joinToString(", "))
            if (request.lastReason.isNotEmpty()) {
                Hint(stringResource(Res.string.certificate_review_sent_back, reasonText(request.lastReason)), isError = true)
            }
            if (model.onlyRefusal) Hint(stringResource(Res.string.certificate_review_no_profile_hint), isError = true)
        } else {
            CertificateDirectChoice(model, enabled)
        }
        if (!model.onlyRefusal) Hint(stringResource(Res.string.certificate_review_only_this))
    }
}

/** Period and job types of a generated certificate; a change rebuilds the text (after a confirmation when it was corrected). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CertificateDirectChoice(model: CertificateReviewModel, enabled: Boolean) {
    var pending by remember { mutableStateOf<Triple<Int, Int, Set<String>>?>(null) }
    // Bumped when a change is cancelled, so the date fields show the kept dates again.
    var revision by remember { mutableIntStateOf(0) }
    fun ask(from: Int, to: Int, types: Set<String>) {
        if (from == model.fromDay && to == model.toDay && types == model.selectedTypes) return
        if (model.isEdited()) pending = Triple(from, to, types) else model.select(from, to, types)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(Res.string.certificate_review_period))
        Row(Modifier.widthIn(max = 480.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CertificateDayField(stringResource(Res.string.certificate_generate_from), model.fromDay, revision, enabled, Modifier.weight(1f)) {
                ask(it, model.toDay, model.selectedTypes)
            }
            CertificateDayField(stringResource(Res.string.certificate_generate_to), model.toDay, revision, enabled, Modifier.weight(1f)) {
                ask(model.fromDay, it, model.selectedTypes)
            }
        }
        if (!model.periodValid()) Hint(stringResource(Res.string.certificate_generate_period_invalid), isError = true)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(Res.string.certificate_review_types),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            val all = model.typeChoices.flatMap { it.second }.toSet()
            TextButton(onClick = { ask(model.fromDay, model.toDay, all) }, enabled = enabled) {
                Text(stringResource(Res.string.certificate_generate_all))
            }
            TextButton(onClick = { ask(model.fromDay, model.toDay, emptySet()) }, enabled = enabled) {
                Text(stringResource(Res.string.certificate_generate_none))
            }
        }
        for ((category, names) in model.typeChoices) {
            Text(
                stringResource(blockTitle(category.name)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (name in names) {
                    val selected = name in model.selectedTypes
                    FilterChip(
                        selected = selected,
                        enabled = enabled,
                        onClick = { ask(model.fromDay, model.toDay, if (selected) model.selectedTypes - name else model.selectedTypes + name) },
                        label = { Text(name) },
                    )
                }
            }
        }
    }

    pending?.let { (from, to, types) ->
        AlertDialog(
            onDismissRequest = {
                pending = null
                revision++
            },
            text = { Text(stringResource(Res.string.certificate_generate_rebuild_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    model.select(from, to, types)
                }) { Text(stringResource(Res.string.certificate_generate_rebuild)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    pending = null
                    revision++
                }) { Text(stringResource(Res.string.cancel)) }
            },
        )
    }
}

/** A day typed as DD/MM/YYYY or picked in a calendar; [onDay] gets every complete date. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CertificateDayField(label: String, day: Int, revision: Int, enabled: Boolean, modifier: Modifier, onDay: (Int) -> Unit) {
    val external = formatCertificateDay(day)
    var field by remember { mutableStateOf(TextFieldValue(external, TextRange(external.length))) }
    LaunchedEffect(external, revision) {
        if (field.text != external) field = TextFieldValue(external, TextRange(external.length))
    }
    var picking by remember { mutableStateOf(false) }
    OutlinedTextField(
        value = field,
        onValueChange = { value ->
            val (formatted, cursor) = formatEuropeanDateInput(value.text, value.selection.start)
            field = TextFieldValue(formatted, TextRange(cursor))
            parseEuropeanDate(formatted)?.let { onDay(dayOfDate(it)) }
        },
        label = { Text(label) },
        singleLine = true,
        enabled = enabled,
        isError = field.text.length == 10 && parseEuropeanDate(field.text) == null,
        trailingIcon = {
            IconButton(onClick = { picking = true }, enabled = enabled) {
                Icon(Icons.Default.CalendarMonth, contentDescription = stringResource(Res.string.select_date))
            }
        },
        modifier = modifier,
    )
    if (picking) {
        val initial = dateOfDay(day)?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        EventDatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    state.selectedDateMillis?.let { ms ->
                        onDay(dayOfDate(Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()))
                    }
                }) { Text(stringResource(Res.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(Res.string.cancel)) } },
        ) { DatePicker(state = state) }
    }
}

/** Optional reason (at most 300 characters, counted), then the request is refused. No e-mail goes out. */
@Composable
internal fun CertificateRefuseDialog(onDismiss: () -> Unit, onRefuse: (String) -> Unit) {
    var note by remember { mutableStateOf("") }
    val max = CertificateReviewModel.MAX_NOTE
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.certificate_review_refuse_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(max) },
                    label = { Text(stringResource(Res.string.certificate_review_refuse_note)) },
                    supportingText = { Text("${note.length}/$max") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Hint(stringResource(Res.string.certificate_review_refuse_hint))
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onRefuse(note.trim()) },
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text(stringResource(Res.string.certificate_review_refuse)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}
