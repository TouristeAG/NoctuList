package com.eventmanager.app.ui.screens

import com.eventmanager.app.portal.PortalGrantResult
import com.eventmanager.app.ui.components.AddVolunteerChoiceDialog
import com.eventmanager.app.ui.components.PortalAccessToggleRow
import com.eventmanager.app.ui.components.PortalGrantResultDialog
import com.eventmanager.app.ui.components.VolunteerSignupLinkDialog
import com.eventmanager.app.ui.components.rememberVolunteerPortalEnabled
import kotlinx.coroutines.launch
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.ui.components.DialogWindowFollowsScreenSize
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import org.jetbrains.compose.resources.stringResource

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.*
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.shape.RoundedCornerShape
import com.eventmanager.app.ui.components.VolunteerDetailPanel
import com.eventmanager.app.ui.components.ProfilePhotoFormPicker
import com.eventmanager.app.ui.components.rememberProfilePhotosUploadEnabled
import com.eventmanager.app.ui.components.genderDisplayLabel
import com.eventmanager.app.ui.components.BirthdayDatePicker
import com.eventmanager.app.ui.components.DeleteVolunteerDialog
import com.eventmanager.app.ui.components.fullScreenDialogProperties
import com.eventmanager.app.data.models.*
import com.eventmanager.app.data.remote.resolvedProfilePhotoPath
import com.eventmanager.app.data.utils.VolunteerActivityManager
import com.eventmanager.app.ui.components.SearchBarWithFilter
import com.eventmanager.app.utils.ValidationUtils
import com.eventmanager.app.ui.utils.*
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.data.utils.formatMoney
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun VolunteerScreen(
    volunteers: List<Volunteer>,
    volunteerJobs: List<Job>,
    venues: List<VenueEntity>,
    onAddVolunteer: (Volunteer, ByteArray?) -> Unit,
    onUpdateVolunteer: (Volunteer) -> Unit,
    onDeleteVolunteer: (Volunteer, Boolean) -> Unit,
    jobTypeConfigs: List<JobTypeConfig> = emptyList(),
    onConfirmFutureEntry: ((Job, Int) -> Unit)? = null,
    scrollBehavior: String = SettingsManager.FULL_SCROLL,
    viewModel: EventManagerViewModel? = null
) {
    val platformContext = LocalPlatformContext.current
    val settingsManager = remember(platformContext) { SettingsManager(platformContext) }
    val currencyCode = remember { settingsManager.getCurrencyCode() }
    val accountTransfers = viewModel?.accountTransfers?.collectAsState()?.value ?: emptyList()
    val accountBalances = viewModel?.accountBalances?.collectAsState()?.value ?: emptyMap()
    var showAddDialog by remember { mutableStateOf(false) }
    val portalEnabled = rememberVolunteerPortalEnabled(viewModel)
    val portalSignupAvailable = portalEnabled && viewModel?.isVolunteerSignupLinkAvailable() == true
    var showAddChoice by remember { mutableStateOf(false) }
    var showSignupLink by remember { mutableStateOf(false) }
    var portalGrant by remember { mutableStateOf<Pair<Volunteer, PortalGrantResult>?>(null) }
    val portalScope = rememberCoroutineScope()
    var showEditDialog by remember { mutableStateOf<Volunteer?>(null) }
    var showDetailPanel by remember { mutableStateOf<Volunteer?>(null) }
    var showDeleteDialog by remember { mutableStateOf<Volunteer?>(null) }
    var searchText by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<String?>(null) }

    val isCompact = isCompactScreen()
    val responsivePadding = getResponsivePadding()
    val responsiveSpacing = getResponsiveSpacing()

    // Memoize filter strings to avoid repeated getString() calls inside filter loop
    val filterAll = stringResource(Res.string.filter_all)
    val filterActive = stringResource(Res.string.filter_active)
    val filterInactive = stringResource(Res.string.filter_inactive)
    
    // Memoize filter options list to avoid recomputation on every recomposition
    val filterOptions = remember { listOf(filterActive, filterInactive) + VolunteerRank.values().map { it.name } }

    val jobsByVolunteerId = remember(volunteerJobs) {
        VolunteerActivityManager.groupJobsByVolunteerId(volunteerJobs)
    }

    // Compute filtered volunteers once - memoized to avoid recalculation on recomposition
    val filteredVolunteers = remember(volunteers, volunteerJobs, searchText, selectedFilter, filterActive, filterInactive, filterAll) {
        val lowerSearchText = searchText.lowercase()
        volunteers.filter { volunteer ->
            val matchesSearch = searchText.isEmpty() || 
                volunteer.name.lowercase().contains(lowerSearchText) ||
                volunteer.email.lowercase().contains(lowerSearchText) ||
                volunteer.lastNameAbbreviation.lowercase().contains(lowerSearchText) ||
                volunteer.nfcCardUid.lowercase().contains(lowerSearchText)
            
            val isActiveNow = VolunteerActivityManager.isVolunteerActive(
                volunteer,
                jobsByVolunteerId[volunteer.id],
            )
            val matchesFilter = selectedFilter?.let { filter ->
                when (filter) {
                    filterAll -> true
                    filterActive -> isActiveNow
                    filterInactive -> !isActiveNow
                    else -> volunteer.currentRank?.name == filter
                }
            } ?: true
            
            matchesSearch && matchesFilter
        }.sortedBy { it.name.lowercase() }
    }
    
    // Pre-compute responsive values to avoid recalculation
    val itemSpacing = if (isCompact) 6.dp else 8.dp
    
    // Extracted header content as a composable lambda to avoid duplication
    val headerContent: @Composable () -> Unit = {
        if (isCompact) {
            Column(verticalArrangement = Arrangement.spacedBy(responsiveSpacing)) {
                Text(
                    text = stringResource(Res.string.volunteers_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = { if (portalSignupAvailable) showAddChoice = true else showAddDialog = true },
                    modifier = Modifier.fillMaxWidth().height(getResponsiveButtonHeight())
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.add_volunteer))
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(Res.string.volunteer_manager),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Button(
                    onClick = { if (portalSignupAvailable) showAddChoice = true else showAddDialog = true },
                    modifier = Modifier.height(getResponsiveButtonHeight())
                ) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.add_volunteer))
                }
            }
        }
    }
    
    // Extracted filter/search content as a composable lambda
    val filterContent: @Composable () -> Unit = {
        SearchBarWithFilter(
            searchText = searchText,
            onSearchTextChange = { searchText = it },
            placeholder = stringResource(Res.string.search_volunteers_placeholder),
            filterOptions = filterOptions,
            selectedFilter = selectedFilter,
            onFilterChange = { selectedFilter = it }
        )
    }
    
    // Extracted count text as a composable lambda
    val countText: @Composable () -> Unit = {
        Text(
            text = "${filteredVolunteers.size} of ${volunteers.size} volunteers",
            style = getResponsiveBodyTypography(),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    
    // Common list item rendering function
    val volunteerItems: LazyListScope.() -> Unit = {
        items(
            items = filteredVolunteers,
            key = { volunteer -> volunteer.id }
        ) { volunteer ->
            VolunteerCard(
                volunteer = volunteer,
                isActive = VolunteerActivityManager.isVolunteerActive(
                    volunteer,
                    jobsByVolunteerId[volunteer.id],
                ),
                accountBalance = accountBalances[AccountHolderKey(AccountHolderType.VOLUNTEER, volunteer.id)]
                    ?: viewModel?.getVolunteerAccountBalance(volunteer.id)
                    ?: 0.0,
                currencyCode = currencyCode,
                onClick = { showDetailPanel = volunteer }
            )
        }
    }
    
    when (scrollBehavior) {
        SettingsManager.HEADER_PINNED -> {
            Column(
                modifier = Modifier.fillMaxSize().padding(responsivePadding)
            ) {
                headerContent()
                Spacer(modifier = Modifier.height(responsiveSpacing))
                filterContent()
                Spacer(modifier = Modifier.height(16.dp))
                countText()
                Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 8.dp))
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(itemSpacing),
                    modifier = Modifier.fillMaxWidth().weight(1f)
                ) {
                    volunteerItems()
                }
            }
        }
        SettingsManager.STICKY_FILTERS -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(responsivePadding),
                verticalArrangement = Arrangement.spacedBy(itemSpacing)
            ) {
                item(key = "header") { headerContent() }
                item(key = "header_spacer") { Spacer(modifier = Modifier.height(responsiveSpacing)) }
                stickyHeader(key = "sticky_filters") {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                            .padding(bottom = 8.dp)
                    ) {
                        filterContent()
                    }
                }
                item(key = "count") { 
                    Column {
                        countText()
                        Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 8.dp))
                    }
                }
                volunteerItems()
            }
        }
        else -> {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(responsivePadding),
                verticalArrangement = Arrangement.spacedBy(itemSpacing)
            ) {
                item(key = "header") { headerContent() }
                item(key = "header_spacer") { Spacer(modifier = Modifier.height(responsiveSpacing)) }
                item(key = "filters") { filterContent() }
                item(key = "filter_spacer") { Spacer(modifier = Modifier.height(16.dp)) }
                item(key = "count") { 
                    Column {
                        countText()
                        Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 8.dp))
                    }
                }
                volunteerItems()
            }
        }
    }
    
    if (showAddChoice) {
        AddVolunteerChoiceDialog(
            onFillIn = {
                showAddChoice = false
                showAddDialog = true
            },
            onSendForm = {
                showAddChoice = false
                showSignupLink = true
            },
            onDismiss = { showAddChoice = false },
        )
    }

    if (showSignupLink && viewModel != null) {
        VolunteerSignupLinkDialog(viewModel = viewModel, onDismiss = { showSignupLink = false })
    }

    portalGrant?.let { (volunteer, result) ->
        PortalGrantResultDialog(viewModel, volunteer, result, onDismiss = { portalGrant = null })
    }

    // Add Volunteer Dialog
    if (showAddDialog) {
        AddVolunteerDialog(
            onDismiss = { showAddDialog = false },
            profilePhotosEnabled = rememberProfilePhotosUploadEnabled(viewModel),
            portalAccessAvailable = portalEnabled && viewModel != null,
            onConfirm = { name, abbreviation, email, phone, dateOfBirth, gender, photo, createPortalAccess ->
                val newVolunteer = Volunteer(
                    name = name,
                    lastNameAbbreviation = abbreviation,
                    email = email,
                    phoneNumber = phone,
                    dateOfBirth = dateOfBirth,
                    gender = gender
                )
                if (createPortalAccess && viewModel != null) {
                    portalScope.launch {
                        val result = viewModel.addVolunteerWithPortalAccess(newVolunteer, photo)
                        portalGrant = newVolunteer to result
                    }
                } else {
                    onAddVolunteer(newVolunteer, photo)
                }
                showAddDialog = false
            }
        )
    }
    
    // Edit Volunteer Dialog
    showEditDialog?.let { volunteer ->
        EditVolunteerDialog(
            volunteer = volunteer,
            onDismiss = { showEditDialog = null },
            viewModel = viewModel,
            onConfirm = { updatedVolunteer ->
                onUpdateVolunteer(updatedVolunteer)
                showEditDialog = null
            }
        )
    }
    
    // Volunteer Detail Panel
    if (showDetailPanel != null) {
        // Memoize filtered jobs to prevent unnecessary recompositions
        val filteredJobsForVolunteer = remember(showDetailPanel?.id, volunteerJobs) {
            volunteerJobs.filter { it.volunteerId == showDetailPanel?.id }
        }
        Dialog(
            onDismissRequest = { showDetailPanel = null },
            properties = fullScreenDialogProperties(),
        ) {
            DialogWindowFollowsScreenSize()
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(getProfileDialogScreenEdgeInset())
            ) {
                VolunteerDetailPanel(
                    modifier = Modifier.fillMaxSize(),
                    volunteer = showDetailPanel!!,
                    volunteerJobs = filteredJobsForVolunteer,
                    venues = venues,
                    jobTypeConfigs = jobTypeConfigs,
                    onConfirmFutureEntry = onConfirmFutureEntry,
                    onEdit = { volunteer ->
                        showDetailPanel = null
                        showEditDialog = volunteer
                    },
                    onAssignNfcUid = { volunteer, uid ->
                        onUpdateVolunteer(
                            volunteer.copy(
                                nfcCardUid = uid,
                                lastModified = System.currentTimeMillis()
                            )
                        )
                        showDetailPanel = volunteer.copy(nfcCardUid = uid)
                    },
                    onDelete = { volunteer ->
                        showDetailPanel = null
                        showDeleteDialog = volunteer
                    },
                    onClose = { showDetailPanel = null },
                    accountBalance = accountBalances[AccountHolderKey(AccountHolderType.VOLUNTEER, showDetailPanel!!.id)]
                        ?: viewModel?.getVolunteerAccountBalance(showDetailPanel!!.id)
                        ?: 0.0,
                    currencyCode = currencyCode,
                    recentTransfers = accountTransfers.filter {
                        it.holderType == AccountHolderType.VOLUNTEER && it.holderId == showDetailPanel!!.id
                    },
                    onManualAccountAdjust = if (viewModel != null) { amount, note ->
                        viewModel.applyManualAccountAdjustment(
                            AccountHolderType.VOLUNTEER,
                            showDetailPanel!!.id,
                            showDetailPanel!!.name,
                            amount,
                            note
                        )
                    } else null,
                    viewModel = viewModel
                )
            }
        }
    }
    
    // Delete Volunteer Dialog
    showDeleteDialog?.let { volunteerToDelete ->
        val shiftCount = remember(volunteerToDelete.id, volunteerJobs) {
            volunteerJobs.count { it.volunteerId == volunteerToDelete.id }
        }
        
        DeleteVolunteerDialog(
            volunteer = volunteerToDelete,
            shiftCount = shiftCount,
            onConfirm = { deleteShifts ->
                onDeleteVolunteer(volunteerToDelete, deleteShifts)
                showDeleteDialog = null
            },
            onDismiss = { showDeleteDialog = null }
        )
    }
}

