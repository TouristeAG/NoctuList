package com.eventmanager.app.ui.screens

import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import org.jetbrains.compose.resources.stringResource

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import com.eventmanager.app.data.models.*
import com.eventmanager.app.data.models.NovaJobType
import com.eventmanager.app.data.utils.DateTimeUtils
import com.eventmanager.app.data.sync.DateFormatUtils
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.ui.components.SearchBarWithFilter
import com.eventmanager.app.ui.components.SearchableDropdown
import com.eventmanager.app.ui.components.DateTimePicker
import com.eventmanager.app.ui.utils.*
import com.eventmanager.app.ui.util.shiftTimeLabel
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import com.eventmanager.app.ui.components.driveimport.DriveShiftImportEntry
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun JobTrackingScreen(
    jobs: List<Job>,
    volunteers: List<Volunteer>,
    jobTypeConfigs: List<JobTypeConfig>,
    venues: List<VenueEntity>,
    onAddJob: (Job) -> Unit,
    onUpdateJob: (Job) -> Unit,
    onDeleteJob: (Job) -> Unit,
    scrollBehavior: String = SettingsManager.FULL_SCROLL,
    driveImportAvailable: Boolean = false,
    onImportJobs: (List<Job>, (Result<Int>) -> Unit) -> Unit = { _, callback -> callback(Result.success(0)) },
) {
    val platformContext = LocalPlatformContext.current
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<Job?>(null) }
    var selectedVenue by remember { mutableStateOf<Venue?>(null) }
    var selectedVenueName by remember { mutableStateOf<String?>(null) }
    var _selectedVolunteer by remember { mutableStateOf<Volunteer?>(null) }
    var searchText by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<String?>(null) }

    val isCompact = isCompactScreen()
    val responsivePadding = getResponsivePadding()
    val responsiveSpacing = getResponsiveSpacing()

    // Create a map for O(1) volunteer lookup instead of O(n) find operations
    val volunteersMap = remember(volunteers) {
        volunteers.associateBy { it.id }
    }

    // Memoize active job type configs to avoid filtering on every recomposition
    val activeJobTypeConfigs = remember(jobTypeConfigs) {
        jobTypeConfigs.filter { it.isActive }
    }
    
    // Memoize volunteer lookup map for better performance
    val volunteerMap = remember(volunteers) {
        volunteers.associateBy { it.id }
    }
    
    // Memoize filtered jobs once, reused in both layouts
    val filteredJobs = remember(jobs, volunteerMap, selectedVenueName, searchText, selectedFilter) {
        val lowerSearchText = searchText.lowercase()
        jobs.filter { job ->
            val volunteer = volunteerMap[job.volunteerId]
            val matchesVenue = if (selectedVenueName == null) {
                true  // No filter selected, show all
            } else if (selectedVenueName == "BOTH") {
                job.venueName == "BOTH"  // Show only jobs marked as "BOTH"
            } else {
                job.venueName == selectedVenueName || job.venueName == "BOTH"  // Show matching or BOTH
            }
            val matchesSearch = searchText.isEmpty() || 
                volunteer?.name?.lowercase()?.contains(lowerSearchText) == true ||
                volunteer?.nfcCardUid?.lowercase()?.contains(lowerSearchText) == true ||
                job.notes.lowercase().contains(lowerSearchText) ||
                job.jobTypeName.lowercase().contains(lowerSearchText)
            val matchesFilter = selectedFilter?.let { filter ->
                job.jobTypeName == filter
            } ?: true
            matchesVenue && matchesSearch && matchesFilter
        }
    }
    
    when (scrollBehavior) {
        SettingsManager.HEADER_PINNED -> {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(responsivePadding)
            ) {
                // Header
                if (isCompact) {
                    // Stack vertically on phones
                    Column(
                        verticalArrangement = Arrangement.spacedBy(responsiveSpacing)
                    ) {
                        Text(
                            text = stringResource(Res.string.shifts_title),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        // Venue Filter
                        val venueFilterOptions = generateVenueFilterOptions(venues)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            venueFilterOptions.forEach { venueOption ->
                                FilterChip(
                                    onClick = { 
                                        selectedVenueName = if (selectedVenueName == venueOption.venueName) null else venueOption.venueName
                                    },
                                    label = { Text(venueOption.displayName) },
                                    selected = selectedVenueName == venueOption.venueName
                                )
                            }
                        }
                        
                        Button(
                            onClick = { showAddDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(getResponsiveButtonHeight())
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(stringResource(Res.string.add_shift))
                        }
                    }
                } else {
                    // Tablet layout: Title + button on first row, filters below
                    Column(verticalArrangement = Arrangement.spacedBy(responsiveSpacing)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(Res.string.shift_tracking),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            
                            Button(
                                onClick = { showAddDialog = true },
                                modifier = Modifier.height(getResponsiveButtonHeight())
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(Res.string.add_shift))
                            }
                        }
                        
                        // Venue Filter below title
                        val venueFilterOptions = generateVenueFilterOptions(venues)
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            venueFilterOptions.forEach { venueOption ->
                                FilterChip(
                                    onClick = { 
                                        selectedVenueName = if (selectedVenueName == venueOption.venueName) null else venueOption.venueName
                                    },
                                    label = { Text(venueOption.displayName) },
                                    selected = selectedVenueName == venueOption.venueName
                                )
                            }
                        }
                    }
                }
                
                Spacer(modifier = Modifier.height(responsiveSpacing))
                
                // Search and Filter Section
                SearchBarWithFilter(
                    searchText = searchText,
                    onSearchTextChange = { searchText = it },
                    placeholder = stringResource(Res.string.search_shifts_placeholder),
                    filterOptions = activeJobTypeConfigs.map { it.name },
                    selectedFilter = selectedFilter,
                    onFilterChange = { selectedFilter = it }
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                Text(
                    text = "${filteredJobs.size} of ${jobs.size} shifts",
                    style = getResponsiveBodyTypography(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 8.dp))
                
                // Shifts list - Use LazyColumn for lazy loading and better performance
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(if (isCompact) 6.dp else 8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(
                        items = filteredJobs,
                        key = { job -> job.id }
                    ) { job ->
                        JobCard(
                            job = job,
                            volunteer = job.volunteerId?.let { volunteersMap[it] },
                            venues = venues,
                            onUpdate = { showEditDialog = job },
                            onDelete = onDeleteJob
                        )
                    }
                }
            }
        }
        SettingsManager.STICKY_FILTERS -> {
            // Page scrolls but filters become sticky at the top
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(responsivePadding),
                verticalArrangement = Arrangement.spacedBy(if (isCompact) 6.dp else 8.dp)
            ) {
                // Header section (scrolls away)
                item {
                    if (isCompact) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(responsiveSpacing)
                        ) {
                            Text(
                                text = stringResource(Res.string.shifts_title),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            
                            val venueFilterOptions = generateVenueFilterOptions(venues)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            ) {
                                venueFilterOptions.forEach { venueOption ->
                                    FilterChip(
                                        onClick = { 
                                            selectedVenueName = if (selectedVenueName == venueOption.venueName) null else venueOption.venueName
                                        },
                                        label = { Text(venueOption.displayName) },
                                        selected = selectedVenueName == venueOption.venueName
                                    )
                                }
                            }
                            
                            Button(
                                onClick = { showAddDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(getResponsiveButtonHeight())
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(Res.string.add_shift))
                            }
                        }
                    } else {
                        // Tablet layout: Title + button on first row, filters below
                        Column(verticalArrangement = Arrangement.spacedBy(responsiveSpacing)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(Res.string.shift_tracking),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                
                                Button(
                                    onClick = { showAddDialog = true },
                                    modifier = Modifier.height(getResponsiveButtonHeight())
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(Res.string.add_shift))
                                }
                            }
                            
                            // Venue Filter below title
                            val venueFilterOptions = generateVenueFilterOptions(venues)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            ) {
                                venueFilterOptions.forEach { venueOption ->
                                    FilterChip(
                                        onClick = { 
                                            selectedVenueName = if (selectedVenueName == venueOption.venueName) null else venueOption.venueName
                                        },
                                        label = { Text(venueOption.displayName) },
                                        selected = selectedVenueName == venueOption.venueName
                                    )
                                }
                            }
                        }
                    }
                }
                
                item {
                    Spacer(modifier = Modifier.height(responsiveSpacing))
                }
                
                // Sticky filter section - becomes pinned when scrolled to top
                stickyHeader {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(bottom = 8.dp)
                    ) {
                        SearchBarWithFilter(
                            searchText = searchText,
                            onSearchTextChange = { searchText = it },
                            placeholder = stringResource(Res.string.search_shifts_placeholder),
                            filterOptions = activeJobTypeConfigs.map { it.name },
                            selectedFilter = selectedFilter,
                            onFilterChange = { selectedFilter = it }
                        )
                    }
                }
                
                // Count text - scrolls with content, not sticky
                item {
                    Column {
                        Text(
                            text = "${filteredJobs.size} of ${jobs.size} shifts",
                            style = getResponsiveBodyTypography(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 8.dp))
                    }
                }
                
                items(
                    items = filteredJobs,
                    key = { job -> job.id }
                ) { job ->
                    JobCard(
                        job = job,
                        volunteer = job.volunteerId?.let { volunteersMap[it] },
                        venues = venues,
                        onUpdate = { showEditDialog = job },
                        onDelete = onDeleteJob
                    )
                }
            }
        }
        else -> {
            // FULL_SCROLL: Whole page (header + list) scrolls together
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(responsivePadding),
                verticalArrangement = Arrangement.spacedBy(if (isCompact) 6.dp else 8.dp)
            ) {
                item {
                    if (isCompact) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(responsiveSpacing)
                        ) {
                            Text(
                                text = stringResource(Res.string.shifts_title),
                                style = MaterialTheme.typography.headlineMedium,
                                fontWeight = FontWeight.Bold
                            )
                            
                            val venueFilterOptions = generateVenueFilterOptions(venues)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            ) {
                                venueFilterOptions.forEach { venueOption ->
                                    FilterChip(
                                        onClick = { 
                                            selectedVenueName = if (selectedVenueName == venueOption.venueName) null else venueOption.venueName
                                        },
                                        label = { Text(venueOption.displayName) },
                                        selected = selectedVenueName == venueOption.venueName
                                    )
                                }
                            }
                            
                            Button(
                                onClick = { showAddDialog = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(getResponsiveButtonHeight())
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(stringResource(Res.string.add_shift))
                            }
                        }
                    } else {
                        // Tablet layout: Title + button on first row, filters below
                        Column(verticalArrangement = Arrangement.spacedBy(responsiveSpacing)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = stringResource(Res.string.shift_tracking),
                                    style = MaterialTheme.typography.headlineMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                
                                Button(
                                    onClick = { showAddDialog = true },
                                    modifier = Modifier.height(getResponsiveButtonHeight())
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(stringResource(Res.string.add_shift))
                                }
                            }
                            
                            // Venue Filter below title
                            val venueFilterOptions = generateVenueFilterOptions(venues)
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            ) {
                                venueFilterOptions.forEach { venueOption ->
                                    FilterChip(
                                        onClick = { 
                                            selectedVenueName = if (selectedVenueName == venueOption.venueName) null else venueOption.venueName
                                        },
                                        label = { Text(venueOption.displayName) },
                                        selected = selectedVenueName == venueOption.venueName
                                    )
                                }
                            }
                        }
                    }
                }
                
                item {
                    Spacer(modifier = Modifier.height(responsiveSpacing))
                }
                
                item {
                    SearchBarWithFilter(
                        searchText = searchText,
                        onSearchTextChange = { searchText = it },
                        placeholder = stringResource(Res.string.search_shifts_placeholder),
                        filterOptions = activeJobTypeConfigs.map { it.name },
                        selectedFilter = selectedFilter,
                        onFilterChange = { selectedFilter = it }
                    )
                }
                
                item {
                    Spacer(modifier = Modifier.height(16.dp))
                }
                
                item {
                    Text(
                        text = "${filteredJobs.size} of ${jobs.size} shifts",
                        style = getResponsiveBodyTypography(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                items(
                    items = filteredJobs,
                    key = { job -> job.id }
                ) { job ->
                    JobCard(
                        job = job,
                        volunteer = job.volunteerId?.let { volunteersMap[it] },
                        venues = venues,
                        onUpdate = { showEditDialog = job },
                        onDelete = onDeleteJob
                    )
                }
            }
        }
    }
    
    // Add Shift Dialog
    if (showAddDialog) {
        AddJobDialog(
            volunteers = volunteers,
            jobTypeConfigs = jobTypeConfigs,
            venues = venues,
            onDismiss = { showAddDialog = false },
            onConfirm = { job ->
                onAddJob(job)
                showAddDialog = false
            },
            driveImportAvailable = driveImportAvailable,
            onImportJobs = onImportJobs,
        )
    }
    
    // Edit Shift Dialog
    showEditDialog?.let { job ->
        EditJobDialog(
            job = job,
            volunteers = volunteers,
            jobTypeConfigs = jobTypeConfigs,
            venues = venues,
            onDismiss = { showEditDialog = null },
            onConfirm = { updatedJob ->
                onUpdateJob(updatedJob)
                showEditDialog = null
            }
        )
    }
}

