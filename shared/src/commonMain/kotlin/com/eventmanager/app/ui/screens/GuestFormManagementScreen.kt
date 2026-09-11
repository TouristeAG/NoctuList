package com.eventmanager.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.models.GuestForm
import com.eventmanager.app.data.models.GuestFormStatus
import com.eventmanager.app.data.models.closedAtMillis
import com.eventmanager.app.data.models.isListedAsOpen
import com.eventmanager.app.data.models.isListedAsPending
import com.eventmanager.app.data.models.isListedAsRecentlyClosed
import com.eventmanager.app.data.models.statusValue
import com.eventmanager.app.data.utils.AppTimeZone
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.cancel
import com.eventmanager.app.resources.close
import com.eventmanager.app.resources.edit
import com.eventmanager.app.resources.guest_form_allow_multiple
import com.eventmanager.app.resources.guest_form_artist
import com.eventmanager.app.resources.guest_form_close_action
import com.eventmanager.app.resources.guest_form_close_confirm_body
import com.eventmanager.app.resources.guest_form_close_confirm_title
import com.eventmanager.app.resources.guest_form_copy_link
import com.eventmanager.app.resources.guest_form_count
import com.eventmanager.app.resources.guest_form_empty
import com.eventmanager.app.resources.guest_form_event_name
import com.eventmanager.app.resources.guest_form_filter_closed
import com.eventmanager.app.resources.guest_form_filter_open
import com.eventmanager.app.resources.guest_form_filter_pending
import com.eventmanager.app.resources.guest_form_link_copied
import com.eventmanager.app.resources.guest_form_management_title
import com.eventmanager.app.resources.guest_form_search_placeholder
import com.eventmanager.app.resources.guest_form_status_accepted
import com.eventmanager.app.resources.guest_form_status_expired
import com.eventmanager.app.resources.guest_form_status_open
import com.eventmanager.app.resources.guest_form_status_pending
import com.eventmanager.app.resources.guest_form_status_rejected
import com.eventmanager.app.ui.components.GuestFormCreatorDialog
import com.eventmanager.app.ui.components.SearchBarWithFilter
import com.eventmanager.app.ui.utils.getResponsiveBodyTypography
import com.eventmanager.app.ui.utils.getResponsiveCardElevation
import com.eventmanager.app.ui.utils.getResponsiveCardPadding
import com.eventmanager.app.ui.utils.getResponsivePadding
import com.eventmanager.app.ui.utils.getResponsiveSpacing
import com.eventmanager.app.ui.utils.getResponsiveTitleTypography
import com.eventmanager.app.ui.utils.getResponsiveTypography
import com.eventmanager.app.ui.utils.isCompactScreen
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import org.jetbrains.compose.resources.stringResource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GuestFormManagementScreen(
    viewModel: EventManagerViewModel,
    onBack: () -> Unit = {},
) {
    val forms by viewModel.guestForms.collectAsState()
    val venues by viewModel.venues.collectAsState()
    val venueAccesses by viewModel.temporaryGuestVenueAccesses.collectAsState()
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var searchText by remember { mutableStateOf("") }
    var selectedFilter by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<GuestForm?>(null) }
    var closing by remember { mutableStateOf<GuestForm?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refreshGuestFormsFromRemote()
        nowMillis = System.currentTimeMillis()
    }

    val isCompact = isCompactScreen()
    val responsivePadding = getResponsivePadding()
    val responsiveSpacing = getResponsiveSpacing()
    val filterOpen = stringResource(Res.string.guest_form_filter_open)
    val filterPending = stringResource(Res.string.guest_form_filter_pending)
    val filterClosed = stringResource(Res.string.guest_form_filter_closed)
    val filterOptions = remember(filterOpen, filterPending, filterClosed) {
        listOf(filterOpen, filterPending, filterClosed)
    }

    val listed = remember(forms, nowMillis) {
        forms.filter { form ->
            form.isListedAsOpen(nowMillis) ||
                form.isListedAsPending() ||
                form.isListedAsRecentlyClosed(nowMillis)
        }
    }
    val filtered = remember(listed, searchText, selectedFilter, filterOpen, filterPending, filterClosed, nowMillis) {
        val query = searchText.trim().lowercase()
        listed.filter { form ->
            val matchesSearch = query.isEmpty() ||
                form.artistName.lowercase().contains(query) ||
                form.eventName.lowercase().contains(query) ||
                form.venueName.lowercase().contains(query)
            val matchesFilter = when (selectedFilter) {
                filterOpen -> form.isListedAsOpen(nowMillis)
                filterPending -> form.isListedAsPending()
                filterClosed -> form.isListedAsRecentlyClosed(nowMillis)
                else -> true
            }
            matchesSearch && matchesFilter
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(responsivePadding),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f),
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(Res.string.close),
                        modifier = Modifier.size(24.dp),
                    )
                }
                Text(
                    text = stringResource(Res.string.guest_form_management_title),
                    style = getResponsiveTypography(),
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Spacer(modifier = Modifier.height(responsiveSpacing))

        SearchBarWithFilter(
            searchText = searchText,
            onSearchTextChange = { searchText = it },
            placeholder = stringResource(Res.string.guest_form_search_placeholder),
            filterOptions = filterOptions,
            selectedFilter = selectedFilter,
            onFilterChange = { selectedFilter = it },
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(Res.string.guest_form_count, filtered.size, listed.size),
            style = getResponsiveBodyTypography(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(if (isCompact) 4.dp else 8.dp))

        if (filtered.isEmpty()) {
            Text(
                text = stringResource(Res.string.guest_form_empty),
                style = getResponsiveBodyTypography(),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 24.dp),
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(if (isCompact) 6.dp else 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) {
                items(
                    items = filtered,
                    key = { it.formId },
                ) { form ->
                    GuestFormManagementCard(
                        form = form,
                        nowMillis = nowMillis,
                        publicUrl = viewModel.guestFormUrl(form),
                        onEdit = { editing = form },
                        onClose = { closing = form },
                    )
                }
            }
        }
    }

    editing?.let { form ->
        GuestFormCreatorDialog(
            viewModel = viewModel,
            venues = venues,
            venueAccesses = venueAccesses,
            editing = form,
            onDismiss = { editing = null },
        )
    }

    closing?.let { form ->
        AlertDialog(
            onDismissRequest = { closing = null },
            title = { Text(stringResource(Res.string.guest_form_close_confirm_title)) },
            text = {
                Text(stringResource(Res.string.guest_form_close_confirm_body, form.artistName))
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.expireGuestForm(form)
                        closing = null
                    },
                ) {
                    Text(stringResource(Res.string.guest_form_close_action))
                }
            },
            dismissButton = {
                TextButton(onClick = { closing = null }) {
                    Text(stringResource(Res.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun GuestFormManagementCard(
    form: GuestForm,
    nowMillis: Long,
    publicUrl: String,
    onEdit: () -> Unit,
    onClose: () -> Unit,
) {
    val isOpen = form.isListedAsOpen(nowMillis)
    val clipboard = LocalClipboardManager.current
    var copied by remember(form.formId) { mutableStateOf(false) }
    val statusLabel = when {
        isOpen -> stringResource(Res.string.guest_form_status_open)
        form.isListedAsPending() -> stringResource(Res.string.guest_form_status_pending)
        else -> when (form.statusValue) {
            GuestFormStatus.ACCEPTED -> stringResource(Res.string.guest_form_status_accepted)
            GuestFormStatus.REJECTED -> stringResource(Res.string.guest_form_status_rejected)
            GuestFormStatus.EXPIRED, GuestFormStatus.OPEN ->
                stringResource(Res.string.guest_form_status_expired)
            GuestFormStatus.PENDING_REVIEW -> stringResource(Res.string.guest_form_status_pending)
        }
    }
    val dateLabel = formatGuestFormDate(
        if (isOpen || form.isListedAsPending()) form.eventDateMillis else form.closedAtMillis(),
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = getResponsiveCardElevation()),
    ) {
        Column(modifier = Modifier.padding(getResponsiveCardPadding())) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = form.artistName.ifBlank { stringResource(Res.string.guest_form_artist) },
                        style = getResponsiveTitleTypography(),
                        fontWeight = FontWeight.Bold,
                    )
                    val eventLine = listOfNotNull(
                        form.eventName.takeIf { it.isNotBlank() },
                        form.venueName.takeIf { it.isNotBlank() },
                        dateLabel.takeIf { it.isNotBlank() },
                    ).joinToString(" · ")
                    if (eventLine.isNotBlank()) {
                        Text(
                            text = eventLine,
                            style = getResponsiveBodyTypography(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (form.eventName.isBlank()) {
                        Text(
                            text = stringResource(Res.string.guest_form_event_name),
                            style = getResponsiveBodyTypography(),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                AssistChip(
                    onClick = { },
                    label = { Text(statusLabel, style = MaterialTheme.typography.labelSmall) },
                )
            }

            if (form.allowMultipleResponses && form.parentFormId.isBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.guest_form_allow_multiple),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (isOpen) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onEdit,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(Res.string.edit))
                    }
                    OutlinedButton(
                        onClick = onClose,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(Res.string.guest_form_close_action))
                    }
                }
                if (publicUrl.isNotBlank()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = {
                            clipboard.setText(AnnotatedString(publicUrl))
                            copied = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(
                            Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            if (copied) stringResource(Res.string.guest_form_link_copied)
                            else stringResource(Res.string.guest_form_copy_link),
                        )
                    }
                }
            }
        }
    }
}

private fun formatGuestFormDate(millis: Long): String {
    if (millis <= 0L) return ""
    val fmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).apply {
        timeZone = AppTimeZone.java
    }
    return fmt.format(Date(millis))
}
