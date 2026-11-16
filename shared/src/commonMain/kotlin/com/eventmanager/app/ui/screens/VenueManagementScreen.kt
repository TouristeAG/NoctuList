package com.eventmanager.app.ui.screens
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import org.jetbrains.compose.resources.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.data.models.VenueAccessCatalog
import com.eventmanager.app.ui.components.SearchBarWithFilter
import com.eventmanager.app.ui.components.VenueAccessBindings
import com.eventmanager.app.ui.components.VenueAccessDraft
import com.eventmanager.app.ui.components.VenueAccessEditor
import com.eventmanager.app.ui.utils.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VenueManagementScreen(
    venues: List<VenueEntity>,
    onAddVenue: (VenueEntity) -> Unit,
    onUpdateVenue: (VenueEntity) -> Unit,
    onDeleteVenue: (VenueEntity) -> Unit,
    onUpdateVenueStatus: (Long, Boolean) -> Unit,
    onBack: () -> Unit = {},
    /** Temporary guest accesses, edited per venue. Null on the Sheets backend. */
    venueAccesses: VenueAccessBindings? = null,
) {
    var showAddDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf<VenueEntity?>(null) }
    var searchText by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<String?>(null) }

    val isCompact = isCompactScreen()
    val responsivePadding = getResponsivePadding()
    val responsiveSpacing = getResponsiveSpacing()

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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(Res.string.venues_title),
                        style = getResponsiveTypography(),
                        fontWeight = FontWeight.Bold
                    )
                    
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(Res.string.close),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
                
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.height(getResponsiveButtonHeight())
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.add_venue))
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
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.size(48.dp)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Close",
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    
                    Text(
                        text = stringResource(Res.string.venue_management_title),
                        style = getResponsiveTypography(),
                        fontWeight = FontWeight.Bold
                    )
                }
                
                Button(
                    onClick = { showAddDialog = true },
                    modifier = Modifier.height(getResponsiveButtonHeight())
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(Res.string.add_venue))
                }
            }
        }
        
        Spacer(modifier = Modifier.height(responsiveSpacing))
        
        // Memoize filter strings and options to avoid recomputation on every recomposition
        val filterActiveText = stringResource(Res.string.filter_active)
        val filterInactiveText = stringResource(Res.string.filter_inactive)
        val filterOptions = remember(filterActiveText, filterInactiveText) { 
            listOf(filterActiveText, filterInactiveText) 
        }
        
        // Search and Filter Section
        SearchBarWithFilter(
            searchText = searchText,
            onSearchTextChange = { searchText = it },
            placeholder = stringResource(Res.string.search_venues_placeholder),
            filterOptions = filterOptions,
            selectedFilter = selectedFilter,
            onFilterChange = { selectedFilter = it }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Memoize filtered venues to avoid recalculating on every recomposition
        val filteredVenues = remember(venues, searchText, selectedFilter, filterActiveText, filterInactiveText) {
            val lowerSearchText = searchText.lowercase()
            venues.filter { venue ->
                val matchesSearch = searchText.isEmpty() || 
                    venue.name.lowercase().contains(lowerSearchText) ||
                    venue.description.lowercase().contains(lowerSearchText)
                val matchesFilter = selectedFilter?.let { filter ->
                    when (filter) {
                        filterActiveText -> venue.isActive
                        filterInactiveText -> !venue.isActive
                        else -> true
                    }
                } ?: true
                matchesSearch && matchesFilter
            }
        }
        
        Text(
            text = stringResource(Res.string.venues_count, filteredVenues.size, venues.size),
            style = getResponsiveBodyTypography(),
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 8.dp))
        
        // Venues list
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(if (isCompact) 6.dp else 8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(
                items = filteredVenues,
                key = { venue -> venue.id }
            ) { venue ->
                VenueConfigCard(
                    venue = venue,
                    onUpdate = { showEditDialog = venue },
                    onDelete = onDeleteVenue,
                    onToggleStatus = { isActive ->
                        onUpdateVenueStatus(venue.id, isActive)
                    }
                )
            }
        }
    }
    
    // Add Venue Dialog
    if (showAddDialog) {
        AddVenueConfigDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { venue ->
                onAddVenue(venue)
                showAddDialog = false
            }
        )
    }
    
    // Edit Venue Dialog
    showEditDialog?.let { venue ->
        EditVenueConfigDialog(
            venue = venue,
            venueAccesses = venueAccesses,
            onDismiss = { showEditDialog = null },
            onConfirm = { updatedVenue ->
                onUpdateVenue(updatedVenue)
                showEditDialog = null
            }
        )
    }
}

