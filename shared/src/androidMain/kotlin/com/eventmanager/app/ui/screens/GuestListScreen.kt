package com.eventmanager.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.eventmanager.app.data.models.*
import com.eventmanager.app.data.remote.MultiOrgMerge
import com.eventmanager.app.data.remote.resolvedProfilePhotoPath
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.ui.components.SearchBarWithFilter
import com.eventmanager.app.ui.components.VolunteerBenefitsPanel
import com.eventmanager.app.ui.components.GuestDetailPanel
import com.eventmanager.app.ui.components.GuestBarDiscountField
import com.eventmanager.app.ui.components.ProfilePhotoFormPicker
import com.eventmanager.app.ui.components.rememberGuestBarDiscountEnabled
import com.eventmanager.app.ui.components.rememberProfilePhotosUploadEnabled
import com.eventmanager.app.ui.components.rememberTemporaryGuestFeatures
import com.eventmanager.app.ui.components.temporaryEntryValidatedLabel
import com.eventmanager.app.ui.components.EventDatePickerField
import com.eventmanager.app.ui.components.GuestFormCreatorDialog
import com.eventmanager.app.ui.components.GuestFormPendingCard
import com.eventmanager.app.ui.components.TemporaryGuestAccessSelector
import com.eventmanager.app.ui.components.TemporaryGuestNameCard
import com.eventmanager.app.ui.components.TemporaryGuestVenueDropdown
import com.eventmanager.app.ui.components.fullScreenDialogProperties
import com.eventmanager.app.ui.utils.*
import com.eventmanager.app.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.format.DateTimeFormatter
import com.eventmanager.app.ui.components.OrgColorDot
import com.eventmanager.app.ui.utils.rememberGuestListEffectiveToday
import com.eventmanager.app.data.utils.formatMoney
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel

/** Billeterie (read-only) guest/volunteer profile on phone: shorter card-style window vs. full-screen admin. */
private const val READ_ONLY_PROFILE_PHONE_HEIGHT_FRACTION = 0.60f
private val READ_ONLY_PROFILE_PHONE_OUTER_HORIZONTAL_PADDING = 32.dp
private val READ_ONLY_PROFILE_PHONE_OUTER_VERTICAL_PADDING = 24.dp

@Composable
private fun BoxScope.ReadOnlyPhoneProfileFrame(
    enabled: Boolean,
    content: @Composable () -> Unit
) {
    if (enabled) {
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(
                    horizontal = READ_ONLY_PROFILE_PHONE_OUTER_HORIZONTAL_PADDING,
                    vertical = READ_ONLY_PROFILE_PHONE_OUTER_VERTICAL_PADDING
                )
                .fillMaxHeight(READ_ONLY_PROFILE_PHONE_HEIGHT_FRACTION)
        ) {
            content()
        }
    } else {
        content()
    }
}