@Composable
fun JobCard(
    job: Job,
    volunteer: Volunteer?,
    venues: List<VenueEntity>,
    onUpdate: (Job) -> Unit,
    onDelete: (Job) -> Unit,
    modifier: Modifier = Modifier
) {
    val platformContext = LocalPlatformContext.current
    val isCompact = isCompactScreen()
    val responsivePadding = getResponsiveCardPadding()
    
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = getResponsiveCardElevation())
    ) {
        Column(
            modifier = Modifier.padding(responsivePadding)
        ) {
            // Header with job info
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = volunteer?.name ?: stringResource(Res.string.unknown_volunteer),
                        style = getResponsiveTitleTypography(),
                        fontWeight = FontWeight.SemiBold
                    )
                    
                    Text(
                        text = "${job.jobTypeName} • ${getVenueDisplayString(job.venueName, venues)}",
                        style = getResponsiveBodyTypography(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Text(
                        text = formatDate(job.date, platformContext),
                        style = if (isCompact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    // Shift time badge
                    AssistChip(
                        onClick = { },
                        label = { 
                            Text(
                                shiftTimeLabel(job.shiftTime),
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        colors = AssistChipDefaults.assistChipColors(
                            containerColor = if (job.shiftTime == ShiftTime.AFTER_MIDNIGHT) {
                                MaterialTheme.colorScheme.secondaryContainer
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            }
                        )
                    )
                }
            }
            
            if (job.notes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 8.dp))
                Text(
                    text = job.notes,
                    style = if (isCompact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            Spacer(modifier = Modifier.height(if (isCompact) 6.dp else 8.dp))
            
            // Action buttons
            if (isCompact) {
                // Stack vertically on phones
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onUpdate(job) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(Res.string.edit))
                    }
                    
                    OutlinedButton(
                        onClick = { onDelete(job) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(Res.string.delete))
                    }
                }
            } else {
                // Row layout on tablets
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { onUpdate(job) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(Res.string.edit))
                    }
                    
                    OutlinedButton(
                        onClick = { onDelete(job) },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(Res.string.delete))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddJobDialog(
    volunteers: List<Volunteer>,
    jobTypeConfigs: List<JobTypeConfig>,
    venues: List<VenueEntity>,
    onDismiss: () -> Unit,
    onConfirm: (Job) -> Unit,
    driveImportAvailable: Boolean = false,
    onImportJobs: (List<Job>, (Result<Int>) -> Unit) -> Unit = { _, callback -> callback(Result.success(0)) },
) {
    val platformContext = LocalPlatformContext.current
    var selectedVolunteer by remember { mutableStateOf<Volunteer?>(null) }
    var selectedJobTypeConfig by remember { mutableStateOf<JobTypeConfig?>(null) }
    var selectedVenueName by remember { mutableStateOf<String?>(null) }
    var selectedDateTime by remember { mutableStateOf(System.currentTimeMillis()) }
    var selectedShiftTime by remember { mutableStateOf(ShiftTime.BEFORE_MIDNIGHT) }
    var notes by remember { mutableStateOf("") }
    var showVolunteerDropdown by remember { mutableStateOf(false) }
    var showJobTypeDropdown by remember { mutableStateOf(false) }
    var showVenueDropdown by remember { mutableStateOf(false) }
    var showShiftTimeDropdown by remember { mutableStateOf(false) }
    
    // Filter active job type configs
    val activeJobTypeConfigs = jobTypeConfigs.filter { it.isActive }
    val volunteersAlphabetical = remember(volunteers) { volunteersSortedAlphabetically(volunteers) }

    val isCompact = isCompactScreen()
    val scrollState = rememberScrollState()
    val jobTypeMenuScrollState = rememberScrollState()
    val venueMenuScrollState = rememberScrollState()
    val isTabletDevice = isTablet()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = !isTabletDevice
        )
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (isTabletDevice) {
                        Modifier
                            .fillMaxSize()
                            .padding(getTabletDialogScreenEdgeInset())
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.9f)
                            .padding(16.dp)
                    }
                )
        ) {
            Card(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
            Text(
                text = if (isCompact) stringResource(Res.string.add_shift) else stringResource(Res.string.add_new_shift),
                        style = if (isTabletDevice) getTabletConstrainedTitleTypography() else getResponsiveTypography(),
                        fontWeight = FontWeight.Bold
                    )
                    
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.close))
                    }
                }
                
                // Scrollable Content
            Column(
                modifier = Modifier
                        .weight(1f)
                    .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(if (isCompact) 12.dp else 16.dp)
            ) {
                // Bulk import from a Google Docs/Sheets planning document or meeting minutes.
                if (driveImportAvailable) {
                    DriveShiftImportEntry(
                        volunteers = volunteersAlphabetical,
                        jobTypeConfigs = jobTypeConfigs,
                        venues = venues,
                        defaultDateTime = selectedDateTime,
                        onImport = onImportJobs,
                        onImported = onDismiss,
                    )
                }

                // Volunteer selection with search
                SearchableDropdown(
                    items = volunteersAlphabetical,
                    selectedItem = selectedVolunteer,
                    onItemSelected = { volunteer -> selectedVolunteer = volunteer },
                    itemText = { volunteer -> 
                        if (volunteer.lastNameAbbreviation.isNotEmpty()) {
                            "${volunteer.name} (${volunteer.lastNameAbbreviation})"
                        } else {
                            volunteer.name
                        }
                    },
                    searchText = { volunteer -> 
                        "${volunteer.name} ${volunteer.lastNameAbbreviation} ${volunteer.nfcCardUid}".trim()
                    },
                    label = stringResource(Res.string.volunteer),
                    placeholder = stringResource(Res.string.search_volunteers_shift_placeholder),
                    modifier = Modifier.fillMaxWidth()
                )

                // Job type selection
                ExposedDropdownMenuBox(
                    expanded = showJobTypeDropdown,
                    onExpandedChange = { showJobTypeDropdown = it }
                ) {
                    OutlinedTextField(
                        value = selectedJobTypeConfig?.name ?: "",
                        onValueChange = { },
                        readOnly = true,
                        label = { Text(stringResource(Res.string.shift_type)) },
                        placeholder = { Text(stringResource(Res.string.select_shift_type)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = showJobTypeDropdown)
                        },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = showJobTypeDropdown,
                        onDismissRequest = { showJobTypeDropdown = false },
                        modifier = Modifier.heightIn(max = 280.dp)
                    ) {
                        if (activeJobTypeConfigs.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.no_shift_types_available)) },
                                onClick = { showJobTypeDropdown = false }
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp)
                                    .verticalScroll(jobTypeMenuScrollState)
                            ) {
                                activeJobTypeConfigs.forEach { config ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(config.name)
                                                if (config.description.isNotEmpty()) {
                                                    Text(
                                                        text = config.description,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            selectedJobTypeConfig = config
                                            showJobTypeDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Venue selection
                ExposedDropdownMenuBox(
                    expanded = showVenueDropdown,
                    onExpandedChange = { showVenueDropdown = it }
                ) {
                    OutlinedTextField(
                        value = selectedVenueName ?: stringResource(Res.string.venue),
                        onValueChange = { },
                        readOnly = true,
                        label = { Text(stringResource(Res.string.venue)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = showVenueDropdown)
                        },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = showVenueDropdown,
                        onDismissRequest = { showVenueDropdown = false },
                        modifier = Modifier.heightIn(max = 280.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .verticalScroll(venueMenuScrollState)
                        ) {
                            val allOptionText = stringResource(Res.string.venue_all)
                            DropdownMenuItem(
                                text = { Text(allOptionText) },
                                onClick = {
                                    selectedVenueName = "BOTH"
                                    showVenueDropdown = false
                                }
                            )

                            // Add individual venues (only active ones)
                            venues.filter { it.isActive }.forEach { venue ->
                                DropdownMenuItem(
                                    text = { Text(venue.name) },
                                    onClick = {
                                        selectedVenueName = venue.name
                                        showVenueDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Shift time selection (only show for DEFAULT_SHIFT that requires it)
                val showShiftTimeForAdd = selectedJobTypeConfig?.let {
                    it.requiresShiftTime && it.novaJobType == NovaJobType.DEFAULT_SHIFT
                } ?: false
                if (showShiftTimeForAdd) {
                    ExposedDropdownMenuBox(
                        expanded = showShiftTimeDropdown,
                        onExpandedChange = { showShiftTimeDropdown = it }
                    ) {
                        OutlinedTextField(
                            value = shiftTimeLabel(selectedShiftTime),
                            onValueChange = { },
                            readOnly = true,
                            label = { Text(stringResource(Res.string.shift_time)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = showShiftTimeDropdown)
                            },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        
                        ExposedDropdownMenu(
                            expanded = showShiftTimeDropdown,
                            onDismissRequest = { showShiftTimeDropdown = false }
                        ) {
                            ShiftTime.values().forEach { shiftTime ->
                                DropdownMenuItem(
                                    text = { Text(shiftTimeLabel(shiftTime)) },
                                    onClick = {
                                        selectedShiftTime = shiftTime
                                        showShiftTimeDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Date and Time picker
                DateTimePicker(
                    selectedTimestamp = selectedDateTime,
                    onTimestampChanged = { selectedDateTime = it },
                    label = stringResource(Res.string.shift_date_time),
                    modifier = Modifier.fillMaxWidth()
                )

                // Notes
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(Res.string.notes_optional)) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
                
                // Action Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(Res.string.cancel))
                    }
                    
                    Button(
                onClick = {
                    selectedVolunteer?.let { volunteer ->
                        selectedJobTypeConfig?.let { config ->
                            val effectiveShiftTime = if (config.novaJobType == NovaJobType.DEFAULT_SHIFT && config.requiresShiftTime)
                                selectedShiftTime else ShiftTime.BEFORE_MIDNIGHT
                            val job = Job(
                                volunteerId = volunteer.id,
                                jobType = JobType.OTHER,
                                jobTypeName = config.name,
                                venueName = selectedVenueName ?: venues.firstOrNull { it.isActive }?.name ?: "GROOVE",
                                date = selectedDateTime,
                                shiftTime = effectiveShiftTime,
                                notes = notes
                            )
                            onConfirm(job)
                        }
                    }
                },
                        enabled = selectedVolunteer != null && selectedJobTypeConfig != null,
                        modifier = Modifier.weight(1f)
            ) {
                Text(stringResource(Res.string.add_job))
            }
                }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditJobDialog(
    job: Job,
    volunteers: List<Volunteer>,
    jobTypeConfigs: List<JobTypeConfig>,
    venues: List<VenueEntity>,
    onDismiss: () -> Unit,
    onConfirm: (Job) -> Unit
) {
    val platformContext = LocalPlatformContext.current
    var selectedVolunteer by remember { mutableStateOf(volunteers.find { it.id == job.volunteerId }) }
    var selectedJobTypeConfig by remember { mutableStateOf(jobTypeConfigs.find { it.name == job.jobTypeName }) }
    var selectedVenueName by remember { mutableStateOf(job.venueName) }
    var selectedShiftTime by remember { mutableStateOf(job.shiftTime) }
    var selectedDateTime by remember { mutableStateOf(job.date) }
    var notes by remember { mutableStateOf(job.notes) }
    var showVolunteerDropdown by remember { mutableStateOf(false) }
    var showJobTypeDropdown by remember { mutableStateOf(false) }
    var showVenueDropdown by remember { mutableStateOf(false) }
    var showShiftTimeDropdown by remember { mutableStateOf(false) }
    
    // Filter active job type configs
    val activeJobTypeConfigs = jobTypeConfigs.filter { it.isActive }
    val volunteersAlphabetical = remember(volunteers) { volunteersSortedAlphabetically(volunteers) }
    // Keep current selection visible even if that type was deactivated
    val jobTypesForEditDropdown = remember(activeJobTypeConfigs, selectedJobTypeConfig) {
        val cur = selectedJobTypeConfig
        if (cur != null && activeJobTypeConfigs.none { it.name == cur.name }) {
            listOf(cur) + activeJobTypeConfigs
        } else {
            activeJobTypeConfigs
        }
    }

    val isCompact = isCompactScreen()
    val scrollState = rememberScrollState()
    val jobTypeMenuScrollState = rememberScrollState()
    val venueMenuScrollState = rememberScrollState()
    val isTabletDevice = isTablet()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = !isTabletDevice
        )
    ) {
        Box(
            modifier = Modifier
                .then(
                    if (isTabletDevice) {
                        Modifier
                            .fillMaxSize()
                            .padding(getTabletDialogScreenEdgeInset())
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.9f)
                            .padding(16.dp)
                    }
                )
        ) {
            Card(
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    // Header
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                    Text(
                        text = if (isCompact) stringResource(Res.string.edit_shift) else stringResource(Res.string.edit_shift_details),
                        style = if (isTabletDevice) getTabletConstrainedTitleTypography() else getResponsiveTypography(),
                        fontWeight = FontWeight.Bold
                    )
                    
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.close))
                    }
                }
                
                // Scrollable Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(if (isCompact) 12.dp else 16.dp)
                ) {
                // Volunteer selection with search
                SearchableDropdown(
                    items = volunteersAlphabetical,
                    selectedItem = selectedVolunteer,
                    onItemSelected = { volunteer -> selectedVolunteer = volunteer },
                    itemText = { volunteer -> 
                        if (volunteer.lastNameAbbreviation.isNotEmpty()) {
                            "${volunteer.name} (${volunteer.lastNameAbbreviation})"
                        } else {
                            volunteer.name
                        }
                    },
                    searchText = { volunteer -> 
                        "${volunteer.name} ${volunteer.lastNameAbbreviation} ${volunteer.nfcCardUid}".trim()
                    },
                    label = stringResource(Res.string.volunteer),
                    placeholder = stringResource(Res.string.search_volunteers_shift_placeholder),
                    modifier = Modifier.fillMaxWidth()
                )

                // Job type selection
                ExposedDropdownMenuBox(
                    expanded = showJobTypeDropdown,
                    onExpandedChange = { showJobTypeDropdown = it }
                ) {
                    OutlinedTextField(
                        value = selectedJobTypeConfig?.name ?: "",
                        onValueChange = { },
                        readOnly = true,
                        label = { Text(stringResource(Res.string.shift_type)) },
                        placeholder = { Text(stringResource(Res.string.select_shift_type)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = showJobTypeDropdown)
                        },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = showJobTypeDropdown,
                        onDismissRequest = { showJobTypeDropdown = false },
                        modifier = Modifier.heightIn(max = 280.dp)
                    ) {
                        if (jobTypesForEditDropdown.isEmpty()) {
                            DropdownMenuItem(
                                text = { Text(stringResource(Res.string.no_shift_types_available)) },
                                onClick = { showJobTypeDropdown = false }
                            )
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp)
                                    .verticalScroll(jobTypeMenuScrollState)
                            ) {
                                jobTypesForEditDropdown.forEach { config ->
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(config.name)
                                                if (config.description.isNotEmpty()) {
                                                    Text(
                                                        text = config.description,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                            }
                                        },
                                        onClick = {
                                            selectedJobTypeConfig = config
                                            showJobTypeDropdown = false
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Venue selection
                ExposedDropdownMenuBox(
                    expanded = showVenueDropdown,
                    onExpandedChange = { showVenueDropdown = it }
                ) {
                    OutlinedTextField(
                        value = selectedVenueName,
                        onValueChange = { },
                        readOnly = true,
                        label = { Text(stringResource(Res.string.venue)) },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = showVenueDropdown)
                        },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    
                    ExposedDropdownMenu(
                        expanded = showVenueDropdown,
                        onDismissRequest = { showVenueDropdown = false },
                        modifier = Modifier.heightIn(max = 280.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 280.dp)
                                .verticalScroll(venueMenuScrollState)
                        ) {
                            val allOptionText = stringResource(Res.string.venue_all)
                            DropdownMenuItem(
                                text = { Text(allOptionText) },
                                onClick = {
                                    selectedVenueName = "BOTH"
                                    showVenueDropdown = false
                                }
                            )

                            // Add individual venues (only active ones)
                            venues.filter { it.isActive }.forEach { venue ->
                                DropdownMenuItem(
                                    text = { Text(venue.name) },
                                    onClick = {
                                        selectedVenueName = venue.name
                                        showVenueDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Shift time selection (only show for DEFAULT_SHIFT that requires it)
                val showShiftTimeForEdit = selectedJobTypeConfig?.let {
                    it.requiresShiftTime && it.novaJobType == NovaJobType.DEFAULT_SHIFT
                } ?: false
                if (showShiftTimeForEdit) {
                    ExposedDropdownMenuBox(
                        expanded = showShiftTimeDropdown,
                        onExpandedChange = { showShiftTimeDropdown = it }
                    ) {
                        OutlinedTextField(
                            value = shiftTimeLabel(selectedShiftTime),
                            onValueChange = { },
                            readOnly = true,
                            label = { Text(stringResource(Res.string.shift_time)) },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = showShiftTimeDropdown)
                            },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                        )
                        
                        ExposedDropdownMenu(
                            expanded = showShiftTimeDropdown,
                            onDismissRequest = { showShiftTimeDropdown = false }
                        ) {
                            ShiftTime.values().forEach { shiftTime ->
                                DropdownMenuItem(
                                    text = { Text(shiftTimeLabel(shiftTime)) },
                                    onClick = {
                                        selectedShiftTime = shiftTime
                                        showShiftTimeDropdown = false
                                    }
                                )
                            }
                        }
                    }
                }

                // Date and Time picker
                DateTimePicker(
                    selectedTimestamp = selectedDateTime,
                    onTimestampChanged = { selectedDateTime = it },
                    label = stringResource(Res.string.shift_date_time),
                    modifier = Modifier.fillMaxWidth()
                )

                // Notes
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(Res.string.notes_optional)) },
                    modifier = Modifier.fillMaxWidth()
                )
                }
                
                // Action Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(Res.string.cancel))
                    }
                    
                    Button(
                        onClick = {
                            selectedVolunteer?.let { volunteer ->
                                selectedJobTypeConfig?.let { config ->
                                    val effectiveShiftTime = if (config.novaJobType == NovaJobType.DEFAULT_SHIFT && config.requiresShiftTime)
                                        selectedShiftTime else ShiftTime.BEFORE_MIDNIGHT
                                    val updatedJob = job.copy(
                                        volunteerId = volunteer.id,
                                        jobTypeName = config.name,
                                        venueName = selectedVenueName ?: job.venueName,
                                        date = selectedDateTime,
                                        shiftTime = effectiveShiftTime,
                                        notes = notes
                                    )
                                    onConfirm(updatedJob)
                                }
                            }
                        },
                        enabled = selectedVolunteer != null && selectedJobTypeConfig != null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(Res.string.update_job))
                    }
                }
                }
            }
        }
    }
}

private fun formatDate(timestamp: Long, platformContext: com.eventmanager.app.platform.PlatformContext): String {
    return DateFormatUtils.formatDateTime(timestamp, platformContext)
}

private fun volunteersSortedAlphabetically(volunteers: List<Volunteer>): List<Volunteer> {
    val collator = Collator.getInstance().apply { strength = Collator.SECONDARY }
    return volunteers.sortedWith(
        compareBy<Volunteer, String>(collator) { it.name.trim() }
            .thenBy(collator) { it.lastNameAbbreviation.trim() }
    )
}

