package com.eventmanager.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.eventmanager.app.data.models.*
import com.eventmanager.app.data.models.ManualTemporaryGuestBatch
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.components.GuestBarDiscountField
import com.eventmanager.app.ui.components.GuestDetailPanel
import com.eventmanager.app.ui.components.GuestVenueDropdownField
import com.eventmanager.app.ui.components.ProfilePhotoFormPicker
import com.eventmanager.app.ui.components.rememberGuestBarDiscountEnabled
import com.eventmanager.app.ui.components.rememberProfilePhotosUploadEnabled
import com.eventmanager.app.ui.components.rememberTemporaryGuestFeatures
import com.eventmanager.app.ui.components.EventDatePickerField
import com.eventmanager.app.ui.components.GuestFormCreatorDialog
import com.eventmanager.app.ui.components.GuestFormPendingCard
import com.eventmanager.app.ui.components.TemporaryGuestAccessSelector
import com.eventmanager.app.ui.components.TemporaryGuestNameCard
import com.eventmanager.app.ui.components.TemporaryGuestVenueDropdown
import com.eventmanager.app.ui.components.temporaryEntryValidatedLabel
import com.eventmanager.app.ui.components.SearchBarWithFilter
import com.eventmanager.app.ui.components.VolunteerBenefitsPanel
import com.eventmanager.app.data.models.BenefitCalculator
import com.eventmanager.app.data.models.AccountHolderType
import com.eventmanager.app.data.utils.formatMoney
import com.eventmanager.app.data.remote.MultiOrgMerge
import com.eventmanager.app.data.remote.resolvedProfilePhotoPath
import com.eventmanager.app.ui.utils.GuestListDefaultZoneId
import com.eventmanager.app.ui.components.OrgColorDot
import com.eventmanager.app.ui.utils.VenueOption
import com.eventmanager.app.ui.utils.generateVenueFilterOptions
import com.eventmanager.app.ui.utils.getVenueDisplayString
import com.eventmanager.app.ui.utils.rememberGuestListEffectiveToday
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import org.jetbrains.compose.resources.stringResource
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

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
    readOnly: Boolean,
    searchFocusTick: Int,
    viewModel: EventManagerViewModel?
) {
    val platformContext = LocalPlatformContext.current
    val settingsManager = remember(platformContext) { settingsManagerFor(platformContext) }
    val currencyCode = remember { settingsManager.getCurrencyCode() }
    val accountTransfers by viewModel?.accountTransfers?.collectAsState()
        ?: remember { mutableStateOf(emptyList()) }
    val zone = GuestListDefaultZoneId
    val offsetHours = remember(settingsManager) { settingsManager.getDateChangeOffsetHours() }
    val effectiveToday = rememberGuestListEffectiveToday(zone = zone, offsetHours = offsetHours)
    val temporaryGuestFeatures = rememberTemporaryGuestFeatures(viewModel)

    var searchQuery by remember { mutableStateOf("") }
    var selectedVenueName by remember { mutableStateOf<String?>(null) }
    var selectedFilter by remember { mutableStateOf<String?>(null) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<Guest?>(null) }
    var guestToDelete by remember { mutableStateOf<Guest?>(null) }
    var showGuestDetail by remember { mutableStateOf<Guest?>(null) }
    var showVolunteerBenefits by remember { mutableStateOf<Volunteer?>(null) }
    var showTemporaryGuestsTimeline by remember { mutableStateOf(false) }
    var showGuestFormCreator by remember { mutableStateOf(false) }
    val guestFormsEnabled by viewModel?.guestFormsEnabled?.collectAsState()
        ?: remember { mutableStateOf(false) }

    val filterPermanentGuests = stringResource(Res.string.filter_permanent_guests)
    val filterTemporaryGuests = stringResource(Res.string.filter_temporary_guests)
    val filterVolunteerBenefits = stringResource(Res.string.filter_volunteer_benefits)
    val guestFilterOptions = remember(filterPermanentGuests, filterTemporaryGuests, filterVolunteerBenefits) {
        listOf(filterPermanentGuests, filterTemporaryGuests, filterVolunteerBenefits)
    }

    val volunteersMap = remember(volunteers) { volunteers.associateBy { it.id } }
    val venueFilterOptions = generateVenueFilterOptions(venues)
    val allOrgsMode = viewModel?.isFirebaseAllOrgsMode() == true

    val filteredGuests = remember(
        guests, selectedVenueName, searchQuery, selectedFilter, volunteersMap, effectiveToday,
        filterPermanentGuests, filterTemporaryGuests, filterVolunteerBenefits, allOrgsMode, venues,
    ) {
        val q = searchQuery.trim()
        val hasSearch = q.isNotEmpty()
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
                    guest.name.contains(q, ignoreCase = true) ||
                        guest.email.contains(q, ignoreCase = true) ||
                        guest.phoneNumber.contains(q, ignoreCase = true) ||
                        guest.notes.contains(q, ignoreCase = true) ||
                        guest.nfcCardUid.contains(q, ignoreCase = true) ||
                        guest.nanoId.contains(q, ignoreCase = true) ||
                        guest.venueName.contains(q, ignoreCase = true)
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

    val totalInvitations = remember(filteredGuests) { filteredGuests.sumOf { it.invitations } }

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

    val pagePadding = 16.dp
    val itemSpacing = 8.dp

    val guestListItems: LazyListScope.() -> Unit = {
        items(filteredGuests, key = { it.id }) { guest ->
            GuestListRow(
                guest = guest,
                venues = venues,
                readOnly = readOnly,
                onClick = {
                    if (guest.isVolunteerBenefit && guest.volunteerId != null) {
                        volunteersMap[guest.volunteerId]?.let { showVolunteerBenefits = it }
                    } else {
                        showGuestDetail = guest
                    }
                },
                onEdit = { showEditDialog = guest },
                onDelete = { guestToDelete = guest },
                viewModel = viewModel,
            )
        }
    }

    when (scrollBehavior) {
        SettingsManager.HEADER_PINNED -> {
            Column(Modifier.fillMaxSize().padding(pagePadding)) {
                GuestListHeaderSection(readOnly = readOnly)
                GuestListVenueChipsRow(
                    venueFilterOptions = venueFilterOptions,
                    selectedVenueName = selectedVenueName,
                    onVenueSelected = { selectedVenueName = it },
                )
                Spacer(Modifier.height(itemSpacing))
                GuestListFilterBar(
                    searchQuery = searchQuery,
                    onSearchQueryChange = { searchQuery = it },
                    guestFilterOptions = guestFilterOptions,
                    selectedFilter = selectedFilter,
                    onFilterChange = { selectedFilter = it },
                    searchFocusTick = searchFocusTick,
                )
                Spacer(Modifier.height(itemSpacing))
                GuestListStatsRow(
                    filteredGuestCount = filteredGuests.size,
                    totalInvitations = totalInvitations,
                    readOnly = readOnly,
                    onOpenTimeline = {
                        onRefreshTemporaryGuests()
                        showTemporaryGuestsTimeline = true
                    },
                    onAddGuest = { showAddDialog = true },
                )
                Spacer(Modifier.height(itemSpacing))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(itemSpacing),
                    modifier = Modifier.weight(1f),
                ) {
                    if (!readOnly && viewModel != null) {
                        item(key = "guest_form_pending") {
                            GuestFormPendingCard(viewModel = viewModel, isPhone = false)
                        }
                    }
                    guestListItems()
                }
            }
        }
        SettingsManager.STICKY_FILTERS -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pagePadding),
                verticalArrangement = Arrangement.spacedBy(itemSpacing),
            ) {
                item(key = "header") {
                    GuestListHeaderSection(readOnly = readOnly)
                }
                item(key = "venue_filters") {
                    GuestListVenueChipsRow(
                        venueFilterOptions = venueFilterOptions,
                        selectedVenueName = selectedVenueName,
                        onVenueSelected = { selectedVenueName = it },
                    )
                }
                stickyHeader(key = "sticky_filters") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(bottom = itemSpacing),
                    ) {
                        GuestListFilterBar(
                            searchQuery = searchQuery,
                            onSearchQueryChange = { searchQuery = it },
                            guestFilterOptions = guestFilterOptions,
                            selectedFilter = selectedFilter,
                            onFilterChange = { selectedFilter = it },
                            searchFocusTick = searchFocusTick,
                        )
                    }
                }
                item(key = "stats") {
                    GuestListStatsRow(
                        filteredGuestCount = filteredGuests.size,
                        totalInvitations = totalInvitations,
                        readOnly = readOnly,
                        onOpenTimeline = {
                            onRefreshTemporaryGuests()
                            showTemporaryGuestsTimeline = true
                        },
                        onAddGuest = { showAddDialog = true },
                    )
                }
                if (!readOnly && viewModel != null) {
                    item(key = "guest_form_pending") {
                        GuestFormPendingCard(viewModel = viewModel, isPhone = false)
                    }
                }
                guestListItems()
            }
        }
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(pagePadding),
                verticalArrangement = Arrangement.spacedBy(itemSpacing),
            ) {
                item(key = "header") {
                    GuestListHeaderSection(readOnly = readOnly)
                }
                item(key = "venue_filters") {
                    GuestListVenueChipsRow(
                        venueFilterOptions = venueFilterOptions,
                        selectedVenueName = selectedVenueName,
                        onVenueSelected = { selectedVenueName = it },
                    )
                }
                item(key = "filters") {
                    GuestListFilterBar(
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        guestFilterOptions = guestFilterOptions,
                        selectedFilter = selectedFilter,
                        onFilterChange = { selectedFilter = it },
                        searchFocusTick = searchFocusTick,
                    )
                }
                item(key = "stats") {
                    GuestListStatsRow(
                        filteredGuestCount = filteredGuests.size,
                        totalInvitations = totalInvitations,
                        readOnly = readOnly,
                        onOpenTimeline = {
                            onRefreshTemporaryGuests()
                            showTemporaryGuestsTimeline = true
                        },
                        onAddGuest = { showAddDialog = true },
                    )
                }
                if (!readOnly && viewModel != null) {
                    item(key = "guest_form_pending") {
                        GuestFormPendingCard(viewModel = viewModel, isPhone = false)
                    }
                }
                guestListItems()
            }
        }
    }

    if (showTemporaryGuestsTimeline) {
        DesktopTemporaryGuestsTimelineDialog(
            guests = guests,
            zone = zone,
            offsetHours = offsetHours,
            onGuestClick = { guest ->
                showTemporaryGuestsTimeline = false
                showGuestDetail = guest
            },
            onDismiss = { showTemporaryGuestsTimeline = false },
            temporaryFeaturesEnabled = temporaryGuestFeatures.enabled,
        )
    }

    if (!readOnly && showAddDialog) {
        DesktopAddGuestDialog(
            venues = venues,
            profilePhotosEnabled = rememberProfilePhotosUploadEnabled(viewModel),
            barDiscountEnabled = rememberGuestBarDiscountEnabled(viewModel),
            temporaryFeaturesEnabled = temporaryGuestFeatures.enabled,
            venueAccesses = temporaryGuestFeatures.venueAccesses,
            guestFormsEnabled = guestFormsEnabled,
            onCreateForm = {
                showAddDialog = false
                showGuestFormCreator = true
            },
            onDismiss = { showAddDialog = false },
            onConfirmPermanent = { guest, photo ->
                onAddGuest(guest, photo)
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

    showEditDialog?.let { guest ->
        DesktopEditGuestDialog(
            guest = guest,
            venues = venues,
            profilePhotosEnabled = rememberProfilePhotosUploadEnabled(viewModel),
            barDiscountEnabled = rememberGuestBarDiscountEnabled(viewModel),
            temporaryFeaturesEnabled = temporaryGuestFeatures.enabled,
            venueAccesses = temporaryGuestFeatures.venueAccesses,
            viewModel = viewModel,
            onDismiss = { showEditDialog = null },
            onConfirm = { updated ->
                onUpdateGuest(updated)
                showEditDialog = null
            }
        )
    }

    guestToDelete?.let { guest ->
        AlertDialog(
            onDismissRequest = { guestToDelete = null },
            title = { Text(stringResource(Res.string.delete_guest)) },
            text = { Text(guest.name) },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteGuest(guest)
                    guestToDelete = null
                }) { Text(stringResource(Res.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { guestToDelete = null }) { Text(stringResource(Res.string.cancel)) }
            }
        )
    }

    showGuestDetail?.let { guest ->
        Dialog(onDismissRequest = { showGuestDetail = null }) {
            GuestDetailPanel(
                guest = guest,
                venues = venues,
                readOnly = readOnly,
                onEdit = { if (!readOnly) { showGuestDetail = null; showEditDialog = it } },
                onAssignNfcUid = { g, uid ->
                    onUpdateGuest(g.copy(nfcCardUid = uid, lastModified = System.currentTimeMillis()))
                    showGuestDetail = g.copy(nfcCardUid = uid)
                },
                onDelete = { if (!readOnly) { showGuestDetail = null; guestToDelete = it } },
                onClose = { showGuestDetail = null },
                accountBalance = viewModel?.getGuestAccountBalance(guest.nanoId) ?: 0.0,
                currencyCode = currencyCode,
                recentTransfers = accountTransfers.filter {
                    it.holderType == AccountHolderType.GUEST && it.holderId == guest.nanoId
                },
                onManualAccountAdjust = if (viewModel != null && !readOnly) { amount, note ->
                    viewModel.applyManualAccountAdjustment(
                        AccountHolderType.GUEST,
                        guest.nanoId,
                        guest.name,
                        amount,
                        note
                    )
                } else null,
                viewModel = viewModel,
                modifier = Modifier.fillMaxWidth().heightIn(min = 400.dp, max = 700.dp)
            )
        }
    }

    showVolunteerBenefits?.let { volunteer ->
        val benefitStatus = remember(volunteer.id, jobs, jobTypeConfigs) {
            BenefitCalculator.calculateVolunteerBenefitStatus(volunteer, jobs, jobTypeConfigs)
        }
        val volunteerJobs = remember(volunteer.id, jobs) { jobs.filter { it.volunteerId == volunteer.id } }
        Dialog(onDismissRequest = { showVolunteerBenefits = null }) {
            VolunteerBenefitsPanel(
                volunteer = volunteer,
                volunteerBenefitStatus = benefitStatus,
                volunteerJobs = volunteerJobs,
                venues = venues,
                jobTypeConfigs = jobTypeConfigs,
                readOnly = readOnly,
                onClose = { showVolunteerBenefits = null },
                onConfirmEntry = onConfirmEntry,
                onAssignNfcUid = if (readOnly) null else { v, uid ->
                    onUpdateVolunteer(v.copy(nfcCardUid = uid, lastModified = System.currentTimeMillis()))
                    showVolunteerBenefits = v.copy(nfcCardUid = uid)
                },
                accountBalance = viewModel?.getVolunteerAccountBalance(volunteer.id) ?: 0.0,
                currencyCode = currencyCode,
                recentTransfers = accountTransfers.filter {
                    it.holderType == AccountHolderType.VOLUNTEER && it.holderId == volunteer.id
                },
                onManualAccountAdjust = null,
                viewModel = viewModel,
                modifier = Modifier.fillMaxWidth().heightIn(min = 400.dp, max = 700.dp)
            )
        }
    }
}

@Composable
private fun GuestListHeaderSection(readOnly: Boolean) {
    if (!readOnly) {
        Text(
            text = stringResource(Res.string.guest_list_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(Res.string.guest_list_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun GuestListVenueChipsRow(
    venueFilterOptions: List<VenueOption>,
    selectedVenueName: String?,
    onVenueSelected: (String?) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        venueFilterOptions.forEach { venueOption ->
            FilterChip(
                onClick = {
                    onVenueSelected(
                        if (selectedVenueName == venueOption.venueName) null else venueOption.venueName,
                    )
                },
                label = { Text(venueOption.displayName) },
                selected = selectedVenueName == venueOption.venueName,
                leadingIcon = {
                    Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(16.dp))
                },
            )
        }
    }
}

@Composable
private fun GuestListFilterBar(
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    guestFilterOptions: List<String>,
    selectedFilter: String?,
    onFilterChange: (String?) -> Unit,
    searchFocusTick: Int,
) {
    SearchBarWithFilter(
        searchText = searchQuery,
        onSearchTextChange = onSearchQueryChange,
        placeholder = stringResource(Res.string.search_guests_placeholder),
        filterOptions = guestFilterOptions,
        selectedFilter = selectedFilter,
        onFilterChange = onFilterChange,
        requestFocusTrigger = searchFocusTick,
    )
}

@Composable
private fun GuestListStatsRow(
    filteredGuestCount: Int,
    totalInvitations: Int,
    readOnly: Boolean,
    onOpenTimeline: () -> Unit,
    onAddGuest: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "${stringResource(Res.string.guests_count)}: $filteredGuestCount",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "${stringResource(Res.string.invitations_count)}: $totalInvitations",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedButton(onClick = onOpenTimeline) {
                Icon(Icons.Default.History, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(stringResource(Res.string.temp_guest_timeline_button))
            }
            if (!readOnly) {
                FilledTonalButton(onClick = onAddGuest) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.add_guest))
                }
            }
        }
    }
}