/** Full-screen dialog window has no "outside" for [dismissOnClickOutside]; tap empty area to dismiss. */
@Composable
private fun Modifier.readOnlyPhoneProfileScrimDismiss(enabled: Boolean, onDismiss: () -> Unit): Modifier {
    if (!enabled) return this
    val interactionSource = remember { MutableInteractionSource() }
    return this.clickable(
        interactionSource = interactionSource,
        indication = null,
        onClick = onDismiss
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
actual fun GuestListScreen(
    guests: List<Guest>,
    volunteers: List<Volunteer>,
    jobs: List<Job>,
    jobTypeConfigs: List<JobTypeConfig>,
    venues: List<VenueEntity>,
    onAddGuest: (Guest, ByteArray?) -> Unit,
    onAddTemporaryGuests: (ManualTemporaryGuestBatch) -> Unit,
    onUpdateGuest: (Guest) -> Unit,
    onUpdateVolunteer: (Volunteer) -> Unit,
    onDeleteGuest: (Guest) -> Unit,
    onRefreshTemporaryGuests: () -> Unit,
    onConfirmEntry: ((Job, Int) -> Unit)?,
    @Suppress("UNUSED_PARAMETER") isSyncing: Boolean,
    @Suppress("UNUSED_PARAMETER") lastSyncTime: Long,
    scrollBehavior: String,
    /** Billeterie: view-only list and detail panels (no add/edit/delete/NFC/QR); history/future timeline and future-entry validate allowed. */
    readOnly: Boolean,
    @Suppress("UNUSED_PARAMETER") searchFocusTick: Int,
    viewModel: EventManagerViewModel?
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val settingsManager = remember { settingsManagerFor(context) }
    val currencyCode = remember { settingsManager.getCurrencyCode() }
    val accountTransfers by viewModel?.accountTransfers?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) }
    var selectedVenueName by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showVolunteerBenefits by remember { mutableStateOf<Volunteer?>(null) }
    var showGuestDetailPanel by remember { mutableStateOf<Guest?>(null) }
    var showEditGuestDialog by remember { mutableStateOf<Guest?>(null) }
    var searchText by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<String?>(null) }
    var showTemporaryGuestsTimeline by remember { mutableStateOf(false) }
    var showGuestFormCreator by remember { mutableStateOf(false) }
    val guestFormsEnabled by viewModel?.guestFormsEnabled?.collectAsState()
        ?: remember { mutableStateOf(false) }
    
    val isCompact = isCompactScreen()
    val isPhone = !isTablet()
    val responsivePadding = if (isPhone) getPhonePortraitPadding() else getResponsivePadding()
    val responsiveSpacing = if (isPhone) getPhonePortraitSpacing() else getResponsiveSpacing()

    // Create a map for O(1) volunteer lookup instead of O(n) find operations
    val volunteersMap = remember(volunteers) {
        volunteers.associateBy { it.id }
    }

    // Get filter strings once (getString is cheap, no need for remember)
    val filterPermanentGuests = context.getString(R.string.filter_permanent_guests)
    val filterTemporaryGuests = context.getString(R.string.filter_temporary_guests)
    val filterVolunteerBenefits = context.getString(R.string.filter_volunteer_benefits)
    val guestFilterOptions = remember(filterPermanentGuests, filterTemporaryGuests, filterVolunteerBenefits) {
        listOf(filterPermanentGuests, filterTemporaryGuests, filterVolunteerBenefits)
    }
    val zone = GuestListDefaultZoneId
    val offsetHours = settingsManager.getDateChangeOffsetHours()
    val effectiveToday = rememberGuestListEffectiveToday(zone = zone, offsetHours = offsetHours)
    val temporaryGuestFeatures = rememberTemporaryGuestFeatures(viewModel)

    val allOrgsMode = viewModel?.isFirebaseAllOrgsMode() == true
    val mergedVenueNames = remember(venues, allOrgsMode) {
        if (allOrgsMode) MultiOrgMerge.mergedVenueFilterNames(venues) else emptyList()
    }

    // Filter guests with proper dependency tracking on all inputs
    // Note: derivedStateOf only tracks Compose State objects, not function parameters like 'guests'
    // So we must include 'guests' as a key to remember() to re-filter when sync updates data
    val filteredGuests = remember(guests, selectedVenueName, searchText, selectedFilter, volunteersMap, effectiveToday, allOrgsMode, venues) {
        val searchQuery = searchText.trim()
        val hasSearch = searchQuery.isNotEmpty()
        val sortable = ArrayList<Pair<String, Guest>>(guests.size)

        for (guest in guests) {
            if (guest.isTemporaryGuest) {
                val eventTs = guest.temporaryEventDate ?: continue
                if (Instant.ofEpochMilli(eventTs).atZone(zone).toLocalDate() != effectiveToday) continue
                // A single-use entry that has been consumed belongs to the "Already in" box, not
                // to tonight's door list.
                if (guest.temporaryEntryValidated) continue
            }

            if (!MultiOrgMerge.matchesGuestVenueSelection(guest, selectedVenueName, venues, allOrgsMode)) continue

            if (hasSearch) {
                val matchesSearch =
                    guest.name.contains(searchQuery, ignoreCase = true) ||
                    guest.email.contains(searchQuery, ignoreCase = true) ||
                    guest.phoneNumber.contains(searchQuery, ignoreCase = true) ||
                    guest.notes.contains(searchQuery, ignoreCase = true) ||
                    guest.nfcCardUid.contains(searchQuery, ignoreCase = true)
                if (!matchesSearch) continue
            }

            val matchesFilter = when (selectedFilter) {
                filterVolunteerBenefits -> guest.isVolunteerBenefit
                filterPermanentGuests -> !guest.isVolunteerBenefit && !guest.isTemporaryGuest
                filterTemporaryGuests -> !guest.isVolunteerBenefit && guest.isTemporaryGuest
                else -> true
            }
            if (!matchesFilter) continue

            val sortKey = if (guest.isVolunteerBenefit && guest.volunteerId != null) {
                volunteersMap[guest.volunteerId]?.name ?: guest.name
            } else {
                guest.name
            }
            sortable.add(sortKey.lowercase() to guest)
        }

        sortable.sortBy { it.first }
        sortable.map { it.second }
    }
    
    // Memoize total invitations calculation
    val totalInvitations = remember(filteredGuests) {
        filteredGuests.sumOf { it.invitations }
    }
    
    // Generate venue filter options (memoized inside on venues + locale)
    val venueFilterOptions = generateVenueFilterOptions(venues)

    // Keep temporary guests in sync when entering/re-entering this screen
    val refreshTemporaryGuestsLatest by rememberUpdatedState(onRefreshTemporaryGuests)
    LaunchedEffect(Unit) {
        refreshTemporaryGuestsLatest()
    }
    LaunchedEffect(guestFormsEnabled, viewModel) {
        if (!guestFormsEnabled || viewModel == null) return@LaunchedEffect
        viewModel.refreshGuestFormsFromRemote()
        while (true) {
            kotlinx.coroutines.delay(12_000L)
            viewModel.refreshGuestFormsFromRemote()
        }
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshTemporaryGuestsLatest()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    when (scrollBehavior) {
        SettingsManager.HEADER_PINNED -> {
            // Original behavior: header fixed, only list scrolls
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(responsivePadding)
            ) {
            if (!readOnly) {
                Text(
                    text = if (isCompact) context.getString(R.string.guest_list_title) else context.getString(R.string.guest_list_management),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                
                Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 8.dp))
                
                if (!isCompact) {
                    Text(
                        text = context.getString(R.string.guest_list_description),
                        style = getResponsiveBodyTypography(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                Spacer(modifier = Modifier.height(responsiveSpacing))
            }
            
            if (isCompact) {
                // Stack vertically on phones
                Column(
                    verticalArrangement = Arrangement.spacedBy(responsiveSpacing)
                ) {
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
                                selected = selectedVenueName == venueOption.venueName,
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.LocationOn,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                    
                    if (!readOnly) {
                        Button(
                            onClick = { showAddDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(getResponsiveButtonHeight())
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(context.getString(R.string.add_guest))
                        }
                    }
                }
            } else {
                // Side by side on tablets
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
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
                                selected = selectedVenueName == venueOption.venueName,
                                leadingIcon = {
                                    Icon(
                                        Icons.Default.LocationOn,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                    }
                    
                    if (!readOnly) {
                        Button(
                            onClick = { showAddDialog = true },
                            modifier = Modifier.height(getResponsiveButtonHeight())
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(context.getString(R.string.add_guest))
                        }
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Search and Filter Section
            SearchBarWithFilter(
                searchText = searchText,
                onSearchTextChange = { searchText = it },
                placeholder = context.getString(R.string.search_guests_placeholder),
                filterOptions = guestFilterOptions,
                selectedFilter = selectedFilter,
                onFilterChange = { selectedFilter = it }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            GuestStatsAndTimelineRow(
                guestCount = filteredGuests.size,
                invitationCount = totalInvitations,
                onOpenTimeline = {
                    onRefreshTemporaryGuests()
                    showTemporaryGuestsTimeline = true
                }
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // Guest list - Use LazyColumn for lazy loading and better performance
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                if (!readOnly && viewModel != null) {
                    item(key = "guest_form_pending") {
                        GuestFormPendingCard(viewModel = viewModel, isPhone = isPhone)
                    }
                }
                items(
                    items = filteredGuests,
                    key = { guest -> 
                        guestStableKey(guest)
                    }
                ) { guest ->
                    GuestCard(
                        guest = guest,
                        volunteersMap = volunteersMap,
                        venues = venues,
                        onDelete = { onDeleteGuest(guest) },
                        onVolunteerClick = { volunteer ->
                            showVolunteerBenefits = volunteer
                        },
                        onGuestClick = { clickedGuest ->
                            showGuestDetailPanel = clickedGuest
                        },
                        viewModel = viewModel,
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
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header section (scrolls away)
                if (!readOnly) {
                    item {
                        Text(
                            text = if (isCompact) context.getString(R.string.guest_list_title) else context.getString(R.string.guest_list_management),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 8.dp))
                        
                        if (!isCompact) {
                            Text(
                                text = context.getString(R.string.guest_list_description),
                                style = getResponsiveBodyTypography(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(responsiveSpacing))
                    }
                }
                
                item {
                    if (isCompact) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(responsiveSpacing)
                        ) {
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
                                        selected = selectedVenueName == venueOption.venueName,
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.LocationOn,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                            }
                            
                            if (!readOnly) {
                                Button(
                                    onClick = { showAddDialog = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(getResponsiveButtonHeight())
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(context.getString(R.string.add_guest))
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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
                                        selected = selectedVenueName == venueOption.venueName,
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.LocationOn,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                            }
                            
                            if (!readOnly) {
                                Button(
                                    onClick = { showAddDialog = true },
                                    modifier = Modifier.height(getResponsiveButtonHeight())
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(context.getString(R.string.add_guest))
                                }
                            }
                        }
                    }
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
                            placeholder = context.getString(R.string.search_guests_placeholder),
                            filterOptions = guestFilterOptions,
                            selectedFilter = selectedFilter,
                            onFilterChange = { selectedFilter = it }
                        )
                    }
                }
                
                // Statistics section - scrolls with the list
                item {
                    GuestStatsAndTimelineRow(
                        guestCount = filteredGuests.size,
                        invitationCount = totalInvitations,
                        onOpenTimeline = {
                            onRefreshTemporaryGuests()
                            showTemporaryGuestsTimeline = true
                        }
                    )
                }

                if (!readOnly && viewModel != null) {
                    item(key = "guest_form_pending") {
                        GuestFormPendingCard(viewModel = viewModel, isPhone = isPhone)
                    }
                }
                
                items(
                    items = filteredGuests,
                    key = { guest -> 
                        guestStableKey(guest)
                    }
                ) { guest ->
                    GuestCard(
                        guest = guest,
                        volunteersMap = volunteersMap,
                        venues = venues,
                        onDelete = { onDeleteGuest(guest) },
                        onVolunteerClick = { volunteer ->
                            showVolunteerBenefits = volunteer
                        },
                        onGuestClick = { clickedGuest ->
                            showGuestDetailPanel = clickedGuest
                        },
                        viewModel = viewModel,
                    )
                }
            }
        }
        else -> {
            // FULL_SCROLL: header and list scroll together
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(responsivePadding),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (!readOnly) {
                    item {
                        Text(
                            text = if (isCompact) context.getString(R.string.guest_list_title) else context.getString(R.string.guest_list_management),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 8.dp))
                        
                        if (!isCompact) {
                            Text(
                                text = context.getString(R.string.guest_list_description),
                                style = getResponsiveBodyTypography(),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(responsiveSpacing))
                    }
                }
                
                item {
                    if (isCompact) {
                        Column(
                            verticalArrangement = Arrangement.spacedBy(responsiveSpacing)
                        ) {
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
                                        selected = selectedVenueName == venueOption.venueName,
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.LocationOn,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                            }
                            
                            if (!readOnly) {
                                Button(
                                    onClick = { showAddDialog = true },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(getResponsiveButtonHeight())
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(context.getString(R.string.add_guest))
                                }
                            }
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
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
                                        selected = selectedVenueName == venueOption.venueName,
                                        leadingIcon = {
                                            Icon(
                                                Icons.Default.LocationOn,
                                                contentDescription = null,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    )
                                }
                            }
                            
                            if (!readOnly) {
                                Button(
                                    onClick = { showAddDialog = true },
                                    modifier = Modifier.height(getResponsiveButtonHeight())
                                ) {
                                    Icon(Icons.Default.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(context.getString(R.string.add_guest))
                                }
                            }
                        }
                    }
                }
                
                item {
                    SearchBarWithFilter(
                        searchText = searchText,
                        onSearchTextChange = { searchText = it },
                        placeholder = context.getString(R.string.search_guests_placeholder),
                        filterOptions = guestFilterOptions,
                        selectedFilter = selectedFilter,
                        onFilterChange = { selectedFilter = it }
                    )
                }
                
                item {
                    GuestStatsAndTimelineRow(
                        guestCount = filteredGuests.size,
                        invitationCount = totalInvitations,
                        onOpenTimeline = {
                            onRefreshTemporaryGuests()
                            showTemporaryGuestsTimeline = true
                        }
                    )
                }

                if (!readOnly && viewModel != null) {
                    item(key = "guest_form_pending") {
                        GuestFormPendingCard(viewModel = viewModel, isPhone = isPhone)
                    }
                }
                
                items(
                    items = filteredGuests,
                    key = { guest -> 
                        guestStableKey(guest)
                    }
                ) { guest ->
                    GuestCard(
                        guest = guest,
                        volunteersMap = volunteersMap,
                        venues = venues,
                        onDelete = { onDeleteGuest(guest) },
                        onVolunteerClick = { volunteer ->
                            showVolunteerBenefits = volunteer
                        },
                        onGuestClick = { clickedGuest ->
                            showGuestDetailPanel = clickedGuest
                        },
                        viewModel = viewModel,
                    )
                }
            }
        }
    }
    
    if (showTemporaryGuestsTimeline) {
        TemporaryGuestsTimelineDialog(
            guests = guests,
            volunteers = volunteers,
            jobs = jobs,
            jobTypeConfigs = jobTypeConfigs,
            onGuestClick = { guest ->
                showTemporaryGuestsTimeline = false
                showGuestDetailPanel = guest
            },
            onVolunteerEntryClick = { volunteerId ->
                volunteers.find { it.id == volunteerId }?.let { v ->
                    showTemporaryGuestsTimeline = false
                    showVolunteerBenefits = v
                }
            },
            onDismiss = { showTemporaryGuestsTimeline = false },
            temporaryFeaturesEnabled = temporaryGuestFeatures.enabled,
        )
    }

    // Add Guest Dialog
    if (!readOnly && showAddDialog) {
        AddGuestDialog(
            venues = venues,
            onDismiss = { showAddDialog = false },
            profilePhotosEnabled = rememberProfilePhotosUploadEnabled(viewModel),
            barDiscountEnabled = rememberGuestBarDiscountEnabled(viewModel),
            temporaryFeaturesEnabled = temporaryGuestFeatures.enabled,
            venueAccesses = temporaryGuestFeatures.venueAccesses,
            guestFormsEnabled = guestFormsEnabled,
            onCreateForm = {
                showAddDialog = false
                showGuestFormCreator = true
            },
            onConfirmPermanent = { name, email, phoneNumber, invitations, venueName, notes, barDiscountPercent, photoBytes ->
                val newGuest = Guest(
                    name = name,
                    email = email,
                    phoneNumber = phoneNumber,
                    invitations = invitations,
                    venueName = venueName,
                    notes = notes,
                    barDiscountPercent = barDiscountPercent
                )
                onAddGuest(newGuest, photoBytes)
                showAddDialog = false
            },
            onConfirmTemporary = { batch ->
                onAddTemporaryGuests(batch)
                showAddDialog = false
            }
        )
    }

    if (showGuestFormCreator && viewModel != null) {
        GuestFormCreatorDialog(
            viewModel = viewModel,
            venues = venues,
            venueAccesses = temporaryGuestFeatures.venueAccesses,
            onDismiss = { showGuestFormCreator = false },
        )
    }
    
    // Volunteer Benefits Panel
    if (showVolunteerBenefits != null) {
        val volunteer = showVolunteerBenefits!!
        
        // Scalar version key that changes whenever any job entry counter or timestamp is modified.
        // Ensures remember caches inside the Dialog are invalidated reliably.
        val jobsVersion = remember(jobs) {
            jobs.fold(0L) { acc, j -> acc + j.lastModified + (j.benefitFutureEntriesRemaining ?: 0) }
        }
        val benefitContext = LocalContext.current
        val benefitSettingsManager = remember { settingsManagerFor(benefitContext) }
        val benefitOffsetHours = remember { benefitSettingsManager.getDateChangeOffsetHours() }
        val memoizedBenefitStatus = remember(volunteer.id, jobs, jobTypeConfigs, benefitOffsetHours, jobsVersion) {
            BenefitCalculator.calculateVolunteerBenefitStatus(volunteer, jobs, jobTypeConfigs, offsetHours = benefitOffsetHours)
        }
        val memoizedVolunteerJobs = remember(volunteer.id, jobs, jobsVersion) {
            jobs.filter { it.volunteerId == volunteer.id }
        }
        
        val benefitsTablet = isTablet()
        val compactReadOnlyVolunteerProfile = !benefitsTablet && readOnly
        Dialog(
            onDismissRequest = { showVolunteerBenefits = null },
            properties = fullScreenDialogProperties(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .readOnlyPhoneProfileScrimDismiss(compactReadOnlyVolunteerProfile) {
                        showVolunteerBenefits = null
                    }
                    .then(
                        if (benefitsTablet) Modifier.padding(getTabletDialogScreenEdgeInset())
                        else Modifier
                    )
            ) {
                ReadOnlyPhoneProfileFrame(enabled = compactReadOnlyVolunteerProfile) {
                    VolunteerBenefitsPanel(
                        modifier = Modifier.fillMaxSize(),
                        volunteer = volunteer,
                        volunteerBenefitStatus = memoizedBenefitStatus,
                        volunteerJobs = memoizedVolunteerJobs,
                        venues = venues,
                        jobTypeConfigs = jobTypeConfigs,
                        onClose = { showVolunteerBenefits = null },
                        readOnly = readOnly,
                        onConfirmEntry = onConfirmEntry,
                        onAssignNfcUid = if (readOnly) null else { updatedVolunteer, uid ->
                            onUpdateVolunteer(
                                updatedVolunteer.copy(
                                    nfcCardUid = uid,
                                    lastModified = System.currentTimeMillis()
                                )
                            )
                            showVolunteerBenefits = updatedVolunteer.copy(nfcCardUid = uid)
                        },
                        accountBalance = viewModel?.getVolunteerAccountBalance(volunteer.id) ?: 0.0,
                        currencyCode = currencyCode,
                        recentTransfers = accountTransfers.filter {
                            it.holderType == AccountHolderType.VOLUNTEER && it.holderId == volunteer.id
                        },
                        onManualAccountAdjust = null,
                        viewModel = viewModel
                    )
                }
            }
        }
    }
    
    // Guest Detail Panel
    if (showGuestDetailPanel != null) {
        val detailGuest = showGuestDetailPanel!!
        val guestDetailTablet = isTablet()
        val compactReadOnlyGuestProfile = !guestDetailTablet && readOnly
        Dialog(
            onDismissRequest = { showGuestDetailPanel = null },
            properties = fullScreenDialogProperties(),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .readOnlyPhoneProfileScrimDismiss(compactReadOnlyGuestProfile) {
                        showGuestDetailPanel = null
                    }
                    .then(
                        if (guestDetailTablet) Modifier.padding(getTabletDialogScreenEdgeInset())
                        else Modifier
                    )
            ) {
                ReadOnlyPhoneProfileFrame(enabled = compactReadOnlyGuestProfile) {
                    GuestDetailPanel(
                        modifier = Modifier.fillMaxSize(),
                        guest = detailGuest,
                        venues = venues,
                        readOnly = readOnly,
                        onEdit = { guest ->
                            showGuestDetailPanel = null
                            showEditGuestDialog = guest
                        },
                        onAssignNfcUid = { guest, uid ->
                            onUpdateGuest(
                                guest.copy(
                                    nfcCardUid = uid,
                                    lastModified = System.currentTimeMillis()
                                )
                            )
                            showGuestDetailPanel = guest.copy(nfcCardUid = uid)
                        },
                        onDelete = { guest ->
                            showGuestDetailPanel = null
                            onDeleteGuest(guest)
                        },
                        onClose = { showGuestDetailPanel = null },
                        accountBalance = viewModel?.getGuestAccountBalance(detailGuest.nanoId) ?: 0.0,
                        currencyCode = currencyCode,
                        recentTransfers = accountTransfers.filter {
                            it.holderType == AccountHolderType.GUEST && it.holderId == detailGuest.nanoId
                        },
                        onManualAccountAdjust = if (viewModel != null && !readOnly) { amount, note ->
                            viewModel.applyManualAccountAdjustment(
                                AccountHolderType.GUEST,
                                detailGuest.nanoId,
                                detailGuest.name,
                                amount,
                                note
                            )
                        } else null,
                        viewModel = viewModel
                    )
                }
            }
        }
    }
    
    // Edit Guest Dialog
    if (!readOnly && showEditGuestDialog != null) {
        EditGuestDialog(
            guest = showEditGuestDialog!!,
            venues = venues,
            profilePhotosEnabled = rememberProfilePhotosUploadEnabled(viewModel),
            barDiscountEnabled = rememberGuestBarDiscountEnabled(viewModel),
            temporaryFeaturesEnabled = temporaryGuestFeatures.enabled,
            venueAccesses = temporaryGuestFeatures.venueAccesses,
            viewModel = viewModel,
            onDismiss = { showEditGuestDialog = null },
            onConfirm = { updatedGuest ->
                onUpdateGuest(updatedGuest)
                showEditGuestDialog = null
            }
        )
    }
}

@Composable
fun GuestCard(
    guest: Guest,
    volunteersMap: Map<String, Volunteer>,
    venues: List<VenueEntity>,
    @Suppress("UNUSED_PARAMETER") onDelete: () -> Unit,
    modifier: Modifier = Modifier,
    onVolunteerClick: (Volunteer) -> Unit = {},
    onGuestClick: (Guest) -> Unit = {},
    viewModel: EventManagerViewModel? = null,
) {
    val context = LocalContext.current
    val isCompact = isCompactScreen()
    val responsivePadding = getResponsivePadding()
    val responsiveAvatarSize = getResponsiveAvatarSize()
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = true) {
                if (guest.isVolunteerBenefit && guest.volunteerId != null) {
                    // Lookup volunteer by ID using map for O(1) performance
                    volunteersMap[guest.volunteerId]?.let { onVolunteerClick(it) }
                } else {
                    // Permanent guest - open detail panel
                    onGuestClick(guest)
                }
            },
        elevation = CardDefaults.cardElevation(defaultElevation = getResponsiveCardElevation())
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(responsivePadding),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar circle
            Card(
                modifier = Modifier.size(responsiveAvatarSize),
                shape = androidx.compose.foundation.shape.CircleShape,
                colors = CardDefaults.cardColors(
                    containerColor = if (guest.isVolunteerBenefit) 
                        MaterialTheme.colorScheme.secondary 
                    else 
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = guest.name.take(1).uppercase(),
                        style = if (isCompact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (guest.isVolunteerBenefit) 
                            MaterialTheme.colorScheme.onSecondary 
                        else 
                            MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
            
            Spacer(modifier = Modifier.width(if (isCompact) 8.dp else 12.dp))
            
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = if (guest.isVolunteerBenefit && guest.lastNameAbbreviation.isNotEmpty()) {
                            "${guest.name} (${guest.lastNameAbbreviation})"
                        } else {
                            guest.name
                        },
                        style = if (isCompact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (viewModel?.isFirebaseAllOrgsMode() == true && guest.firebaseOrgId.isNotBlank()) {
                        OrgColorDot(orgId = guest.firebaseOrgId, viewModel = viewModel, size = 8.dp)
                    }
                    
                    if (guest.isVolunteerBenefit) {
                        AssistChip(
                            onClick = { },
                            label = { 
                                Text(
                                    context.getString(R.string.volunteer_label),
                                    style = MaterialTheme.typography.labelSmall
                                ) 
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        )
                    } else if (guest.isTemporaryGuest) {
                        AssistChip(
                            onClick = { },
                            label = {
                                Text(
                                    context.getString(R.string.temp_guest_chip_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            },
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                labelColor = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        )
                    }
                }
                
                if (guest.isTemporaryGuest && guest.temporaryArtistName.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = guest.temporaryArtistName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                if (!guest.isTemporaryGuest) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.People,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = context.getString(R.string.invitations_text, guest.invitations),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                Icons.Default.LocationOn,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = getVenueDisplayString(guest.venueName, venues),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
                
            }
            
            // Click indicator for volunteer benefits or permanent guests
            if (guest.isVolunteerBenefit && guest.volunteerId != null) {
                @Suppress("DEPRECATION")
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = context.getString(R.string.view_benefits),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            } else {
                // Permanent guest - show click indicator
                @Suppress("DEPRECATION")
                Icon(
                    imageVector = Icons.Default.ArrowForward,
                    contentDescription = context.getString(R.string.view_details),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun GuestStatsAndTimelineRow(
    guestCount: Int,
    invitationCount: Int,
    onOpenTimeline: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val isPhone = !isTablet()

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (isPhone) 8.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(if (isPhone) 8.dp else 12.dp)
        ) {
            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(if (isPhone) 88.dp else 96.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(if (isPhone) 8.dp else 12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (isPhone) 8.dp else 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = guestCount.toString(),
                        style = if (isPhone) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isPhone) context.getString(R.string.guests_count) else context.getString(R.string.total_guests_count),
                        style = if (isPhone) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Card(
                modifier = Modifier
                    .weight(1f)
                    .height(if (isPhone) 88.dp else 96.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                shape = RoundedCornerShape(if (isPhone) 8.dp else 12.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(if (isPhone) 8.dp else 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = invitationCount.toString(),
                        style = if (isPhone) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (isPhone) context.getString(R.string.invitations_count) else context.getString(R.string.total_invitations_count),
                        style = if (isPhone) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        if (onOpenTimeline != null) {
        Card(
            modifier = Modifier
                .weight(1f)
                .height(if (isPhone) 88.dp else 96.dp)
                .clickable { onOpenTimeline() },
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
            shape = RoundedCornerShape(if (isPhone) 8.dp else 12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(if (isPhone) 8.dp else 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Icon(
                    Icons.Default.History,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = context.getString(R.string.temp_guest_timeline_button),
                    style = if (isPhone) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        }
    }
}

private enum class TempGuestRange(@androidx.annotation.StringRes val labelRes: Int, val days: Long?) {
    THREE_DAYS(R.string.temp_guest_range_three_days, 3),
    ONE_WEEK(R.string.temp_guest_range_one_week, 7),
    TWO_WEEKS(R.string.temp_guest_range_two_weeks, 14),
    ONE_MONTH(R.string.temp_guest_range_one_month, 30),
    SIX_MONTHS(R.string.temp_guest_range_six_months, 182),
    ONE_YEAR(R.string.temp_guest_range_one_year, 365),
    ALL_TIME(R.string.temp_guest_range_all_time, null)
}

private enum class TempGuestRangeShortLabel(@androidx.annotation.StringRes val labelRes: Int) {
    THREE_DAYS(R.string.temp_guest_range_three_days_short),
    ONE_WEEK(R.string.temp_guest_range_one_week_short),
    TWO_WEEKS(R.string.temp_guest_range_two_weeks_short),
    ONE_MONTH(R.string.temp_guest_range_one_month_short),
    SIX_MONTHS(R.string.temp_guest_range_six_months_short),
    ONE_YEAR(R.string.temp_guest_range_one_year_short),
    ALL_TIME(R.string.temp_guest_range_all_time_short)
}

private data class VolunteerAccessEntry(
    val volunteerId: String,
    val volunteerName: String,
    val volunteerNameLower: String,
    val volunteerNfcUidLower: String,
    val accessStartDate: LocalDate,
    val accessEndDate: LocalDate
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemporaryGuestsTimelineDialog(
    guests: List<Guest>,
    volunteers: List<Volunteer>,
    jobs: List<Job>,
    jobTypeConfigs: List<JobTypeConfig>,
    onGuestClick: (Guest) -> Unit,
    onVolunteerEntryClick: (String) -> Unit,
    onDismiss: () -> Unit,
    /** Firebase only: shows the Tonight tracking tab (entered / total). */
    temporaryFeaturesEnabled: Boolean = false,
) {
    val context = LocalContext.current
    val settingsManager = remember { settingsManagerFor(context) }
    val zone = GuestListDefaultZoneId
    val offsetHours = remember(settingsManager) { settingsManager.getDateChangeOffsetHours() }
    val effectiveToday = rememberGuestListEffectiveToday(zone = zone, offsetHours = offsetHours)

    var searchText by remember { mutableStateOf("") }
    var selectedRange by remember { mutableStateOf(TempGuestRange.THREE_DAYS) }
    val tabTonight = 0
    val tabFuture = if (temporaryFeaturesEnabled) 1 else 0
    val tabHistory = if (temporaryFeaturesEnabled) 2 else 1
    var selectedTab by remember(temporaryFeaturesEnabled) {
        mutableStateOf(if (temporaryFeaturesEnabled) tabTonight else tabFuture)
    }
    val rangeToShortLabel = remember(context) {
        mapOf(
            TempGuestRange.THREE_DAYS to context.getString(TempGuestRangeShortLabel.THREE_DAYS.labelRes),
            TempGuestRange.ONE_WEEK to context.getString(TempGuestRangeShortLabel.ONE_WEEK.labelRes),
            TempGuestRange.TWO_WEEKS to context.getString(TempGuestRangeShortLabel.TWO_WEEKS.labelRes),
            TempGuestRange.ONE_MONTH to context.getString(TempGuestRangeShortLabel.ONE_MONTH.labelRes),
            TempGuestRange.SIX_MONTHS to context.getString(TempGuestRangeShortLabel.SIX_MONTHS.labelRes),
            TempGuestRange.ONE_YEAR to context.getString(TempGuestRangeShortLabel.ONE_YEAR.labelRes),
            TempGuestRange.ALL_TIME to context.getString(TempGuestRangeShortLabel.ALL_TIME.labelRes)
        )
    }
    val shortLabelToRange = remember(rangeToShortLabel) { rangeToShortLabel.entries.associate { it.value to it.key } }
    val rangeFilterOptions = remember(rangeToShortLabel) { TempGuestRange.values().mapNotNull { rangeToShortLabel[it] } }

    val temporaryGuestsWithDate = remember(guests, zone) {
        guests.asSequence()
            .filter { it.isTemporaryGuest }
            .mapNotNull { guest ->
                val ts = guest.temporaryEventDate ?: return@mapNotNull null
                guest to Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
            }
            .toList()
    }

    val filtered = remember(temporaryGuestsWithDate, searchText, selectedRange, effectiveToday) {
        val searchQuery = searchText.trim()
        val hasSearch = searchQuery.isNotEmpty()
        val rangeDays = selectedRange.days
        temporaryGuestsWithDate.filter { (guest, eventDate) ->
            val inRange = if (rangeDays == null) {
                true
            } else {
                kotlin.math.abs(ChronoUnit.DAYS.between(effectiveToday, eventDate)) <= rangeDays
            }
            val matchesSearch = !hasSearch ||
                guest.name.contains(searchQuery, ignoreCase = true) ||
                guest.temporaryArtistName.contains(searchQuery, ignoreCase = true) ||
                guest.temporaryContactPhone.contains(searchQuery, ignoreCase = true) ||
                guest.notes.contains(searchQuery, ignoreCase = true) ||
                guest.nfcCardUid.contains(searchQuery, ignoreCase = true)
            inRange && matchesSearch
        }
    }

    // Tonight's guests: entered vs still outside (Firebase tracking only).
    val tonightEntered = remember(filtered, effectiveToday, temporaryFeaturesEnabled) {
        if (!temporaryFeaturesEnabled) {
            emptyList()
        } else {
            filtered.filter { (guest, eventDate) ->
                eventDate == effectiveToday && guest.temporaryEntryValidated
            }.map { it.first }.sortedByDescending { it.temporaryEntryValidatedAt }
        }
    }
    val tonightRemaining = remember(filtered, effectiveToday, temporaryFeaturesEnabled) {
        if (!temporaryFeaturesEnabled) {
            emptyList()
        } else {
            filtered.filter { (guest, eventDate) ->
                eventDate == effectiveToday && !guest.temporaryEntryValidated
            }.map { it.first }.sortedBy { it.name.lowercase() }
        }
    }
    val tonightTotal = tonightEntered.size + tonightRemaining.size

    // With Firebase tracking, tonight is only on the Tonight tab. Without it, today stays in Future.
    val futureAndPastGuests = remember(filtered, effectiveToday, temporaryFeaturesEnabled) {
        val futurePred: (LocalDate) -> Boolean = if (temporaryFeaturesEnabled) {
            { date -> date.isAfter(effectiveToday) }
        } else {
            { date -> !date.isBefore(effectiveToday) }
        }
        filtered.partition { (_, eventDate) -> futurePred(eventDate) }
    }
    val futureGuests = remember(futureAndPastGuests) {
        futureAndPastGuests.first.map { it.first }
            .sortedWith(compareBy<Guest> { it.temporaryEventDate ?: Long.MAX_VALUE }.thenBy { it.name.lowercase() })
    }

    val pastGuests = remember(futureAndPastGuests) {
        futureAndPastGuests.second
            .filter { (_, eventDate) -> eventDate.isBefore(effectiveToday) }
            .map { it.first }
            .sortedWith(compareByDescending<Guest> { it.temporaryEventDate ?: Long.MIN_VALUE }.thenBy { it.name.lowercase() })
    }

    val benefitRelevantJobTypeNames = remember(jobTypeConfigs) {
        jobTypeConfigs.asSequence()
            .filter { config ->
                config.isActive && (
                    config.isShiftJob ||
                        config.isOrionJob ||
                        (config.benefitSystemType == BenefitSystemType.MANUAL && config.manualRewards != null)
                    )
            }
            .map { it.name }
            .toSet()
    }
    val volunteersById = remember(volunteers) { volunteers.associateBy { it.id } }
    val volunteerAccessEntries = remember(jobs, benefitRelevantJobTypeNames, volunteersById, zone, offsetHours, jobTypeConfigs) {
        val rangesByVolunteer = mutableMapOf<String, MutableList<Pair<LocalDate, LocalDate>>>()
        val jobsByVolunteerId = jobs
            .asSequence()
            .filter { it.jobTypeName in benefitRelevantJobTypeNames }
            .groupBy { it.volunteerId }

        jobsByVolunteerId.forEach { (volunteerId, volunteerJobsRaw) ->
            val volunteer = volunteersById[volunteerId] ?: return@forEach
            if (volunteerJobsRaw.isEmpty()) return@forEach

            // Sort once and iterate prefix subLists to avoid copying lists.
            val volunteerJobs = volunteerJobsRaw.sortedBy { it.date }
            for (index in volunteerJobs.indices) {
                val checkpointTs = volunteerJobs[index].date
                val jobsUpToCheckpoint = volunteerJobs.subList(0, index + 1)

                val status = BenefitCalculator.calculateVolunteerBenefitStatusFromVolunteerJobs(
                    volunteer = volunteer,
                    volunteerJobs = jobsUpToCheckpoint,
                    jobTypeConfigs = jobTypeConfigs,
                    currentTime = checkpointTs,
                    offsetHours = offsetHours
                )

                val benefit = status.benefits
                val validUntil = benefit.validUntil
                if (!benefit.isActive || !benefit.guestListAccess || validUntil == null || validUntil <= checkpointTs) {
                    continue
                }

                val startDate = Instant.ofEpochMilli(checkpointTs).atZone(zone).toLocalDate()
                val endDate = Instant.ofEpochMilli(validUntil).atZone(zone).toLocalDate()
                rangesByVolunteer.getOrPut(volunteer.id) { mutableListOf() }.add(startDate to endDate)
            }
        }

        val entries = ArrayList<VolunteerAccessEntry>()
        rangesByVolunteer.forEach { (volunteerId, ranges) ->
            val volunteer = volunteersById[volunteerId] ?: return@forEach
            if (ranges.isEmpty()) return@forEach

            ranges.sortBy { it.first }
            var currentStart = ranges[0].first
            var currentEnd = ranges[0].second

            for (i in 1 until ranges.size) {
                val nextStart = ranges[i].first
                val nextEnd = ranges[i].second
                if (!nextStart.isAfter(currentEnd.plusDays(1))) {
                    if (nextEnd.isAfter(currentEnd)) currentEnd = nextEnd
                } else {
                    entries.add(
                        VolunteerAccessEntry(
                            volunteerId = volunteer.id,
                            volunteerName = volunteer.name,
                            volunteerNameLower = volunteer.name.lowercase(),
                            volunteerNfcUidLower = volunteer.nfcCardUid.lowercase(),
                            accessStartDate = currentStart,
                            accessEndDate = currentEnd
                        )
                    )
                    currentStart = nextStart
                    currentEnd = nextEnd
                }
            }

            entries.add(
                VolunteerAccessEntry(
                    volunteerId = volunteer.id,
                    volunteerName = volunteer.name,
                    volunteerNameLower = volunteer.name.lowercase(),
                    volunteerNfcUidLower = volunteer.nfcCardUid.lowercase(),
                    accessStartDate = currentStart,
                    accessEndDate = currentEnd
                )
            )
        }

        entries
    }

    val filteredVolunteerEntries = remember(volunteerAccessEntries, searchText, selectedRange, effectiveToday) {
        val searchQuery = searchText.trim().lowercase()
        val hasSearch = searchQuery.isNotEmpty()
        val rangeDays = selectedRange.days
        volunteerAccessEntries.filter { entry ->
            val inRange = if (rangeDays == null) {
                true
            } else {
                val fromDate = effectiveToday.minusDays(rangeDays)
                val toDate = effectiveToday.plusDays(rangeDays)
                !entry.accessEndDate.isBefore(fromDate) && !entry.accessStartDate.isAfter(toDate)
            }
            val matchesSearch = !hasSearch ||
                entry.volunteerNameLower.contains(searchQuery) ||
                entry.volunteerNfcUidLower.contains(searchQuery)
            inRange && matchesSearch
        }
    }

    val futureAndPastVolunteerEntries = remember(filteredVolunteerEntries, effectiveToday) {
        filteredVolunteerEntries.partition { !it.accessEndDate.isBefore(effectiveToday) }
    }
    val futureVolunteerEntries = remember(futureAndPastVolunteerEntries) {
        futureAndPastVolunteerEntries.first
            .sortedWith(compareBy<VolunteerAccessEntry> { it.accessStartDate }.thenBy { it.volunteerName.lowercase() })
    }

    val pastVolunteerEntries = remember(futureAndPastVolunteerEntries) {
        futureAndPastVolunteerEntries.second
            .sortedWith(compareByDescending<VolunteerAccessEntry> { it.accessEndDate }.thenBy { it.volunteerName.lowercase() })
    }

    val temporaryTimelineTablet = isTablet()
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (temporaryTimelineTablet) Modifier.padding(getTabletDialogScreenEdgeInset())
                    else Modifier
                )
        ) {
        Card(
            modifier = Modifier
                .then(
                    if (temporaryTimelineTablet) {
                        Modifier.fillMaxSize()
                    } else {
                        Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(0.95f)
                            .padding(16.dp)
                    }
                ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = context.getString(R.string.temp_guest_timeline_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = context.getString(R.string.close))
                    }
                }

                SearchBarWithFilter(
                    searchText = searchText,
                    onSearchTextChange = { searchText = it },
                    placeholder = context.getString(R.string.search_guests_placeholder),
                    filterOptions = rangeFilterOptions,
                    selectedFilter = rangeToShortLabel[selectedRange],
                    onFilterChange = { selected ->
                        selectedRange = selected?.let { shortLabelToRange[it] } ?: TempGuestRange.THREE_DAYS
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    // Ranges already include ALL_TIME ("All" / "Tous"); a separate filter_all row
                    // duplicated the label in FR and mapped null to THREE_DAYS (wrong).
                    includeFilterAllMenuItem = false
                )

                Spacer(modifier = Modifier.height(12.dp))

                TabRow(selectedTabIndex = selectedTab) {
                    if (temporaryFeaturesEnabled) {
                        Tab(
                            selected = selectedTab == tabTonight,
                            onClick = { selectedTab = tabTonight },
                            text = { Text(context.getString(R.string.temp_guest_tab_tonight)) },
                        )
                    }
                    Tab(
                        selected = selectedTab == tabFuture,
                        onClick = { selectedTab = tabFuture },
                        text = { Text(context.getString(R.string.temp_guest_tab_future)) },
                    )
                    Tab(
                        selected = selectedTab == tabHistory,
                        onClick = { selectedTab = tabHistory },
                        text = { Text(context.getString(R.string.temp_guest_tab_history)) },
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    if (temporaryFeaturesEnabled && selectedTab == tabTonight) {
                        item {
                            TimelineSectionHeader(
                                title = context.getString(R.string.temp_guest_tonight_title),
                                count = tonightEntered.size,
                                isFuture = false,
                                countLabel = context.getString(
                                    R.string.temp_guest_validated_count,
                                    tonightEntered.size,
                                    tonightTotal,
                                ),
                                highlighted = true,
                            )
                        }
                        item {
                            Text(
                                context.getString(R.string.temp_guest_tonight_tracking),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        item {
                            TimelineSectionHeader(
                                title = context.getString(R.string.temp_guest_section_validated),
                                count = tonightEntered.size,
                                isFuture = false,
                                highlighted = true,
                            )
                        }
                        if (tonightEntered.isEmpty()) {
                            item {
                                Text(
                                    context.getString(R.string.temp_guest_none_validated),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(
                                items = tonightEntered,
                                key = { guest -> "temp_entered_${guestStableKey(guest)}" },
                            ) { guest ->
                                TemporaryGuestTimelineItem(
                                    guest = guest,
                                    onClick = { onGuestClick(guest) },
                                    showEntryValidation = true,
                                )
                            }
                        }
                        item {
                            Spacer(modifier = Modifier.height(12.dp))
                            TimelineSectionHeader(
                                title = context.getString(R.string.temp_guest_section_remaining),
                                count = tonightRemaining.size,
                                isFuture = true,
                            )
                        }
                        if (tonightRemaining.isEmpty()) {
                            item {
                                Text(
                                    context.getString(R.string.temp_guest_none_remaining),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(
                                items = tonightRemaining,
                                key = { guest -> "temp_remain_${guestStableKey(guest)}" },
                            ) { guest ->
                                TemporaryGuestTimelineItem(
                                    guest = guest,
                                    onClick = { onGuestClick(guest) },
                                )
                            }
                        }
                    } else if (selectedTab == tabFuture) {
                        item {
                            TimelineSectionHeader(
                                title = context.getString(R.string.temp_guest_section_upcoming),
                                count = futureGuests.size,
                                isFuture = true,
                            )
                        }
                        if (futureGuests.isEmpty()) {
                            item {
                                Text(
                                    context.getString(R.string.temp_guest_none_upcoming),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(
                                items = futureGuests,
                                key = { guest -> "temp_future_${guestStableKey(guest)}_${guest.temporaryEventDate}" },
                            ) { guest ->
                                TemporaryGuestTimelineItem(
                                    guest = guest,
                                    onClick = { onGuestClick(guest) },
                                )
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(12.dp))
                            TimelineSectionHeader(
                                title = context.getString(R.string.temp_guest_subsection_volunteers),
                                count = futureVolunteerEntries.size,
                                isFuture = true,
                            )
                        }
                        if (futureVolunteerEntries.isEmpty()) {
                            item {
                                Text(
                                    context.getString(R.string.temp_guest_none_volunteers),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(
                                items = futureVolunteerEntries,
                                key = { entry -> "vol_future_${entry.volunteerId}_${entry.accessStartDate}_${entry.accessEndDate}" },
                            ) { entry ->
                                VolunteerTimelineItem(
                                    entry = entry,
                                    onClick = { onVolunteerEntryClick(entry.volunteerId) },
                                )
                            }
                        }
                    } else {
                        item {
                            TimelineSectionHeader(
                                title = context.getString(R.string.temp_guest_section_past),
                                count = pastGuests.size,
                                isFuture = false,
                            )
                        }
                        if (pastGuests.isEmpty()) {
                            item {
                                Text(
                                    context.getString(R.string.temp_guest_none_past),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(
                                items = pastGuests,
                                key = { guest -> "temp_past_${guestStableKey(guest)}_${guest.temporaryEventDate}" },
                            ) { guest ->
                                TemporaryGuestTimelineItem(
                                    guest = guest,
                                    onClick = { onGuestClick(guest) },
                                )
                            }
                        }

                        item {
                            Spacer(modifier = Modifier.height(12.dp))
                            TimelineSectionHeader(
                                title = context.getString(R.string.temp_guest_subsection_volunteers),
                                count = pastVolunteerEntries.size,
                                isFuture = false,
                            )
                        }
                        if (pastVolunteerEntries.isEmpty()) {
                            item {
                                Text(
                                    context.getString(R.string.temp_guest_none_volunteers),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(
                                items = pastVolunteerEntries,
                                key = { entry -> "vol_past_${entry.volunteerId}_${entry.accessStartDate}_${entry.accessEndDate}" },
                            ) { entry ->
                                VolunteerTimelineItem(
                                    entry = entry,
                                    onClick = { onVolunteerEntryClick(entry.volunteerId) },
                                )
                            }
                        }
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun TemporaryGuestTimelineItem(
    guest: Guest,
    onClick: () -> Unit,
    /** Replaces the event date with the entry time and marks the avatar as checked in. */
    showEntryValidation: Boolean = false,
) {
    val eventDateText = remember(guest.temporaryEventDate) {
        guest.temporaryEventDate?.let { com.eventmanager.app.data.utils.DateTimeUtils.formatGenevaDateOnly(it) } ?: "-"
    }
    val entryTimeText = temporaryEntryValidatedLabel(guest)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Card(
                modifier = Modifier.size(42.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                colors = CardDefaults.cardColors(
                    containerColor = if (showEntryValidation) {
                        MaterialTheme.colorScheme.tertiaryContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    }
                )
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (showEntryValidation) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(22.dp),
                        )
                    } else {
                        Text(
                            text = guest.name.take(1).uppercase(),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = guest.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = guest.temporaryArtistName.ifBlank { "-" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = if (showEntryValidation && entryTimeText != null) entryTimeText else eventDateText,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun VolunteerTimelineItem(
    entry: VolunteerAccessEntry,
    onClick: () -> Unit
) {
    val rangeText = remember(entry.accessStartDate, entry.accessEndDate) {
        val formatter = DateTimeFormatter.ofPattern("d MMMM yyyy", java.util.Locale.getDefault())
        "${entry.accessStartDate.format(formatter)} - ${entry.accessEndDate.format(formatter)}"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Card(
                modifier = Modifier.size(42.dp),
                shape = androidx.compose.foundation.shape.CircleShape,
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = entry.volunteerName.take(1).uppercase(),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entry.volunteerName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = rangeText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TimelineSectionHeader(
    title: String,
    count: Int,
    isFuture: Boolean,
    /** Overrides the plain count, e.g. "3 / 12" for tonight's validated entries. */
    countLabel: String? = null,
    highlighted: Boolean = false,
) {
    val containerColor = when {
        highlighted -> MaterialTheme.colorScheme.tertiaryContainer
        isFuture -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }
    val contentColor = when {
        highlighted -> MaterialTheme.colorScheme.onTertiaryContainer
        isFuture -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSecondaryContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
            Text(
                text = countLabel ?: count.toString(),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        }
    }
}

private fun guestStableKey(guest: Guest): String {
    val typePrefix = when {
        guest.isTemporaryGuest -> "temp"
        guest.isVolunteerBenefit -> "vol"
        else -> "reg"
    }
    // Use a row-unique key first. `sheetsId` is not guaranteed unique during sync transitions.
    if (guest.id > 0L) return "$typePrefix:id_${guest.id}"
    // Fallback: NanoID should be globally unique even when DB id is not yet assigned.
    if (guest.nanoId.isNotBlank()) return "$typePrefix:n_${guest.nanoId}"
    return "$typePrefix:f_${guest.lastModified}_${guest.name}_${guest.venueName}_${guest.invitations}_${guest.temporaryEventDate}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddGuestDialog(
    venues: List<VenueEntity>,
    onDismiss: () -> Unit,
    onConfirmPermanent: (String, String, String, Int, String, String, Int, ByteArray?) -> Unit,
    onConfirmTemporary: (ManualTemporaryGuestBatch) -> Unit,
    profilePhotosEnabled: Boolean = false,
    barDiscountEnabled: Boolean = false,
    temporaryFeaturesEnabled: Boolean = false,
    venueAccesses: List<VenueAccess> = emptyList(),
    guestFormsEnabled: Boolean = false,
    onCreateForm: () -> Unit = {},
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(0) }

    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phoneNumber by remember { mutableStateOf("") }
    var invitations by remember { mutableStateOf("0") }
    var selectedVenueName by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf("") }
    var barDiscount by remember { mutableStateOf("0") }
    var showVenueDropdown by remember { mutableStateOf(false) }
    var pendingPhotoBytes by remember { mutableStateOf<ByteArray?>(null) }

    var temporaryArtist by remember { mutableStateOf("") }
    var temporaryEventDateMillis by remember { mutableStateOf<Long?>(null) }
    var temporaryEmergencyPhone by remember { mutableStateOf("") }
    var temporaryContactEmail by remember { mutableStateOf("") }
    var temporaryComments by remember { mutableStateOf("") }
    var temporaryVenueName by remember { mutableStateOf<String?>(null) }
    var temporaryBarDiscount by remember { mutableStateOf("0") }
    val temporaryGuestNames = remember { mutableStateListOf("") }
    val temporaryGuestAccessIds = remember { mutableStateListOf(emptySet<String>()) }

    val isCompact = isCompactScreen()
    val scrollState = rememberScrollState()
    val isTabletDevice = isTablet()

    val activeVenues = remember(venues) { venues.filter { it.isActive } }
    val selectedVenueAccesses = remember(venueAccesses, temporaryVenueName) {
        temporaryVenueName?.let { VenueAccessCatalog.forVenue(venueAccesses, it) }.orEmpty()
    }

    val trimmedTempEntries = temporaryGuestNames.mapIndexed { index, rawName ->
        ManualTemporaryGuestEntry(
            name = rawName.trim(),
            accessIds = temporaryGuestAccessIds.getOrElse(index) { emptySet() },
        )
    }.filter { it.name.isNotEmpty() }
    val temporaryFormValid = temporaryEventDateMillis != null &&
        temporaryArtist.trim().isNotEmpty() &&
        trimmedTempEntries.isNotEmpty()

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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (isCompact) context.getString(R.string.add_guest) else context.getString(R.string.add_new_guest),
                            style = if (isTabletDevice) getTabletConstrainedTitleTypography() else getResponsiveTypography(),
                            fontWeight = FontWeight.Bold
                        )

                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = context.getString(R.string.close))
                        }
                    }

                    TabRow(selectedTabIndex = selectedTab) {
                        Tab(
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 },
                            text = { Text(context.getString(R.string.add_guest_tab_permanent)) }
                        )
                        Tab(
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 },
                            text = { Text(context.getString(R.string.add_guest_tab_temporary)) }
                        )
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(scrollState)
                            .padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(if (isCompact) 12.dp else 16.dp)
                    ) {
                        if (selectedTab == 0) {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text(context.getString(R.string.guest_name)) },
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text(context.getString(R.string.guest_email)) },
                                placeholder = { Text(context.getString(R.string.guest_email_placeholder)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = phoneNumber,
                                onValueChange = { phoneNumber = it },
                                label = { Text(context.getString(R.string.guest_phone_number)) },
                                placeholder = { Text(context.getString(R.string.guest_phone_placeholder)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = invitations,
                                onValueChange = {
                                    if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                                        invitations = it
                                    }
                                },
                                label = { Text(context.getString(R.string.number_of_invitations)) },
                                modifier = Modifier.fillMaxWidth()
                            )

                            ExposedDropdownMenuBox(
                                expanded = showVenueDropdown,
                                onExpandedChange = { showVenueDropdown = !showVenueDropdown }
                            ) {
                                OutlinedTextField(
                                    value = selectedVenueName ?: context.getString(R.string.venue),
                                    onValueChange = { },
                                    readOnly = true,
                                    label = { Text(context.getString(R.string.venue)) },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = showVenueDropdown)
                                    },
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth()
                                )

                                ExposedDropdownMenu(
                                    expanded = showVenueDropdown,
                                    onDismissRequest = { showVenueDropdown = false }
                                ) {
                                    val allOptionText = context.getString(R.string.venue_all)
                                    DropdownMenuItem(
                                        text = { Text(allOptionText) },
                                        onClick = {
                                            selectedVenueName = "BOTH"
                                            showVenueDropdown = false
                                        }
                                    )

                                    activeVenues.forEach { venue ->
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

                            OutlinedTextField(
                                value = notes,
                                onValueChange = { notes = it },
                                label = { Text(context.getString(R.string.notes)) },
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 3
                            )
                            if (barDiscountEnabled) {
                                GuestBarDiscountField(
                                    value = barDiscount,
                                    onValueChange = { barDiscount = it },
                                )
                            }
                            ProfilePhotoFormPicker(
                                enabled = profilePhotosEnabled,
                                currentUrl = "",
                                name = name,
                                pendingBytes = pendingPhotoBytes,
                                onPicked = { pendingPhotoBytes = it },
                                onClearPending = { pendingPhotoBytes = null },
                            )
                        } else {
                            if (guestFormsEnabled) {
                                OutlinedButton(
                                    onClick = onCreateForm,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text(context.getString(R.string.guest_form_create))
                                }
                            }
                            EventDatePickerField(
                                selectedDateMillis = temporaryEventDateMillis,
                                onDateSelected = { temporaryEventDateMillis = it },
                                label = context.getString(R.string.temp_guest_event_date_label),
                            )

                            OutlinedTextField(
                                value = temporaryArtist,
                                onValueChange = { temporaryArtist = it },
                                label = { Text(context.getString(R.string.temp_guest_artist_label)) },
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = temporaryEmergencyPhone,
                                onValueChange = { temporaryEmergencyPhone = it },
                                label = { Text(context.getString(R.string.temp_guest_contact_phone_label)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            if (temporaryFeaturesEnabled) {
                                OutlinedTextField(
                                    value = temporaryContactEmail,
                                    onValueChange = { temporaryContactEmail = it },
                                    label = { Text(context.getString(R.string.temp_guest_contact_email_label)) },
                                    supportingText = {
                                        Text(context.getString(R.string.temp_guest_contact_email_hint))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )
                            }

                            temporaryGuestNames.forEachIndexed { index, guestNameValue ->
                                TemporaryGuestNameCard(
                                    name = guestNameValue,
                                    onNameChange = { temporaryGuestNames[index] = it },
                                    label = if (index == 0) {
                                        context.getString(R.string.guest_name)
                                    } else {
                                        context.getString(R.string.add_guest_additional_name_label, index + 1)
                                    },
                                    venueAccesses = selectedVenueAccesses,
                                    selectedAccessIds = temporaryGuestAccessIds.getOrElse(index) { emptySet() },
                                    onToggleAccess = { accessId ->
                                        val current = temporaryGuestAccessIds.getOrElse(index) { emptySet() }
                                        temporaryGuestAccessIds[index] = if (current.contains(accessId)) {
                                            current - accessId
                                        } else {
                                            current + accessId
                                        }
                                    },
                                    onRemove = if (index > 0) {
                                        {
                                            if (temporaryGuestNames.size > 1) {
                                                temporaryGuestNames.removeAt(index)
                                                temporaryGuestAccessIds.removeAt(index)
                                            }
                                        }
                                    } else {
                                        null
                                    },
                                )
                            }

                            TextButton(
                                onClick = {
                                    temporaryGuestNames.add("")
                                    temporaryGuestAccessIds.add(emptySet())
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(context.getString(R.string.add_guest_add_another_name))
                            }

                            if (temporaryFeaturesEnabled) {
                                TemporaryGuestVenueDropdown(
                                    venues = activeVenues,
                                    selectedVenueName = temporaryVenueName,
                                    onVenueSelected = { picked ->
                                        if (picked != temporaryVenueName) {
                                            // Accesses belong to a venue, so a venue change cannot
                                            // keep grants that no longer exist there.
                                            temporaryGuestAccessIds.indices.forEach {
                                                temporaryGuestAccessIds[it] = emptySet()
                                            }
                                        }
                                        temporaryVenueName = picked
                                    },
                                )
                            }

                            OutlinedTextField(
                                value = temporaryComments,
                                onValueChange = { temporaryComments = it },
                                label = { Text(context.getString(R.string.notes)) },
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 3
                            )

                            if (barDiscountEnabled) {
                                GuestBarDiscountField(
                                    value = temporaryBarDiscount,
                                    onValueChange = { temporaryBarDiscount = it },
                                )
                            }
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(context.getString(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                if (selectedTab == 0) {
                                    val invitationCount = invitations.toIntOrNull() ?: 0
                                    val defaultVenue = activeVenues.firstOrNull()?.name ?: "GROOVE"
                                    onConfirmPermanent(
                                        name,
                                        email,
                                        phoneNumber,
                                        invitationCount,
                                        selectedVenueName ?: defaultVenue,
                                        notes,
                                        if (barDiscountEnabled) barDiscount.toIntOrNull() ?: 0 else 0,
                                        pendingPhotoBytes
                                    )
                                } else {
                                    val millis = temporaryEventDateMillis ?: return@TextButton
                                    onConfirmTemporary(
                                        ManualTemporaryGuestBatch(
                                            eventDateMillis = millis,
                                            artistName = temporaryArtist.trim(),
                                            emergencyContactPhone = temporaryEmergencyPhone.trim(),
                                            comments = temporaryComments.trim(),
                                            guests = trimmedTempEntries,
                                            venueName = temporaryVenueName.orEmpty(),
                                            contactEmail = temporaryContactEmail.trim(),
                                            barDiscountPercent = if (barDiscountEnabled) {
                                                temporaryBarDiscount.toIntOrNull() ?: 0
                                            } else {
                                                0
                                            },
                                        )
                                    )
                                }
                            },
                            enabled = if (selectedTab == 0) {
                                name.isNotBlank()
                            } else {
                                temporaryFormValid
                            }
                        ) {
                            Text(context.getString(R.string.add))
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditGuestDialog(
    guest: Guest,
    venues: List<VenueEntity>,
    onDismiss: () -> Unit,
    onConfirm: (Guest) -> Unit,
    profilePhotosEnabled: Boolean = false,
    barDiscountEnabled: Boolean = false,
    temporaryFeaturesEnabled: Boolean = false,
    venueAccesses: List<VenueAccess> = emptyList(),
    viewModel: EventManagerViewModel? = null,
) {
    val context = LocalContext.current
    val isTemporaryGuest = guest.isTemporaryGuest
    var name by remember { mutableStateOf(guest.name) }
    var email by remember { mutableStateOf(guest.email) }
    var phoneNumber by remember { mutableStateOf(guest.phoneNumber) }
    var invitations by remember { mutableStateOf(guest.invitations.toString()) }
    var selectedVenueName by remember { mutableStateOf<String?>(guest.venueName) }
    var notes by remember { mutableStateOf(guest.notes) }
    var barDiscount by remember { mutableStateOf(guest.barDiscountPercent.toString()) }
    var temporaryArtistName by remember { mutableStateOf(guest.temporaryArtistName) }
    var temporaryContactPhone by remember { mutableStateOf(guest.temporaryContactPhone) }
    var temporaryContactEmail by remember { mutableStateOf(guest.temporaryContactEmail) }
    var temporaryEventDateMillis by remember { mutableStateOf(guest.temporaryEventDate) }
    var temporaryVenueName by remember {
        mutableStateOf(guest.temporaryVenueName.ifBlank { null })
    }
    var temporaryAccessIds by remember { mutableStateOf(guest.temporaryAccessIdSet()) }
    var showVenueDropdown by remember { mutableStateOf(false) }
    var pendingPhotoBytes by remember { mutableStateOf<ByteArray?>(null) }

    val isCompact = isCompactScreen()
    val scrollState = rememberScrollState()
    val isTabletDevice = isTablet()
    
    // Memoize active venues to avoid repeated filtering
    val activeVenues = remember(venues) { venues.filter { it.isActive } }
    val selectedVenueAccesses = remember(venueAccesses, temporaryVenueName) {
        temporaryVenueName?.let { VenueAccessCatalog.forVenue(venueAccesses, it) }.orEmpty()
    }

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
                            text = if (isCompact) context.getString(R.string.edit_guest) else context.getString(R.string.edit_guest_details),
                            style = if (isTabletDevice) getTabletConstrainedTitleTypography() else getResponsiveTypography(),
                            fontWeight = FontWeight.Bold
                        )
                        
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = context.getString(R.string.close))
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
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(context.getString(R.string.guest_name)) },
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (isTemporaryGuest) {
                            OutlinedTextField(
                                value = temporaryArtistName,
                                onValueChange = { temporaryArtistName = it },
                                label = { Text(context.getString(R.string.temp_guest_artist_label)) },
                                modifier = Modifier.fillMaxWidth()
                            )

                            EventDatePickerField(
                                selectedDateMillis = temporaryEventDateMillis,
                                onDateSelected = { temporaryEventDateMillis = it },
                                label = context.getString(R.string.temp_guest_event_date_label),
                            )

                            OutlinedTextField(
                                value = temporaryContactPhone,
                                onValueChange = { temporaryContactPhone = it },
                                label = { Text(context.getString(R.string.temp_guest_contact_phone_label)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            if (temporaryFeaturesEnabled) {
                                OutlinedTextField(
                                    value = temporaryContactEmail,
                                    onValueChange = { temporaryContactEmail = it },
                                    label = { Text(context.getString(R.string.temp_guest_contact_email_label)) },
                                    supportingText = {
                                        Text(context.getString(R.string.temp_guest_contact_email_hint))
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true
                                )

                                TemporaryGuestVenueDropdown(
                                    venues = activeVenues,
                                    selectedVenueName = temporaryVenueName,
                                    onVenueSelected = { picked ->
                                        if (picked != temporaryVenueName) {
                                            temporaryAccessIds = emptySet()
                                        }
                                        temporaryVenueName = picked
                                    },
                                )

                                if (selectedVenueAccesses.isNotEmpty()) {
                                    Text(
                                        text = context.getString(R.string.temp_guest_access_person_label),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    TemporaryGuestAccessSelector(
                                        venueAccesses = selectedVenueAccesses,
                                        selectedAccessIds = temporaryAccessIds,
                                        onToggleAccess = { accessId ->
                                            temporaryAccessIds = if (temporaryAccessIds.contains(accessId)) {
                                                temporaryAccessIds - accessId
                                            } else {
                                                temporaryAccessIds + accessId
                                            }
                                        },
                                    )
                                }
                            }
                        } else {
                            OutlinedTextField(
                                value = email,
                                onValueChange = { email = it },
                                label = { Text(context.getString(R.string.guest_email)) },
                                placeholder = { Text(context.getString(R.string.guest_email_placeholder)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = phoneNumber,
                                onValueChange = { phoneNumber = it },
                                label = { Text(context.getString(R.string.guest_phone_number)) },
                                placeholder = { Text(context.getString(R.string.guest_phone_placeholder)) },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = invitations,
                                onValueChange = {
                                    if (it.isEmpty() || it.all { char -> char.isDigit() }) {
                                        invitations = it
                                    }
                                },
                                label = { Text(context.getString(R.string.number_of_invitations)) },
                                modifier = Modifier.fillMaxWidth()
                            )

                            ExposedDropdownMenuBox(
                                expanded = showVenueDropdown,
                                onExpandedChange = { showVenueDropdown = !showVenueDropdown }
                            ) {
                                OutlinedTextField(
                                    value = selectedVenueName ?: context.getString(R.string.venue),
                                    onValueChange = { },
                                    readOnly = true,
                                    label = { Text(context.getString(R.string.venue)) },
                                    trailingIcon = {
                                        ExposedDropdownMenuDefaults.TrailingIcon(expanded = showVenueDropdown)
                                    },
                                    modifier = Modifier
                                        .menuAnchor()
                                        .fillMaxWidth()
                                )

                                ExposedDropdownMenu(
                                    expanded = showVenueDropdown,
                                    onDismissRequest = { showVenueDropdown = false }
                                ) {
                                    val allOptionText = context.getString(R.string.venue_all)
                                    DropdownMenuItem(
                                        text = { Text(allOptionText) },
                                        onClick = {
                                            selectedVenueName = "BOTH"
                                            showVenueDropdown = false
                                        }
                                    )

                                    // Add individual venues (only active ones)
                                    activeVenues.forEach { venue ->
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

                        OutlinedTextField(
                            value = notes,
                            onValueChange = { notes = it },
                            label = { Text(context.getString(R.string.notes)) },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3
                        )
                        if (barDiscountEnabled && (!isTemporaryGuest || temporaryFeaturesEnabled)) {
                            GuestBarDiscountField(
                                value = barDiscount,
                                onValueChange = { barDiscount = it },
                            )
                        }
                        if (!isTemporaryGuest) {
                            ProfilePhotoFormPicker(
                                enabled = profilePhotosEnabled,
                                currentUrl = guest.profilePhotoUrl,
                                currentPath = guest.resolvedProfilePhotoPath(),
                                name = name,
                                pendingBytes = pendingPhotoBytes,
                                onPicked = { pendingPhotoBytes = it },
                                onClearPending = { pendingPhotoBytes = null },
                                onRemoveExisting = { viewModel?.removeProfilePhotoForGuest(guest) },
                            )
                        }
                    }
                    
                    // Footer
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text(context.getString(R.string.cancel))
                        }
                        TextButton(
                            onClick = {
                                val updatedGuest = if (isTemporaryGuest) {
                                    guest.copy(
                                        name = name,
                                        notes = notes,
                                        temporaryArtistName = temporaryArtistName,
                                        temporaryContactPhone = temporaryContactPhone,
                                        temporaryEventDate = temporaryEventDateMillis,
                                        temporaryContactEmail = if (temporaryFeaturesEnabled) {
                                            temporaryContactEmail.trim()
                                        } else {
                                            guest.temporaryContactEmail
                                        },
                                        temporaryVenueName = if (temporaryFeaturesEnabled) {
                                            temporaryVenueName.orEmpty()
                                        } else {
                                            guest.temporaryVenueName
                                        },
                                        temporaryAccessIds = if (temporaryFeaturesEnabled) {
                                            encodeTemporaryAccessIds(temporaryAccessIds)
                                        } else {
                                            guest.temporaryAccessIds
                                        },
                                        barDiscountPercent = if (barDiscountEnabled && temporaryFeaturesEnabled) {
                                            barDiscount.toIntOrNull() ?: 0
                                        } else {
                                            guest.barDiscountPercent
                                        },
                                    )
                                } else {
                                    val invitationCount = invitations.toIntOrNull() ?: 1
                                    val defaultVenue = activeVenues.firstOrNull()?.name ?: "GROOVE"
                                    guest.copy(
                                        name = name,
                                        email = email,
                                        phoneNumber = phoneNumber,
                                        lastNameAbbreviation = "", // Permanent guests don't have abbreviations
                                        invitations = invitationCount,
                                        venueName = selectedVenueName ?: defaultVenue,
                                        notes = notes,
                                        barDiscountPercent = if (barDiscountEnabled) {
                                            barDiscount.toIntOrNull() ?: 0
                                        } else {
                                            guest.barDiscountPercent
                                        }
                                    )
                                }
                                onConfirm(updatedGuest)
                                if (!isTemporaryGuest) {
                                    pendingPhotoBytes?.let { viewModel?.uploadProfilePhotoForGuest(updatedGuest, it) }
                                }
                            },
                            enabled = name.isNotBlank() && (!isTemporaryGuest || temporaryEventDateMillis != null)
                        ) {
                            Text(context.getString(R.string.update_guest))
                        }
                    }
                }
            }
        }
    }
}

