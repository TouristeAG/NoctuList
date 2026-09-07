package com.eventmanager.app.ui.components
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import org.jetbrains.compose.resources.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.eventmanager.app.data.utils.DateTimeUtils
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateTimePicker(
    selectedTimestamp: Long,
    onTimestampChanged: (Long) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    val resolvedLabel = label ?: stringResource(Res.string.date_time_label)
    val calendar = remember(selectedTimestamp) {
        Calendar.getInstance(TimeZone.getTimeZone("Europe/Zurich")).apply {
            timeInMillis = selectedTimestamp
        }
    }
    
    var showDatePicker by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = resolvedLabel,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium
        )
        
        // Stacked: date then time — narrow dialogs clip two side-by-side buttons.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = { showDatePicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.CalendarToday,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(DateTimeUtils.formatGenevaDateOnly(selectedTimestamp))
            }
            OutlinedButton(
                onClick = { showTimePicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    Icons.Default.Schedule,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(DateTimeUtils.formatGenevaTimeOnly(selectedTimestamp))
            }
        }
        
        // Current selection display
        Text(
            text = DateTimeUtils.formatGenevaDate(selectedTimestamp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        // Relative time indicator
        Text(
            text = DateTimeUtils.getRelativeTimeString(selectedTimestamp),
            style = MaterialTheme.typography.bodySmall,
            color = when {
                DateTimeUtils.isPast(selectedTimestamp) -> MaterialTheme.colorScheme.error
                DateTimeUtils.isFuture(selectedTimestamp) -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
    
    // Date picker dialog
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = selectedTimestamp
        )
        
        EventDatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { dateMillis ->
                            // Combine selected date with current time
                            val newCalendar = Calendar.getInstance(TimeZone.getTimeZone("Europe/Zurich"))
                            newCalendar.timeInMillis = dateMillis
                            newCalendar.set(Calendar.HOUR_OF_DAY, calendar.get(Calendar.HOUR_OF_DAY))
                            newCalendar.set(Calendar.MINUTE, calendar.get(Calendar.MINUTE))
                            newCalendar.set(Calendar.SECOND, 0)
                            newCalendar.set(Calendar.MILLISECOND, 0)
                            onTimestampChanged(newCalendar.timeInMillis)
                        }
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(Res.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
    
    // Time picker dialog
    if (showTimePicker) {
        val timePickerState = rememberTimePickerState(
            initialHour = calendar.get(Calendar.HOUR_OF_DAY),
            initialMinute = calendar.get(Calendar.MINUTE),
            is24Hour = true
        )
        
        TimePickerDialog(
            onDismissRequest = { showTimePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        // Combine current date with selected time
                        val newCalendar = Calendar.getInstance(TimeZone.getTimeZone("Europe/Zurich"))
                        newCalendar.timeInMillis = selectedTimestamp
                        newCalendar.set(Calendar.HOUR_OF_DAY, timePickerState.hour)
                        newCalendar.set(Calendar.MINUTE, timePickerState.minute)
                        newCalendar.set(Calendar.SECOND, 0)
                        newCalendar.set(Calendar.MILLISECOND, 0)
                        onTimestampChanged(newCalendar.timeInMillis)
                        showTimePicker = false
                    }
                ) {
                    Text(stringResource(Res.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showTimePicker = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        ) {
            // Dial collapses under phone resolution scaling — use digital input.
            TimeInput(state = timePickerState)
        }
    }
}

@Composable
internal fun EventDatePickerDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Res.string.select_date)) },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                content()
            }
        },
        confirmButton = confirmButton,
        dismissButton = dismissButton,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    )
}

@Composable
private fun TimePickerDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    dismissButton: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text(stringResource(Res.string.select_time)) },
        text = content,
        confirmButton = confirmButton,
        dismissButton = dismissButton
    )
}