@Composable
private fun GuestListRow(
    guest: Guest,
    venues: List<VenueEntity>,
    readOnly: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    viewModel: EventManagerViewModel? = null,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(guest.name, fontWeight = FontWeight.SemiBold)
                    if (viewModel?.isFirebaseAllOrgsMode() == true && guest.firebaseOrgId.isNotBlank()) {
                        OrgColorDot(orgId = guest.firebaseOrgId, viewModel = viewModel, size = 8.dp)
                    }
                    when {
                        guest.isVolunteerBenefit -> {
                            AssistChip(
                                onClick = {},
                                label = { Text(stringResource(Res.string.volunteer_label), style = MaterialTheme.typography.labelSmall) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            )
                        }
                        guest.isTemporaryGuest -> {
                            AssistChip(
                                onClick = {},
                                label = { Text(stringResource(Res.string.temp_guest_chip_label), style = MaterialTheme.typography.labelSmall) },
                                colors = AssistChipDefaults.assistChipColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                )
                            )
                        }
                    }
                }
                if (guest.isTemporaryGuest && guest.temporaryArtistName.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(Icons.Default.Mic, contentDescription = null, modifier = Modifier.size(14.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(guest.temporaryArtistName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    Text(
                        "${getVenueDisplayString(guest.venueName, venues)} • ${stringResource(Res.string.invitations_text, guest.invitations)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (guest.email.isNotBlank()) {
                    Text(guest.email, style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!readOnly) {
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "Actions")
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.edit)) },
                            onClick = { menuOpen = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(Res.string.delete)) },
                            onClick = { menuOpen = false; onDelete() }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopTemporaryGuestsTimelineDialog(
    guests: List<Guest>,
    zone: java.time.ZoneId,
    offsetHours: Int,
    onGuestClick: (Guest) -> Unit,
    onDismiss: () -> Unit,
    /** Firebase only: shows the Tonight tracking tab (entered / total). */
    temporaryFeaturesEnabled: Boolean = false,
) {
    val effectiveToday = rememberGuestListEffectiveToday(zone = zone, offsetHours = offsetHours)
    val dateFormatter = remember { DateTimeFormatter.ofPattern("yyyy-MM-dd") }

    val temporaryGuestsWithDate = remember(guests, zone) {
        guests.asSequence()
            .filter { it.isTemporaryGuest }
            .mapNotNull { guest ->
                val ts = guest.temporaryEventDate ?: return@mapNotNull null
                guest to Instant.ofEpochMilli(ts).atZone(zone).toLocalDate()
            }
            .toList()
    }

    val tonightEntered = remember(temporaryGuestsWithDate, effectiveToday, temporaryFeaturesEnabled) {
        if (!temporaryFeaturesEnabled) {
            emptyList()
        } else {
            temporaryGuestsWithDate
                .filter { (guest, date) -> date == effectiveToday && guest.temporaryEntryValidated }
                .map { it.first }
                .sortedByDescending { it.temporaryEntryValidatedAt }
        }
    }
    val tonightRemaining = remember(temporaryGuestsWithDate, effectiveToday, temporaryFeaturesEnabled) {
        if (!temporaryFeaturesEnabled) {
            emptyList()
        } else {
            temporaryGuestsWithDate
                .filter { (guest, date) -> date == effectiveToday && !guest.temporaryEntryValidated }
                .map { it.first }
                .sortedBy { it.name.lowercase() }
        }
    }
    val tonightTotal = tonightEntered.size + tonightRemaining.size

    // With Firebase tracking, tonight lives only in the Tonight tab. Without it, today stays in Future.
    val (futureGuests, pastGuests) = remember(temporaryGuestsWithDate, effectiveToday, temporaryFeaturesEnabled) {
        val futurePred: (java.time.LocalDate) -> Boolean = if (temporaryFeaturesEnabled) {
            { date -> date.isAfter(effectiveToday) }
        } else {
            { date -> !date.isBefore(effectiveToday) }
        }
        val future = temporaryGuestsWithDate
            .filter { (_, date) -> futurePred(date) }
            .map { it.first }
            .sortedWith(compareBy<Guest> { it.temporaryEventDate ?: Long.MAX_VALUE }.thenBy { it.name.lowercase() })
        val past = temporaryGuestsWithDate
            .filter { (_, date) -> date.isBefore(effectiveToday) }
            .map { it.first }
            .sortedWith(compareByDescending<Guest> { it.temporaryEventDate ?: Long.MIN_VALUE }.thenBy { it.name.lowercase() })
        future to past
    }

    val tabTonight = 0
    val tabFuture = if (temporaryFeaturesEnabled) 1 else 0
    val tabHistory = if (temporaryFeaturesEnabled) 2 else 1
    var selectedTab by remember(temporaryFeaturesEnabled) {
        mutableStateOf(if (temporaryFeaturesEnabled) tabTonight else tabFuture)
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().heightIn(min = 420.dp, max = 640.dp)) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(Res.string.temp_guest_timeline_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) }
                }
                Spacer(Modifier.height(8.dp))
                TabRow(selectedTabIndex = selectedTab) {
                    if (temporaryFeaturesEnabled) {
                        Tab(
                            selected = selectedTab == tabTonight,
                            onClick = { selectedTab = tabTonight },
                            text = { Text(stringResource(Res.string.temp_guest_tab_tonight)) },
                        )
                    }
                    Tab(
                        selected = selectedTab == tabFuture,
                        onClick = { selectedTab = tabFuture },
                        text = { Text(stringResource(Res.string.temp_guest_tab_future)) },
                    )
                    Tab(
                        selected = selectedTab == tabHistory,
                        onClick = { selectedTab = tabHistory },
                        text = { Text(stringResource(Res.string.temp_guest_tab_history)) },
                    )
                }
                Spacer(Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (temporaryFeaturesEnabled && selectedTab == tabTonight) {
                        item {
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                ),
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        stringResource(Res.string.temp_guest_tonight_title),
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    Text(
                                        stringResource(Res.string.temp_guest_tonight_tracking),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                    Text(
                                        stringResource(
                                            Res.string.temp_guest_validated_count,
                                            tonightEntered.size,
                                            tonightTotal,
                                        ),
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                                    )
                                }
                            }
                        }
                        item {
                            Text(
                                stringResource(Res.string.temp_guest_section_validated),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (tonightEntered.isEmpty()) {
                            item {
                                Text(
                                    stringResource(Res.string.temp_guest_none_validated),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(tonightEntered, key = { "entered_${it.id}_${it.nanoId}" }) { guest ->
                                DesktopTimelineGuestRow(
                                    guest = guest,
                                    zone = zone,
                                    dateFormatter = dateFormatter,
                                    onClick = onGuestClick,
                                    showEntryValidation = true,
                                )
                            }
                        }
                        item { Spacer(Modifier.height(8.dp)) }
                        item {
                            Text(
                                stringResource(Res.string.temp_guest_section_remaining),
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (tonightRemaining.isEmpty()) {
                            item {
                                Text(
                                    stringResource(Res.string.temp_guest_none_remaining),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(tonightRemaining, key = { "remain_${it.id}_${it.nanoId}" }) { guest ->
                                DesktopTimelineGuestRow(guest, zone, dateFormatter, onGuestClick)
                            }
                        }
                    } else if (selectedTab == tabFuture) {
                        item {
                            Text(
                                "${stringResource(Res.string.temp_guest_section_upcoming)} (${futureGuests.size})",
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (futureGuests.isEmpty()) {
                            item {
                                Text(
                                    stringResource(Res.string.temp_guest_none_upcoming),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(futureGuests, key = { "future_${it.id}_${it.temporaryEventDate}" }) { guest ->
                                DesktopTimelineGuestRow(guest, zone, dateFormatter, onGuestClick)
                            }
                        }
                    } else {
                        item {
                            Text(
                                "${stringResource(Res.string.temp_guest_section_past)} (${pastGuests.size})",
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        if (pastGuests.isEmpty()) {
                            item {
                                Text(
                                    stringResource(Res.string.temp_guest_none_past),
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            items(pastGuests, key = { "past_${it.id}_${it.temporaryEventDate}" }) { guest ->
                                DesktopTimelineGuestRow(guest, zone, dateFormatter, onGuestClick)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DesktopTimelineGuestRow(
    guest: Guest,
    zone: java.time.ZoneId,
    dateFormatter: DateTimeFormatter,
    onClick: (Guest) -> Unit,
    /** Shows the entry time instead of the event date, for the "Already in" section. */
    showEntryValidation: Boolean = false,
) {
    val eventDate = guest.temporaryEventDate?.let {
        Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
    }
    val entryTimeText = temporaryEntryValidatedLabel(guest)
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onClick(guest) },
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (showEntryValidation) {
                    Icon(
                        Icons.Default.CheckCircle,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Text(guest.name, fontWeight = FontWeight.SemiBold)
            }
            if (showEntryValidation && entryTimeText != null) {
                Text(
                    stringResource(Res.string.temp_guest_entry_validated_at, entryTimeText),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else if (eventDate != null) {
                Text(eventDate.format(dateFormatter), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (guest.temporaryArtistName.isNotEmpty()) {
                Text(guest.temporaryArtistName, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DesktopAddGuestDialog(
    venues: List<VenueEntity>,
    profilePhotosEnabled: Boolean = false,
    barDiscountEnabled: Boolean = false,
    temporaryFeaturesEnabled: Boolean = false,
    venueAccesses: List<VenueAccess> = emptyList(),
    guestFormsEnabled: Boolean = false,
    onCreateForm: () -> Unit = {},
    onDismiss: () -> Unit,
    onConfirmPermanent: (Guest, ByteArray?) -> Unit,
    onConfirmTemporary: (ManualTemporaryGuestBatch) -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val activeVenues = remember(venues) { venues.filter { it.isActive } }

    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var invitations by remember { mutableStateOf("1") }
    var selectedVenueName by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf("") }
    var barDiscount by remember { mutableStateOf("0") }
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

    Dialog(onDismissRequest = onDismiss) {
        Card(modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp)) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    stringResource(Res.string.add_guest),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(16.dp)
                )
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text(stringResource(Res.string.add_guest_tab_permanent)) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text(stringResource(Res.string.add_guest_tab_temporary)) }
                    )
                }
                Column(
                    modifier = Modifier
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selectedTab == 0) {
                        OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.name)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(email, { email = it }, label = { Text(stringResource(Res.string.email)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(phone, { phone = it }, label = { Text(stringResource(Res.string.phone)) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(invitations, { invitations = it }, label = { Text(stringResource(Res.string.invitations)) }, modifier = Modifier.fillMaxWidth())
                        GuestVenueDropdownField(
                            venues = venues,
                            selectedVenueName = selectedVenueName,
                            onVenueSelected = { selectedVenueName = it },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        OutlinedTextField(notes, { notes = it }, label = { Text(stringResource(Res.string.notes)) }, modifier = Modifier.fillMaxWidth())
                        if (barDiscountEnabled) {
                            GuestBarDiscountField(barDiscount, { barDiscount = it })
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
                                Text(stringResource(Res.string.guest_form_create))
                            }
                        }
                        EventDatePickerField(
                            selectedDateMillis = temporaryEventDateMillis,
                            onDateSelected = { temporaryEventDateMillis = it },
                        )
                        OutlinedTextField(
                            temporaryArtist,
                            { temporaryArtist = it },
                            label = { Text(stringResource(Res.string.temp_guest_artist_label)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            temporaryEmergencyPhone,
                            { temporaryEmergencyPhone = it },
                            label = { Text(stringResource(Res.string.temp_guest_contact_phone_label)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (temporaryFeaturesEnabled) {
                            OutlinedTextField(
                                temporaryContactEmail,
                                { temporaryContactEmail = it },
                                label = { Text(stringResource(Res.string.temp_guest_contact_email_label)) },
                                supportingText = {
                                    Text(stringResource(Res.string.temp_guest_contact_email_hint))
                                },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        temporaryGuestNames.forEachIndexed { index, guestNameValue ->
                            TemporaryGuestNameCard(
                                name = guestNameValue,
                                onNameChange = { temporaryGuestNames[index] = it },
                                label = if (index == 0) {
                                    stringResource(Res.string.name)
                                } else {
                                    stringResource(Res.string.add_guest_additional_name_label, index + 1)
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
                            Spacer(Modifier.width(8.dp))
                            Text(stringResource(Res.string.add_guest_add_another_name))
                        }
                        if (temporaryFeaturesEnabled) {
                            TemporaryGuestVenueDropdown(
                                venues = venues,
                                selectedVenueName = temporaryVenueName,
                                onVenueSelected = { picked ->
                                    if (picked != temporaryVenueName) {
                                        temporaryGuestAccessIds.indices.forEach {
                                            temporaryGuestAccessIds[it] = emptySet()
                                        }
                                    }
                                    temporaryVenueName = picked
                                },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        OutlinedTextField(
                            temporaryComments,
                            { temporaryComments = it },
                            label = { Text(stringResource(Res.string.notes)) },
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (barDiscountEnabled) {
                            GuestBarDiscountField(temporaryBarDiscount, { temporaryBarDiscount = it })
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) }
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (selectedTab == 0) {
                                val defaultVenue = activeVenues.firstOrNull()?.name ?: "GROOVE"
                                onConfirmPermanent(
                                    Guest(
                                        name = name.trim(),
                                        email = email.trim(),
                                        phoneNumber = phone.trim(),
                                        invitations = invitations.toIntOrNull() ?: 0,
                                        venueName = selectedVenueName ?: defaultVenue,
                                        notes = notes.trim(),
                                        barDiscountPercent = if (barDiscountEnabled) barDiscount.toIntOrNull() ?: 0 else 0
                                    ),
                                    pendingPhotoBytes,
                                )
                            } else {
                                val millis = temporaryEventDateMillis ?: return@Button
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
                        enabled = if (selectedTab == 0) name.isNotBlank() && selectedVenueName != null else temporaryFormValid
                    ) { Text(stringResource(Res.string.add)) }
                }
            }
        }
    }
}

@Composable
private fun DesktopEditGuestDialog(
    guest: Guest,
    venues: List<VenueEntity>,
    profilePhotosEnabled: Boolean = false,
    barDiscountEnabled: Boolean = false,
    temporaryFeaturesEnabled: Boolean = false,
    venueAccesses: List<VenueAccess> = emptyList(),
    viewModel: EventManagerViewModel? = null,
    onDismiss: () -> Unit,
    onConfirm: (Guest) -> Unit
) {
    val isTemporaryGuest = guest.isTemporaryGuest
    var name by remember(guest) { mutableStateOf(guest.name) }
    var email by remember(guest) { mutableStateOf(guest.email) }
    var phone by remember(guest) { mutableStateOf(guest.phoneNumber) }
    var invitations by remember(guest) { mutableStateOf(guest.invitations.toString()) }
    var selectedVenueName by remember(guest) { mutableStateOf(guest.venueName) }
    var notes by remember(guest) { mutableStateOf(guest.notes) }
    var barDiscount by remember(guest) { mutableStateOf(guest.barDiscountPercent.toString()) }
    var pendingPhotoBytes by remember { mutableStateOf<ByteArray?>(null) }

    var temporaryArtist by remember(guest) { mutableStateOf(guest.temporaryArtistName) }
    var temporaryEventDateMillis by remember(guest) { mutableStateOf(guest.temporaryEventDate) }
    var temporaryContactPhone by remember(guest) { mutableStateOf(guest.temporaryContactPhone) }
    var temporaryContactEmail by remember(guest) { mutableStateOf(guest.temporaryContactEmail) }
    var temporaryVenueName by remember(guest) {
        mutableStateOf(guest.temporaryVenueName.ifBlank { null })
    }
    var temporaryAccessIds by remember(guest) { mutableStateOf(guest.temporaryAccessIdSet()) }
    val selectedVenueAccesses = remember(venueAccesses, temporaryVenueName) {
        temporaryVenueName?.let { VenueAccessCatalog.forVenue(venueAccesses, it) }.orEmpty()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.edit_guest)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(Res.string.name)) }, modifier = Modifier.fillMaxWidth())
                if (isTemporaryGuest) {
                    OutlinedTextField(
                        temporaryArtist,
                        { temporaryArtist = it },
                        label = { Text(stringResource(Res.string.temp_guest_artist_label)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    EventDatePickerField(
                        selectedDateMillis = temporaryEventDateMillis,
                        onDateSelected = { temporaryEventDateMillis = it },
                    )
                    OutlinedTextField(
                        temporaryContactPhone,
                        { temporaryContactPhone = it },
                        label = { Text(stringResource(Res.string.temp_guest_contact_phone_label)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (temporaryFeaturesEnabled) {
                        OutlinedTextField(
                            temporaryContactEmail,
                            { temporaryContactEmail = it },
                            label = { Text(stringResource(Res.string.temp_guest_contact_email_label)) },
                            supportingText = {
                                Text(stringResource(Res.string.temp_guest_contact_email_hint))
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TemporaryGuestVenueDropdown(
                            venues = venues,
                            selectedVenueName = temporaryVenueName,
                            onVenueSelected = { picked ->
                                if (picked != temporaryVenueName) temporaryAccessIds = emptySet()
                                temporaryVenueName = picked
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (selectedVenueAccesses.isNotEmpty()) {
                            Text(
                                text = stringResource(Res.string.temp_guest_access_person_label),
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
                    OutlinedTextField(email, { email = it }, label = { Text(stringResource(Res.string.email)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(phone, { phone = it }, label = { Text(stringResource(Res.string.phone)) }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(invitations, { invitations = it }, label = { Text(stringResource(Res.string.invitations)) }, modifier = Modifier.fillMaxWidth())
                    GuestVenueDropdownField(
                        venues = venues,
                        selectedVenueName = selectedVenueName,
                        onVenueSelected = { selectedVenueName = it },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(notes, { notes = it }, label = { Text(stringResource(Res.string.notes)) }, modifier = Modifier.fillMaxWidth())
                if (barDiscountEnabled && (!isTemporaryGuest || temporaryFeaturesEnabled)) {
                    GuestBarDiscountField(barDiscount, { barDiscount = it })
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
        },
        confirmButton = {
            Button(
                onClick = {
                    val updated = if (isTemporaryGuest) {
                        guest.copy(
                            name = name.trim(),
                            notes = notes.trim(),
                            temporaryArtistName = temporaryArtist.trim(),
                            temporaryEventDate = temporaryEventDateMillis,
                            temporaryContactPhone = temporaryContactPhone.trim(),
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
                            lastModified = System.currentTimeMillis(),
                        )
                    } else {
                        guest.copy(
                            name = name.trim(),
                            email = email.trim(),
                            phoneNumber = phone.trim(),
                            invitations = invitations.toIntOrNull() ?: guest.invitations,
                            venueName = selectedVenueName.trim(),
                            notes = notes.trim(),
                            barDiscountPercent = if (barDiscountEnabled) {
                                barDiscount.toIntOrNull() ?: 0
                            } else {
                                guest.barDiscountPercent
                            },
                            lastModified = System.currentTimeMillis()
                        )
                    }
                    onConfirm(updated)
                    if (!isTemporaryGuest) {
                        pendingPhotoBytes?.let { viewModel?.uploadProfilePhotoForGuest(updated, it) }
                    }
                },
                enabled = name.isNotBlank() && (!isTemporaryGuest || temporaryEventDateMillis != null)
            ) { Text(stringResource(Res.string.save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } }
    )
}
