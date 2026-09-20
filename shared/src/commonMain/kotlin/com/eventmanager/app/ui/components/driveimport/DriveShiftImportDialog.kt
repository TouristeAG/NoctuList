package com.eventmanager.app.ui.components.driveimport

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Verified
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.eventmanager.app.data.drive.MatchConfidence
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.ShiftTime
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.components.DateTimePicker
import com.eventmanager.app.ui.components.SearchableDropdown
import com.eventmanager.app.ui.util.shiftTimeLabel
import org.jetbrains.compose.resources.stringResource

/**
 * Review step of the Drive import: one card per person found in the document, with the
 * same options as adding a shift by hand. Shared event settings sit above the list so
 * date/time and venue are clearly one decision for the whole import.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DriveShiftImportDialog(
    documentTitle: String,
    initialRows: List<DriveImportRow>,
    volunteers: List<Volunteer>,
    jobTypeConfigs: List<JobTypeConfig>,
    venues: List<VenueEntity>,
    importing: Boolean,
    errorMessage: String?,
    onImport: (List<Job>) -> Unit,
    onDismiss: () -> Unit,
) {
    var rows by remember(initialRows) { mutableStateOf(initialRows) }
    var useSharedDateTime by remember { mutableStateOf(true) }
    var sharedDateTime by remember(initialRows) {
        mutableStateOf(initialRows.firstOrNull()?.dateTime ?: System.currentTimeMillis())
    }
    var selectedVenueName by remember(venues) {
        mutableStateOf(venues.firstOrNull { it.isActive }?.name ?: "GROOVE")
    }
    var showVenueDropdown by remember { mutableStateOf(false) }

    val activeConfigs = remember(jobTypeConfigs) { jobTypeConfigs.filter { it.isActive } }
    val sortedVolunteers = remember(volunteers) { volunteers.sortedBy { it.name.lowercase() } }
    val unresolved = rows.count { !it.isReady }
    val duplicateVolunteerIds = remember(rows) {
        rows.mapNotNull { it.volunteer?.id }
            .groupingBy { it }
            .eachCount()
            .filter { it.value > 1 }
            .keys
    }

    fun update(key: String, transform: (DriveImportRow) -> DriveImportRow) {
        rows = rows.map { if (it.key == key) transform(it) else it }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Card(
            modifier = Modifier.fillMaxWidth(0.88f).fillMaxHeight(0.92f).padding(16.dp),
            shape = RoundedCornerShape(28.dp),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                ReviewHeader(
                    documentTitle = documentTitle,
                    peopleCount = rows.size,
                    onDismiss = onDismiss,
                )

                var contentVisible by remember { mutableStateOf(false) }
                LaunchedEffect(initialRows) { contentVisible = true }
                AnimatedVisibility(
                    visible = contentVisible,
                    enter = fadeIn(tween(280)),
                    modifier = Modifier.weight(1f),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp, vertical = 4.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (rows.isEmpty()) {
                            Text(
                                text = stringResource(Res.string.drive_import_no_people),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            SharedImportSettingsCard(
                                selectedVenueName = selectedVenueName,
                                showVenueDropdown = showVenueDropdown,
                                onVenueDropdownChange = { showVenueDropdown = it },
                                onVenueSelected = { selectedVenueName = it },
                                venues = venues,
                                useSharedDateTime = useSharedDateTime,
                                onUseSharedDateTimeChange = { useSharedDateTime = it },
                                sharedDateTime = sharedDateTime,
                                onSharedDateTimeChanged = { sharedDateTime = it },
                            )
                        }

                        rows.forEach { row ->
                            DriveImportProfileCard(
                                row = row,
                                volunteers = sortedVolunteers,
                                activeConfigs = activeConfigs,
                                showDateTime = !useSharedDateTime,
                                onVolunteerChanged = { volunteer -> update(row.key) { it.copy(volunteer = volunteer) } },
                                onJobTypeChanged = { config -> update(row.key) { it.copy(jobTypeConfig = config) } },
                                onShiftTimeChanged = { time -> update(row.key) { it.copy(shiftTime = time) } },
                                onDateTimeChanged = { millis -> update(row.key) { it.copy(dateTime = millis) } },
                                onNotesChanged = { notes -> update(row.key) { it.copy(notes = notes) } },
                                onRemove = { rows = rows.filterNot { it.key == row.key } },
                                isDuplicateVolunteer = row.volunteer?.id in duplicateVolunteerIds,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }

                ReviewFooter(
                    errorMessage = errorMessage,
                    unresolved = unresolved,
                    importing = importing,
                    canImport = rows.isNotEmpty() && unresolved == 0,
                    count = rows.size,
                    onDismiss = onDismiss,
                    onImport = {
                        val shared = sharedDateTime.takeIf { useSharedDateTime }
                        onImport(rows.mapNotNull { it.toJob(selectedVenueName, shared) })
                    },
                )
            }
        }
    }
}

@Composable
private fun ReviewHeader(documentTitle: String, peopleCount: Int, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(48.dp),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.drive_import_review_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            val subtitle = buildString {
                if (documentTitle.isNotBlank()) append(documentTitle)
                if (peopleCount > 0) {
                    if (isNotEmpty()) append("  ·  ")
                    append(stringResource(Res.string.drive_import_people_count, peopleCount))
                }
            }
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.close))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SharedImportSettingsCard(
    selectedVenueName: String,
    showVenueDropdown: Boolean,
    onVenueDropdownChange: (Boolean) -> Unit,
    onVenueSelected: (String) -> Unit,
    venues: List<VenueEntity>,
    useSharedDateTime: Boolean,
    onUseSharedDateTimeChange: (Boolean) -> Unit,
    sharedDateTime: Long,
    onSharedDateTimeChanged: (Long) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Default.Tune,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(Res.string.drive_import_shared_settings),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }

            ExposedDropdownMenuBox(
                expanded = showVenueDropdown,
                onExpandedChange = onVenueDropdownChange,
            ) {
                OutlinedTextField(
                    value = selectedVenueName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text(stringResource(Res.string.venue)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showVenueDropdown) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                ExposedDropdownMenu(
                    expanded = showVenueDropdown,
                    onDismissRequest = { onVenueDropdownChange(false) },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(Res.string.venue_all)) },
                        onClick = {
                            onVenueSelected("BOTH")
                            onVenueDropdownChange(false)
                        },
                    )
                    venues.filter { it.isActive }.forEach { venue ->
                        DropdownMenuItem(
                            text = { Text(venue.name) },
                            onClick = {
                                onVenueSelected(venue.name)
                                onVenueDropdownChange(false)
                            },
                        )
                    }
                }
            }

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(
                            Icons.Default.Schedule,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp),
                        )
                        Text(
                            text = stringResource(Res.string.shift_date_time),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    AnimatedVisibility(visible = useSharedDateTime) {
                        DateTimePicker(
                            selectedTimestamp = sharedDateTime,
                            onTimestampChanged = onSharedDateTimeChanged,
                            label = stringResource(Res.string.shift_date_time),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(Res.string.drive_import_apply_datetime_to_all),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                )
                                Text(
                                    text = stringResource(Res.string.drive_import_shared_datetime_hint),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = useSharedDateTime,
                                onCheckedChange = onUseSharedDateTimeChange,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DriveImportProfileCard(
    row: DriveImportRow,
    volunteers: List<Volunteer>,
    activeConfigs: List<JobTypeConfig>,
    showDateTime: Boolean,
    onVolunteerChanged: (Volunteer) -> Unit,
    onJobTypeChanged: (JobTypeConfig) -> Unit,
    onShiftTimeChanged: (ShiftTime) -> Unit,
    onDateTimeChanged: (Long) -> Unit,
    onNotesChanged: (String) -> Unit,
    onRemove: () -> Unit,
    isDuplicateVolunteer: Boolean,
) {
    var showJobTypeDropdown by remember { mutableStateOf(false) }
    var showShiftTimeDropdown by remember { mutableStateOf(false) }

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                MentionChip(label = row.label, modifier = Modifier.weight(0.34f))
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(Res.string.drive_import_maps_to),
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 14.dp).size(22.dp),
                )
                Column(modifier = Modifier.weight(0.66f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SearchableDropdown(
                        items = volunteers,
                        selectedItem = row.volunteer,
                        onItemSelected = onVolunteerChanged,
                        itemText = { volunteer -> volunteerDisplayName(volunteer) },
                        searchText = { "${it.name} ${it.lastNameAbbreviation} ${it.email}".trim() },
                        label = stringResource(Res.string.drive_import_noctulist_profile),
                        placeholder = stringResource(Res.string.search_volunteers_shift_placeholder),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    MatchConfidencePill(row)
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = stringResource(Res.string.drive_import_remove_row),
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            if (isDuplicateVolunteer) {
                Text(
                    text = stringResource(Res.string.drive_import_duplicate_volunteer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f))

            ExposedDropdownMenuBox(expanded = showJobTypeDropdown, onExpandedChange = { showJobTypeDropdown = it }) {
                OutlinedTextField(
                    value = row.jobTypeConfig?.name.orEmpty(),
                    onValueChange = {},
                    readOnly = true,
                    isError = row.jobTypeConfig == null,
                    label = { Text(stringResource(Res.string.shift_type)) },
                    placeholder = { Text(stringResource(Res.string.select_shift_type)) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showJobTypeDropdown) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                ExposedDropdownMenu(
                    expanded = showJobTypeDropdown,
                    onDismissRequest = { showJobTypeDropdown = false },
                    modifier = Modifier.heightIn(max = 280.dp),
                ) {
                    if (activeConfigs.isEmpty()) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.no_shift_types_available)) },
                            onClick = { showJobTypeDropdown = false },
                        )
                    } else {
                        activeConfigs.forEach { config ->
                            DropdownMenuItem(
                                text = { Text(config.name) },
                                onClick = {
                                    onJobTypeChanged(config)
                                    showJobTypeDropdown = false
                                },
                            )
                        }
                    }
                }
            }

            if (row.showsShiftTime) {
                ExposedDropdownMenuBox(expanded = showShiftTimeDropdown, onExpandedChange = { showShiftTimeDropdown = it }) {
                    OutlinedTextField(
                        value = shiftTimeLabel(row.shiftTime),
                        onValueChange = {},
                        readOnly = true,
                        label = { Text(stringResource(Res.string.shift_time)) },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = showShiftTimeDropdown) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp),
                    )
                    ExposedDropdownMenu(
                        expanded = showShiftTimeDropdown,
                        onDismissRequest = { showShiftTimeDropdown = false },
                    ) {
                        ShiftTime.entries.forEach { shiftTime ->
                            DropdownMenuItem(
                                text = { Text(shiftTimeLabel(shiftTime)) },
                                onClick = {
                                    onShiftTimeChanged(shiftTime)
                                    showShiftTimeDropdown = false
                                },
                            )
                        }
                    }
                }
            }

            if (showDateTime) {
                DateTimePicker(
                    selectedTimestamp = row.dateTime,
                    onTimestampChanged = onDateTimeChanged,
                    label = stringResource(Res.string.shift_date_time),
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            OutlinedTextField(
                value = row.notes,
                onValueChange = onNotesChanged,
                label = { Text(stringResource(Res.string.notes_optional)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
            )
        }
    }
}

@Composable
private fun MentionChip(label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier.padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(Res.string.drive_import_google_mention),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MatchConfidencePill(row: DriveImportRow) {
    val style = confidenceStyle(row)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(style.icon, contentDescription = null, tint = style.content, modifier = Modifier.size(15.dp))
        Text(
            text = style.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = style.content,
        )
        row.match.mention.email?.takeIf { it.isNotBlank() }?.let { email ->
            Text(
                text = "·  $email",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun confidenceStyle(row: DriveImportRow): ConfidenceStyle {
    val scheme = MaterialTheme.colorScheme
    return when {
        row.volunteer == null && row.match.confidence == MatchConfidence.UNMATCHED -> ConfidenceStyle(
            label = stringResource(Res.string.drive_import_match_none),
            icon = Icons.Default.ErrorOutline,
            content = scheme.error,
        )
        row.volunteer == null -> ConfidenceStyle(
            label = stringResource(Res.string.drive_import_match_ambiguous),
            icon = Icons.Default.HelpOutline,
            content = scheme.error,
        )
        row.volunteer?.id != row.match.selectedVolunteer?.id -> ConfidenceStyle(
            label = stringResource(Res.string.drive_import_match_manual),
            icon = Icons.Default.Person,
            content = scheme.primary,
        )
        row.match.confidence == MatchConfidence.CONFIRMED -> ConfidenceStyle(
            label = stringResource(Res.string.drive_import_match_confirmed),
            icon = Icons.Default.CheckCircle,
            content = scheme.tertiary,
        )
        row.match.confidence == MatchConfidence.LIKELY -> ConfidenceStyle(
            label = stringResource(Res.string.drive_import_match_likely),
            icon = Icons.Outlined.Verified,
            content = scheme.secondary,
        )
        else -> ConfidenceStyle(
            label = stringResource(Res.string.drive_import_match_possible),
            icon = Icons.Default.HelpOutline,
            content = scheme.onSurfaceVariant,
        )
    }
}

private data class ConfidenceStyle(
    val label: String,
    val icon: ImageVector,
    val content: Color,
)

@Composable
private fun ReviewFooter(
    errorMessage: String?,
    unresolved: Int,
    importing: Boolean,
    canImport: Boolean,
    count: Int,
    onDismiss: () -> Unit,
    onImport: () -> Unit,
) {
    HorizontalDivider()
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (errorMessage != null) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (unresolved > 0) {
            Text(
                text = stringResource(Res.string.drive_import_unresolved_warning, unresolved),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f), enabled = !importing) {
                Text(stringResource(Res.string.cancel))
            }
            Button(
                onClick = onImport,
                enabled = !importing && canImport,
                modifier = Modifier.weight(1f),
            ) {
                if (importing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Spacer(Modifier.width(8.dp))
                }
                Text(stringResource(Res.string.drive_import_confirm, count))
            }
        }
    }
}

private fun volunteerDisplayName(volunteer: Volunteer): String =
    if (volunteer.lastNameAbbreviation.isNotEmpty()) {
        "${volunteer.name} (${volunteer.lastNameAbbreviation})"
    } else {
        volunteer.name
    }