/**
 * A date picker component specifically for birthday entry.
 * Uses a text field that opens a date picker dialog when clicked.
 * 
 * @param dateString The date string in dd.MM.yyyy format (for display) or yyyy-MM-dd (for storage)
 * @param onDateSelected Callback with the date in dd.MM.yyyy format
 * @param modifier Modifier for the component
 * @param label Label for the text field
 * @param placeholder Placeholder text
 * @param isError Whether to show error state
 * @param supportingText Supporting text (typically error message)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BirthdayDatePicker(
    dateString: String,
    onDateSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable () -> Unit = {},
    placeholder: @Composable () -> Unit = {},
    isError: Boolean = false,
    supportingText: @Composable (() -> Unit)? = null
) {
    var showDatePicker by remember { mutableStateOf(false) }
    
    // Normalize date string to display format (dd.MM.yyyy) for consistent display
    val displayDateString = remember(dateString) {
        if (dateString.isBlank()) {
            ""
        } else {
            val displayFormat = java.text.SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
            val storageFormat = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            
            // Try to parse as display format first
            try {
                val date = displayFormat.parse(dateString)
                if (date != null) {
                    // Already in display format
                    dateString
                } else {
                    // Try storage format and convert to display format
                    try {
                        val parsedDate = storageFormat.parse(dateString)
                        parsedDate?.let { displayFormat.format(it) } ?: dateString
                    } catch (_: Exception) {
                        dateString
                    }
                }
            } catch (_: Exception) {
                // If display format parsing fails, try storage format
                try {
                    val parsedDate = storageFormat.parse(dateString)
                    parsedDate?.let { displayFormat.format(it) } ?: dateString
                } catch (_: Exception) {
                    // If both fail, return original string
                    dateString
                }
            }
        }
    }
    
    // Use TextFieldValue to preserve cursor position
    var textFieldValue by remember { 
        mutableStateOf(TextFieldValue(displayDateString, TextRange(displayDateString.length))) 
    }
    
    // Update textFieldValue when displayDateString changes externally (e.g., from date picker)
    LaunchedEffect(displayDateString) {
        // Only update if the text actually changed externally (not from user typing)
        if (textFieldValue.text != displayDateString) {
            textFieldValue = TextFieldValue(displayDateString, TextRange(displayDateString.length))
        }
    }
    
    // Convert date string to timestamp for date picker
    // Must validate that the date is within the allowed year range (1900-2025)
    val initialDateMillis = remember(displayDateString) {
        if (displayDateString.isBlank()) {
            // Default to 20 years ago if no date is set
            Calendar.getInstance().apply {
                add(Calendar.YEAR, -20)
            }.timeInMillis
        } else {
            try {
                val calendar = Calendar.getInstance()
                // Try to parse as dd.MM.yyyy (display format), then yyyy-MM-dd (storage format)
                val displayFormat = java.text.SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
                displayFormat.isLenient = false // Strict parsing
                var parsedDate = displayFormat.parse(displayDateString)
                if (parsedDate == null) {
                    val storageFormat = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    storageFormat.isLenient = false
                    parsedDate = storageFormat.parse(displayDateString)
                }
                
                // Validate the parsed date
                if (parsedDate != null) {
                    calendar.time = parsedDate
                    val year = calendar.get(Calendar.YEAR)
                    
                    // Check if year is within valid range (1900-2025)
                    if (year in 1900..2025) {
                        // Also check that the date is not in the future (for birthdays)
                        val now = Calendar.getInstance()
                        if (calendar.before(now) || calendar == now) {
                            return@remember calendar.timeInMillis
                        }
                    }
                }
                
                // If parsing failed or date is invalid, use default
                Calendar.getInstance().apply {
                    add(Calendar.YEAR, -20)
                }.timeInMillis
            } catch (_: Exception) {
                // If any parsing error occurs, use safe default
                Calendar.getInstance().apply {
                    add(Calendar.YEAR, -20)
                }.timeInMillis
            }
        }
    }
    
    // Format date input while preserving cursor position
    fun formatDateInputPreservingCursor(input: String, cursorPosition: Int): Pair<String, Int> {
        // Remove all non-digit characters
        val digitsOnly = input.filter { it.isDigit() }
        
        val formatted = when {
            digitsOnly.length <= 2 -> digitsOnly
            digitsOnly.length <= 4 -> "${digitsOnly.substring(0, 2)}.${digitsOnly.substring(2)}"
            else -> "${digitsOnly.substring(0, 2)}.${digitsOnly.substring(2, 4)}.${digitsOnly.substring(4, minOf(8, digitsOnly.length))}"
        }
        
        // Calculate new cursor position based on digit count
        val digitsBeforeCursor = input.substring(0, cursorPosition.coerceAtMost(input.length)).count { it.isDigit() }
        
        // Find position in formatted string after the same number of digits
        var newCursorPos = formatted.length
        var digitCount = 0
        for (i in formatted.indices) {
            if (formatted[i].isDigit()) {
                digitCount++
                if (digitCount == digitsBeforeCursor) {
                    // Position right after this digit
                    newCursorPos = i + 1
                } else if (digitCount > digitsBeforeCursor) {
                    // Position at this digit
                    newCursorPos = i
                    break
                }
            }
        }
        
        // If we're at the end, place cursor at the end
        if (digitsBeforeCursor >= digitsOnly.length) {
            newCursorPos = formatted.length
        }
        
        return Pair(formatted, newCursorPos.coerceIn(0, formatted.length))
    }
    
    OutlinedTextField(
        value = textFieldValue,
        onValueChange = { newValue ->
            val (formatted, newCursorPos) = formatDateInputPreservingCursor(newValue.text, newValue.selection.start)
            textFieldValue = TextFieldValue(formatted, TextRange(newCursorPos))
            onDateSelected(formatted)
        },
        label = label,
        placeholder = placeholder,
        isError = isError,
        supportingText = supportingText,
        trailingIcon = {
            IconButton(onClick = { showDatePicker = true }) {
                Icon(
                    Icons.Default.CalendarToday,
                    contentDescription = "Select date"
                )
            }
        },
        modifier = modifier.fillMaxWidth()
    )
    
    // Date picker dialog
    if (showDatePicker) {
        // Restrict to past dates only (birthdays can't be in the future)
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = initialDateMillis,
            yearRange = IntRange(1900, Calendar.getInstance().get(Calendar.YEAR))
        )
        
        EventDatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { dateMillis ->
                            // Ensure the selected date is not in the future
                            val now = Calendar.getInstance().timeInMillis
                            if (dateMillis <= now) {
                                // Convert to dd.MM.yyyy format
                                val calendar = Calendar.getInstance()
                                calendar.timeInMillis = dateMillis
                                val formattedDate = String.format(
                                    Locale.getDefault(),
                                    "%02d.%02d.%04d",
                                    calendar.get(Calendar.DAY_OF_MONTH),
                                    calendar.get(Calendar.MONTH) + 1, // Calendar.MONTH is 0-based
                                    calendar.get(Calendar.YEAR)
                                )
                                onDateSelected(formattedDate)
                            }
                        }
                        showDatePicker = false
                    }
                ) {
                    Text(stringResource(Res.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