@Composable
fun VenueConfigCard(
    venue: VenueEntity,
    onUpdate: (VenueEntity) -> Unit,
    onDelete: (VenueEntity) -> Unit,
    onToggleStatus: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val isCompact = isCompactScreen()
    val responsivePadding = getResponsiveCardPadding()
    
    Card(
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = getResponsiveCardElevation())
    ) {
        Column(
            modifier = Modifier.padding(responsivePadding)
        ) {
            // Header with name and status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = venue.name,
                        style = getResponsiveTitleTypography(),
                        fontWeight = FontWeight.Bold
                    )
                    
                    if (venue.description.isNotEmpty()) {
                        Text(
                            text = venue.description,
                            style = getResponsiveBodyTypography(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                Switch(
                    checked = venue.isActive,
                    onCheckedChange = onToggleStatus
                )
            }
            
            Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 12.dp))
            
            // Status chip
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                AssistChip(
                    onClick = { },
                    label = { 
                        Text(
                            if (venue.isActive) stringResource(Res.string.venue_active) else stringResource(Res.string.venue_inactive),
                            style = MaterialTheme.typography.labelSmall
                        ) 
                    },
                    leadingIcon = {
                        Icon(
                            if (venue.isActive) Icons.Default.CheckCircle else Icons.Default.Cancel,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(if (isCompact) 8.dp else 12.dp))
            
            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onUpdate(venue) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(Res.string.edit))
                }
                
                OutlinedButton(
                    onClick = { onDelete(venue) },
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(stringResource(Res.string.delete))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddVenueConfigDialog(
    onDismiss: () -> Unit,
    onConfirm: (VenueEntity) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }

    val isCompact = isCompactScreen()
    val scrollState = rememberScrollState()
    val isTabletDevice = isTablet()

    // Custom Dialog with proper scrolling
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
                            text = if (isCompact) stringResource(Res.string.add_venue) else stringResource(Res.string.add_new_venue),
                            style = if (isTabletDevice) getTabletConstrainedTitleTypography() else getResponsiveTypography(),
                            fontWeight = FontWeight.Bold
                        )
                        
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
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
                            label = { Text(stringResource(Res.string.venue_name)) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(Res.string.venue_name_placeholder)) }
                        )

                        OutlinedTextField(
                            value = description,
                            onValueChange = { description = it },
                            label = { Text(stringResource(Res.string.venue_description)) },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(Res.string.venue_description_placeholder)) },
                            maxLines = 3
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
                                    val venue = VenueEntity(
                                        name = name,
                                        description = description
                                    )
                                    onConfirm(venue)
                                },
                                enabled = name.isNotBlank(),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(Res.string.add_venue))
                            }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditVenueConfigDialog(
    venue: VenueEntity,
    onDismiss: () -> Unit,
    onConfirm: (VenueEntity) -> Unit,
    venueAccesses: VenueAccessBindings? = null,
) {
    var name by remember { mutableStateOf(venue.name) }
    var description by remember { mutableStateOf(venue.description) }
    // Accesses are stored by venue name, so the draft and its changes use the saved name.
    val accessDraft = remember(venue.name) {
        VenueAccessDraft(VenueAccessCatalog.forVenue(venueAccesses?.accesses.orEmpty(), venue.name))
    }

    val isCompact = isCompactScreen()
    val scrollState = rememberScrollState()
    val isTabletDevice = isTablet()

    // Custom Dialog with proper scrolling
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
                            text = if (isCompact) stringResource(Res.string.edit) else stringResource(Res.string.edit_venue_configuration),
                        style = if (isTabletDevice) getTabletConstrainedTitleTypography() else getResponsiveTypography(),
                        fontWeight = FontWeight.Bold
                    )
                    
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
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
                        label = { Text(stringResource(Res.string.venue_name)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(Res.string.venue_name_placeholder)) }
                    )

                    OutlinedTextField(
                        value = description,
                        onValueChange = { description = it },
                        label = { Text(stringResource(Res.string.venue_description)) },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(Res.string.venue_description_placeholder)) },
                        maxLines = 3
                    )

                    if (venueAccesses != null) {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        VenueAccessEditor(
                            venueName = venue.name,
                            draft = accessDraft,
                            canEdit = venueAccesses.canEdit,
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
                            val updatedVenue = venue.copy(
                                name = name,
                                description = description
                            )
                            if (venueAccesses != null && venueAccesses.canEdit) {
                                accessDraft.apply(venue.name, venueAccesses)
                            }
                            onConfirm(updatedVenue)
                        },
                        enabled = name.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(Res.string.update_venue))
                    }
                }
                }
            }
        }
    }
}
