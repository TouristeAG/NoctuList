package com.eventmanager.app.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.utils.GuestListDefaultZoneId
import org.jetbrains.compose.resources.stringResource
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters

private val EuropeanDateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")

/** Masks free typing into `JJ/MM/AAAA` and keeps the caret where the user expects it. */
internal fun formatEuropeanDateInput(input: String, cursorPosition: Int): Pair<String, Int> {
    val digits = input.filter { it.isDigit() }.take(8)
    val formatted = when {
        digits.length <= 2 -> digits
        digits.length <= 4 -> "${digits.substring(0, 2)}/${digits.substring(2)}"
        else -> "${digits.substring(0, 2)}/${digits.substring(2, 4)}/${digits.substring(4)}"
    }
    val digitsBeforeCursor = input.take(cursorPosition.coerceAtMost(input.length)).count { it.isDigit() }
    var newCursor = formatted.length
    var seen = 0
    for (i in formatted.indices) {
        if (!formatted[i].isDigit()) continue
        seen++
        if (seen == digitsBeforeCursor) {
            newCursor = i + 1
        } else if (seen > digitsBeforeCursor) {
            newCursor = i
            break
        }
    }
    if (digitsBeforeCursor >= digits.length) newCursor = formatted.length
    return formatted to newCursor.coerceIn(0, formatted.length)
}

internal fun parseEuropeanDate(text: String): LocalDate? =
    runCatching { LocalDate.parse(text.trim(), EuropeanDateFormatter) }.getOrNull()

internal fun formatEuropeanDate(date: LocalDate): String = date.format(EuropeanDateFormatter)

/**
 * Next occurrence of [target], or today when today already is that day — "ce vendredi" during the
 * Friday night shift should stay on the current night, not jump a week ahead.
 */
internal fun nextOrSameWeekday(today: LocalDate, target: DayOfWeek): LocalDate =
    today.with(TemporalAdjusters.nextOrSame(target))

/**
 * Event date entry in the European `JJ/MM/AAAA` format, with a real calendar and one-tap shortcuts
 * for the nights an event is usually created for.
 *
 * @param selectedDateMillis start of the selected day in [GuestListDefaultZoneId], or null.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EventDatePickerField(
    selectedDateMillis: Long?,
    onDateSelected: (Long?) -> Unit,
    modifier: Modifier = Modifier,
    label: String = stringResource(Res.string.temp_guest_event_date_label),
    enabled: Boolean = true,
) {
    val zone = GuestListDefaultZoneId
    val today = remember { LocalDate.now(zone) }
    var showPicker by remember { mutableStateOf(false) }

    val externalText = remember(selectedDateMillis) {
        selectedDateMillis
            ?.let { formatEuropeanDate(Instant.ofEpochMilli(it).atZone(zone).toLocalDate()) }
            .orEmpty()
    }
    var field by remember { mutableStateOf(TextFieldValue(externalText, TextRange(externalText.length))) }

    // Only reset the field when the value changed from the outside (a chip or the calendar), so
    // typing an incomplete date is never fought by the state holder.
    LaunchedEffect(externalText) {
        if (externalText.isNotEmpty() && field.text != externalText) {
            field = TextFieldValue(externalText, TextRange(externalText.length))
        }
    }

    fun commit(date: LocalDate?) {
        onDateSelected(date?.atStartOfDay(zone)?.toInstant()?.toEpochMilli())
    }

    fun select(date: LocalDate) {
        val text = formatEuropeanDate(date)
        field = TextFieldValue(text, TextRange(text.length))
        commit(date)
    }

    val shortcuts = listOf(
        stringResource(Res.string.date_shortcut_today) to today,
        stringResource(Res.string.date_shortcut_this_friday) to nextOrSameWeekday(today, DayOfWeek.FRIDAY),
        stringResource(Res.string.date_shortcut_this_saturday) to nextOrSameWeekday(today, DayOfWeek.SATURDAY),
    )

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = field,
            onValueChange = { newValue ->
                val (formatted, cursor) = formatEuropeanDateInput(newValue.text, newValue.selection.start)
                field = TextFieldValue(formatted, TextRange(cursor))
                commit(parseEuropeanDate(formatted))
            },
            label = { Text(label) },
            placeholder = { Text(stringResource(Res.string.date_format_european_placeholder)) },
            singleLine = true,
            enabled = enabled,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            trailingIcon = {
                IconButton(onClick = { showPicker = true }, enabled = enabled) {
                    Icon(
                        Icons.Default.CalendarToday,
                        contentDescription = stringResource(Res.string.select_date),
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            shortcuts.forEach { (chipLabel, date) ->
                AssistChip(
                    onClick = { if (enabled) select(date) },
                    label = { Text(chipLabel) },
                    enabled = enabled,
                )
            }
        }
    }

    if (showPicker) {
        val initial = parseEuropeanDate(field.text) ?: today
        val pickerState = rememberDatePickerState(
            // The Material picker works in UTC; anchoring at UTC midnight keeps the highlighted day
            // identical to the one typed in the field.
            initialSelectedDateMillis = initial.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(),
            yearRange = IntRange(today.year - 1, today.year + 3),
        )
        EventDatePickerDialog(
            onDismissRequest = { showPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pickerState.selectedDateMillis?.let { millis ->
                        select(Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate())
                    }
                    showPicker = false
                }) { Text(stringResource(Res.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { showPicker = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        ) {
            DatePicker(state = pickerState)
        }
    }
}