@Composable
fun VolunteerCard(
    volunteer: Volunteer,
    onClick: (Volunteer) -> Unit,
    accountBalance: Double = 0.0,
    currencyCode: String = "CHF",
    isActive: Boolean = VolunteerActivityManager.isVolunteerActive(volunteer),
    modifier: Modifier = Modifier
) {
    val platformContext = LocalPlatformContext.current
    val isCompact = isCompactScreen()
    val responsivePadding = getResponsiveCardPadding()
    
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick(volunteer) },
        elevation = CardDefaults.cardElevation(defaultElevation = getResponsiveCardElevation())
    ) {
        Column(
            modifier = Modifier.padding(responsivePadding)
        ) {
            // Header with volunteer info
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = volunteer.name,
                            style = getResponsiveTitleTypography(),
                            fontWeight = FontWeight.Bold
                        )
                        
                        // Activity indicator
                        Icon(
                            Icons.Default.Circle,
                            contentDescription = if (isActive) stringResource(Res.string.active_status) else stringResource(Res.string.inactive_status),
                            modifier = Modifier.size(12.dp),
                            tint = if (isActive) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
                        )
                    }
                    
                    Text(
                        text = "${volunteer.lastNameAbbreviation} • ${volunteer.email}",
                        style = getResponsiveBodyTypography(),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    Text(
                        text = volunteer.phoneNumber,
                        style = if (isCompact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    
                    // Activity status text
                    Text(
                        text = if (isActive) stringResource(Res.string.active_status) else stringResource(Res.string.inactive_status),
                        style = if (isCompact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall,
                        color = if (isActive) Color(0xFF4CAF50) else Color(0xFF9E9E9E)
                    )
                    Text(
                        text = "${stringResource(Res.string.account_amount_label)}: ${formatMoney(accountBalance, currencyCode)}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = if (accountBalance < 0) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.primary
                        }
                    )
                }
                
                // Click indicator
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = stringResource(Res.string.view_details),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun AddVolunteerDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String, String, String, Gender?, ByteArray?, Boolean) -> Unit,
    profilePhotosEnabled: Boolean = false,
    portalAccessAvailable: Boolean = false,
) {
    val platformContext = LocalPlatformContext.current
    var name by remember { mutableStateOf("") }
    var abbreviation by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var dateOfBirth by remember { mutableStateOf("") }
    var selectedGender by remember { mutableStateOf<Gender?>(null) }
    var expandedGender by remember { mutableStateOf(false) }
    var pendingPhotoBytes by remember { mutableStateOf<ByteArray?>(null) }
    var createPortalAccess by remember { mutableStateOf(portalAccessAvailable) }
    
    // Validation states
    var emailError by remember { mutableStateOf<String?>(null) }
    var dateError by remember { mutableStateOf<String?>(null) }

    val isCompact = isCompactScreen()
    val scrollState = rememberScrollState()
    val isTabletDevice = isTablet()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = !isTabletDevice
        )
    ) {
        DialogWindowFollowsScreenSize()
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
                            text = if (isCompact) stringResource(Res.string.add_volunteer) else stringResource(Res.string.add_new_volunteer),
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
                        verticalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 12.dp)
                    ) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text(stringResource(Res.string.full_name)) },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = abbreviation,
                            onValueChange = { abbreviation = it.uppercase() },
                            label = { Text(stringResource(Res.string.last_name_abbreviation)) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(Res.string.last_name_abbreviation_placeholder)) }
                        )

                        OutlinedTextField(
                            value = email,
                            onValueChange = { 
                                email = it
                                emailError = ValidationUtils.getEmailErrorMessage(it)
                            },
                            label = { Text(stringResource(Res.string.email)) },
                            placeholder = { Text(stringResource(Res.string.email_placeholder)) },
                            isError = emailError != null,
                            supportingText = emailError?.let { { Text(it) } },
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = phone,
                            onValueChange = { phone = it },
                            label = { Text(stringResource(Res.string.phone_number)) },
                            modifier = Modifier.fillMaxWidth()
                        )

                        BirthdayDatePicker(
                            dateString = dateOfBirth,
                            onDateSelected = { 
                                dateOfBirth = it
                                dateError = ValidationUtils.getDateErrorMessage(it)
                            },
                            label = { Text(stringResource(Res.string.date_of_birth)) },
                            placeholder = { Text(stringResource(Res.string.date_of_birth_placeholder)) },
                            isError = dateError != null,
                            supportingText = dateError?.let { { Text(it) } }
                        )

                        // Gender Dropdown
                        @OptIn(ExperimentalMaterial3Api::class)
                        ExposedDropdownMenuBox(
                            expanded = expandedGender,
                            onExpandedChange = { expandedGender = !expandedGender },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = selectedGender?.let { genderDisplayLabel(it) } ?: "",
                                onValueChange = { },
                                readOnly = true,
                                label = { Text(stringResource(Res.string.gender)) },
                                placeholder = { Text(stringResource(Res.string.select_gender)) },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedGender) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor()
                            )
                            
                            ExposedDropdownMenu(
                                expanded = expandedGender,
                                onDismissRequest = { expandedGender = false }
                            ) {
                                Gender.values().forEach { gender ->
                                    DropdownMenuItem(
                                        text = { Text(genderDisplayLabel(gender)) },
                                        onClick = {
                                            selectedGender = gender
                                            expandedGender = false
                                        }
                                    )
                                }
                            }
                        }

                        ProfilePhotoFormPicker(
                            enabled = profilePhotosEnabled,
                            currentUrl = "",
                            name = name,
                            pendingBytes = pendingPhotoBytes,
                            onPicked = { pendingPhotoBytes = it },
                            onClearPending = { pendingPhotoBytes = null },
                        )

                        if (portalAccessAvailable) {
                            PortalAccessToggleRow(
                                checked = createPortalAccess,
                                onCheckedChange = { createPortalAccess = it },
                                enabled = true,
                            )
                        }
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
                                val storageDate = ValidationUtils.convertDateToStorageFormat(dateOfBirth) ?: dateOfBirth
                                onConfirm(name, abbreviation, email, phone, storageDate, selectedGender, pendingPhotoBytes, portalAccessAvailable && createPortalAccess) 
                            },
                            enabled = name.isNotBlank() && abbreviation.isNotBlank() && 
                                     email.isNotBlank() && phone.isNotBlank() && dateOfBirth.isNotBlank() &&
                                     emailError == null && dateError == null,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(Res.string.add_volunteer))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EditVolunteerDialog(
    volunteer: Volunteer,
    onDismiss: () -> Unit,
    onConfirm: (Volunteer) -> Unit,
    viewModel: EventManagerViewModel? = null,
) {
    val platformContext = LocalPlatformContext.current
    var name by remember { mutableStateOf(volunteer.name) }
    var abbreviation by remember { mutableStateOf(volunteer.lastNameAbbreviation) }
    var email by remember { mutableStateOf(volunteer.email) }
    var phone by remember { mutableStateOf(volunteer.phoneNumber) }
    var dateOfBirth by remember { mutableStateOf(ValidationUtils.convertDateToDisplayFormat(volunteer.dateOfBirth)) }
    var selectedGender by remember { mutableStateOf(volunteer.gender) }
    var expandedGender by remember { mutableStateOf(false) }
    var pendingPhotoBytes by remember { mutableStateOf<ByteArray?>(null) }
    
    // Validation states
    var emailError by remember { mutableStateOf<String?>(null) }
    var dateError by remember { mutableStateOf<String?>(null) }

    val isCompact = isCompactScreen()
    val scrollState = rememberScrollState()
    val isTabletDevice = isTablet()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = !isTabletDevice
        )
    ) {
        DialogWindowFollowsScreenSize()
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
                            text = if (isCompact) stringResource(Res.string.edit_volunteer) else stringResource(Res.string.edit_volunteer_details),
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
                    verticalArrangement = Arrangement.spacedBy(if (isCompact) 8.dp else 12.dp)
                ) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text(stringResource(Res.string.full_name)) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = abbreviation,
                        onValueChange = { abbreviation = it.uppercase() },
                        label = { Text(stringResource(Res.string.last_name_abbreviation)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(Res.string.last_name_abbreviation_placeholder)) }
                    )

                    OutlinedTextField(
                        value = email,
                        onValueChange = { 
                            email = it
                            emailError = ValidationUtils.getEmailErrorMessage(it)
                        },
                        label = { Text(stringResource(Res.string.email)) },
                        placeholder = { Text(stringResource(Res.string.email_placeholder)) },
                        isError = emailError != null,
                        supportingText = emailError?.let { { Text(it) } },
                        modifier = Modifier.fillMaxWidth()
                    )

                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text(stringResource(Res.string.phone_number)) },
                        modifier = Modifier.fillMaxWidth()
                    )

                    BirthdayDatePicker(
                        dateString = dateOfBirth,
                        onDateSelected = { 
                            dateOfBirth = it
                            dateError = ValidationUtils.getDateErrorMessage(it)
                        },
                        label = { Text(stringResource(Res.string.date_of_birth)) },
                        placeholder = { Text(stringResource(Res.string.date_of_birth_placeholder)) },
                        isError = dateError != null,
                        supportingText = dateError?.let { { Text(it) } }
                    )

                    // Gender Dropdown
                    @OptIn(ExperimentalMaterial3Api::class)
                    ExposedDropdownMenuBox(
                        expanded = expandedGender,
                        onExpandedChange = { expandedGender = !expandedGender },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedGender?.let { genderDisplayLabel(it) } ?: "",
                            onValueChange = { },
                            readOnly = true,
                            label = { Text(stringResource(Res.string.gender)) },
                            placeholder = { Text(stringResource(Res.string.select_gender)) },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedGender) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor()
                        )
                        
                        ExposedDropdownMenu(
                            expanded = expandedGender,
                            onDismissRequest = { expandedGender = false }
                        ) {
                            Gender.values().forEach { gender ->
                                DropdownMenuItem(
                                    text = { Text(genderDisplayLabel(gender)) },
                                    onClick = {
                                        selectedGender = gender
                                        expandedGender = false
                                    }
                                )
                            }
                        }
                    }

                    ProfilePhotoFormPicker(
                            enabled = rememberProfilePhotosUploadEnabled(viewModel),
                            currentUrl = volunteer.profilePhotoUrl,
                            currentPath = volunteer.resolvedProfilePhotoPath(),
                            name = name,
                            pendingBytes = pendingPhotoBytes,
                            onPicked = { pendingPhotoBytes = it },
                            onClearPending = { pendingPhotoBytes = null },
                            onRemoveExisting = { viewModel?.removeProfilePhotoForVolunteer(volunteer) },
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
                            val storageDate = ValidationUtils.convertDateToStorageFormat(dateOfBirth) ?: dateOfBirth
                            val updatedVolunteer = volunteer.copy(
                                name = name,
                                lastNameAbbreviation = abbreviation,
                                email = email,
                                phoneNumber = phone,
                                dateOfBirth = storageDate,
                                gender = selectedGender
                            )
                            onConfirm(updatedVolunteer)
                            pendingPhotoBytes?.let { viewModel?.uploadProfilePhotoForVolunteer(updatedVolunteer, it) }
                        },
                        enabled = name.isNotBlank() && abbreviation.isNotBlank() && 
                                 email.isNotBlank() && phone.isNotBlank() && dateOfBirth.isNotBlank() &&
                                 emailError == null && dateError == null,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(Res.string.update_volunteer))
                    }
                }
                }
            }
        }
    }
}

