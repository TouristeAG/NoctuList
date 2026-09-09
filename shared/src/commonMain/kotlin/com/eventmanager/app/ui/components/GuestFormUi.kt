package com.eventmanager.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Assignment
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.eventmanager.app.data.models.GuestForm
import com.eventmanager.app.data.models.GuestFormExpiry
import com.eventmanager.app.data.models.GuestFormFieldLabelsCodec
import com.eventmanager.app.data.models.GuestFormLogoShape
import com.eventmanager.app.data.models.GuestFormOfferedAccess
import com.eventmanager.app.data.models.GuestFormPerson
import com.eventmanager.app.data.models.GuestFormSubmission
import com.eventmanager.app.data.models.GuestFormSubmissionCodec
import com.eventmanager.app.data.models.ManualTemporaryGuestEntry
import com.eventmanager.app.data.models.VenueAccess
import com.eventmanager.app.data.models.VenueAccessCatalog
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.data.models.isResponseChild
import com.eventmanager.app.data.models.offeredAccesses
import com.eventmanager.app.data.models.toManualEntries
import com.eventmanager.app.data.sync.InstitutionLogoStore
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import com.eventmanager.app.utils.QRCodeUtils
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** Amber used for spent temporary-guest entries and pending form reviews. */
val GuestFormPendingAmber = Color(0xFF8D6E00)

private data class GuestFormAttentionPalette(
    val accent: Color,
    val container: Color,
    val onContainer: Color,
    val muted: Color,
    val border: Color,
    val iconBg: Color,
)

@Composable
private fun rememberGuestFormAttentionPalette(): GuestFormAttentionPalette {
    val scheme = MaterialTheme.colorScheme
    val isDark = scheme.background.luminance() < 0.45f
    return remember(scheme, isDark) {
        if (isDark) {
            GuestFormAttentionPalette(
                accent = Color(0xFFFFC14D),
                container = lerp(scheme.surface, Color(0xFF8D6E00), 0.42f),
                onContainer = Color(0xFFFFF3D6),
                muted = Color(0xFFE8D5A8),
                border = Color(0xFFFFC14D).copy(alpha = 0.55f),
                iconBg = Color(0xFFFFC14D).copy(alpha = 0.18f),
            )
        } else {
            GuestFormAttentionPalette(
                accent = Color(0xFF9A6B00),
                container = lerp(scheme.surface, Color(0xFFFFC107), 0.32f),
                onContainer = Color(0xFF3E2A00),
                muted = Color(0xFF6B5320),
                border = Color(0xFFC9A227).copy(alpha = 0.55f),
                iconBg = Color(0xFFFFC107).copy(alpha = 0.28f),
            )
        }
    }
}

/**
 * Global top-right toast when a public guest-form answer arrives.
 * Works on welcome / billeterie / POS / admin. Click does nothing.
 */
@Composable
fun GuestFormArrivalToastHost(
    viewModel: EventManagerViewModel,
    modifier: Modifier = Modifier,
) {
    val enabled by viewModel.guestFormsEnabled.collectAsState()
    val pending by viewModel.pendingGuestForms.collectAsState()
    val palette = rememberGuestFormAttentionPalette()
    var seenIds by remember { mutableStateOf<Set<String>?>(null) }
    var toastForm by remember { mutableStateOf<GuestForm?>(null) }
    var visible by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val slideClearancePx = with(density) { 48.dp.roundToPx() }

    LaunchedEffect(enabled) {
        if (!enabled) return@LaunchedEffect
        while (true) {
            viewModel.refreshGuestFormsFromRemote()
            delay(12_000L)
        }
    }

    LaunchedEffect(pending, enabled) {
        if (!enabled) {
            seenIds = null
            visible = false
            toastForm = null
            return@LaunchedEffect
        }
        val ids = pending.map { it.formId }.toSet()
        val previous = seenIds
        if (previous == null) {
            seenIds = ids
            return@LaunchedEffect
        }
        val newcomers = pending.filter { it.formId !in previous }
        seenIds = ids
        try {
            for (form in newcomers) {
                toastForm = form
                visible = true
                delay(5_000L)
                visible = false
                // Exit tween is 300ms; wait it out so the Popup is not left mid-slide.
                delay(400L)
            }
        } finally {
            visible = false
            toastForm = null
        }
    }

    if (toastForm == null) return

    Popup(
        alignment = Alignment.TopEnd,
        offset = IntOffset(
            x = with(density) { (-16).dp.roundToPx() },
            y = with(density) { 16.dp.roundToPx() },
        ),
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(Modifier.clipToBounds()) {
            AnimatedVisibility(
                visible = visible && toastForm != null,
                enter = slideInHorizontally(
                    animationSpec = tween(320),
                    initialOffsetX = { full -> full + slideClearancePx },
                ) + fadeIn(animationSpec = tween(280)),
                exit = slideOutHorizontally(
                    animationSpec = tween(300),
                    targetOffsetX = { full -> full + slideClearancePx },
                ) + fadeOut(animationSpec = tween(240)),
                modifier = modifier,
            ) {
                val form = toastForm ?: return@AnimatedVisibility
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = palette.container,
                    contentColor = palette.onContainer,
                    border = BorderStroke(1.dp, palette.border),
                    shadowElevation = 6.dp,
                    tonalElevation = 0.dp,
                    modifier = Modifier
                        .widthIn(max = 340.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = { /* intentionally no-op */ },
                        ),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(palette.iconBg),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Assignment,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f, fill = false)) {
                            Text(
                                stringResource(Res.string.guest_form_pending_title),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.accent,
                            )
                            Text(
                                stringResource(Res.string.guest_form_pending_one, form.artistName),
                                style = MaterialTheme.typography.bodyMedium,
                                color = palette.onContainer,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun GuestFormCreatorDialog(
    viewModel: EventManagerViewModel,
    venues: List<VenueEntity>,
    venueAccesses: List<VenueAccess>,
    onDismiss: () -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.ensureInstitutionLogoFromEmail() }
    val institutionLogo by viewModel.institutionLogoPng.collectAsState()
    val hasInstitutionLogo = institutionLogo.isNotBlank()
    var venueName by remember { mutableStateOf<String?>(null) }
    var eventName by remember { mutableStateOf("") }
    var eventDateMillis by remember { mutableStateOf<Long?>(null) }
    var artistName by remember { mutableStateOf("") }
    var offeredIds by remember { mutableStateOf(setOf<String>()) }
    var accessMaxRequests by remember { mutableStateOf(mapOf<String, String>()) }
    var maxGuests by remember { mutableStateOf(GuestForm.DEFAULT_MAX_GUESTS.toString()) }
    var allowMultipleResponses by remember { mutableStateOf(false) }
    var expiryMode by remember { mutableStateOf(GuestFormExpiry.AFTER_RESPONSE) }
    var manualExpiry by remember { mutableStateOf<Long?>(null) }
    var showInstitutionLogo by remember { mutableStateOf(hasInstitutionLogo) }
    LaunchedEffect(hasInstitutionLogo) { if (hasInstitutionLogo) showInstitutionLogo = true }
    var institutionLogoShape by remember { mutableStateOf(GuestFormLogoShape.ROUNDED) }
    var institutionLogoInvert by remember { mutableStateOf(false) }
    var guestLogoUri by remember { mutableStateOf("") }
    var guestLogoShape by remember { mutableStateOf(GuestFormLogoShape.ROUNDED) }
    var guestLogoInvert by remember { mutableStateOf(false) }
    var prefillEmail by remember { mutableStateOf("") }
    var prefillPhone by remember { mutableStateOf("") }
    var askEmail by remember { mutableStateOf(true) }
    var askPhone by remember { mutableStateOf(true) }
    var fieldLabels by remember { mutableStateOf(mapOf<String, String>()) }
    var created by remember { mutableStateOf<GuestForm?>(null) }
    val venueAccessesForVenue = remember(venueAccesses, venueName) {
        VenueAccessCatalog.forVenue(venueAccesses, venueName.orEmpty())
    }
    val parsedMaxGuests = remember(maxGuests) {
        maxGuests.toIntOrNull()?.coerceIn(1, GuestForm.MAX_GUESTS_LIMIT)
            ?: GuestForm.DEFAULT_MAX_GUESTS
    }
    LaunchedEffect(parsedMaxGuests) {
        accessMaxRequests = accessMaxRequests.mapValues { (_, raw) ->
            val n = raw.toIntOrNull() ?: return@mapValues raw
            if (n > parsedMaxGuests) parsedMaxGuests.toString() else raw
        }
    }

    created?.let { form ->
        GuestFormLinkDialog(
            url = viewModel.guestFormUrl(form),
            artistName = form.artistName,
            allowMultipleResponses = form.allowMultipleResponses,
            onDismiss = onDismiss,
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.guest_form_create_title)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EventDatePickerField(
                    selectedDateMillis = eventDateMillis,
                    onDateSelected = { eventDateMillis = it },
                )
                OutlinedTextField(
                    value = eventName,
                    onValueChange = { eventName = it },
                    label = { Text(stringResource(Res.string.guest_form_event_name)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                OutlinedTextField(
                    value = artistName,
                    onValueChange = { artistName = it },
                    label = { Text(stringResource(Res.string.guest_form_artist)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                TemporaryGuestVenueDropdown(
                    venues = venues,
                    selectedVenueName = venueName,
                    onVenueSelected = { picked ->
                        venueName = picked
                        offeredIds = VenueAccessCatalog.forVenue(venueAccesses, picked.orEmpty())
                            .map { it.id }
                            .toSet()
                        accessMaxRequests = emptyMap()
                    },
                )
                Text(
                    stringResource(Res.string.guest_form_offered_access),
                    style = MaterialTheme.typography.labelMedium,
                )
                when {
                    venueName.isNullOrBlank() -> {
                        Text(
                            stringResource(Res.string.guest_form_offered_access_pick_venue),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    venueAccessesForVenue.isEmpty() -> {
                        Text(
                            stringResource(Res.string.guest_form_offered_access_empty),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    else -> {
                        GuestFormOfferedAccessQuotaEditor(
                            venueAccesses = venueAccessesForVenue,
                            offeredIds = offeredIds,
                            accessMaxRequests = accessMaxRequests,
                            maxGuests = parsedMaxGuests,
                            onToggleAccess = { id ->
                                offeredIds = if (offeredIds.contains(id)) {
                                    offeredIds - id
                                } else {
                                    offeredIds + id
                                }
                            },
                            onMaxRequestsChange = { id, raw ->
                                accessMaxRequests = accessMaxRequests + (id to raw)
                            },
                        )
                    }
                }
                OutlinedTextField(
                    value = maxGuests,
                    onValueChange = { maxGuests = it.filter(Char::isDigit).take(3) },
                    label = { Text(stringResource(Res.string.guest_form_max_guests)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            stringResource(Res.string.guest_form_allow_multiple),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(Res.string.guest_form_allow_multiple_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = allowMultipleResponses,
                        onCheckedChange = { allowMultipleResponses = it },
                    )
                }
                Text(
                    stringResource(Res.string.guest_form_expiry_label),
                    style = MaterialTheme.typography.labelMedium,
                )
                GuestFormExpiryPicker(
                    selected = expiryMode,
                    onSelected = { expiryMode = it },
                )
                if (expiryMode == GuestFormExpiry.MANUAL) {
                    EventDatePickerField(
                        selectedDateMillis = manualExpiry,
                        onDateSelected = { manualExpiry = it },
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(Res.string.guest_form_ask_email),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = askEmail,
                        onCheckedChange = { askEmail = it },
                    )
                }
                if (askEmail) {
                    OutlinedTextField(
                        value = prefillEmail,
                        onValueChange = { prefillEmail = it },
                        label = { Text(stringResource(Res.string.guest_form_prefill_email)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(Res.string.guest_form_ask_phone),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        checked = askPhone,
                        onCheckedChange = { askPhone = it },
                    )
                }
                if (askPhone) {
                    OutlinedTextField(
                        value = prefillPhone,
                        onValueChange = { prefillPhone = it },
                        label = { Text(stringResource(Res.string.guest_form_prefill_phone)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                GuestFormInstitutionLogoSection(
                    logoBase64 = institutionLogo,
                    included = showInstitutionLogo && hasInstitutionLogo,
                    onIncludedChange = { showInstitutionLogo = it },
                    shape = institutionLogoShape,
                    onShapeChange = { institutionLogoShape = it },
                    invert = institutionLogoInvert,
                    onInvertChange = { institutionLogoInvert = it },
                )
                GuestFormGuestLogoSection(
                    currentUri = guestLogoUri,
                    onPicked = { guestLogoUri = it },
                    onClear = { guestLogoUri = "" },
                    shape = guestLogoShape,
                    onShapeChange = { guestLogoShape = it },
                    invert = guestLogoInvert,
                    onInvertChange = { guestLogoInvert = it },
                )
                GuestFormFieldLabelsSection(
                    labels = fieldLabels,
                    onLabelChange = { key, value ->
                        fieldLabels = fieldLabels + (key to value)
                    },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val date = eventDateMillis ?: return@Button
                    val artist = artistName.trim()
                    if (artist.isEmpty()) return@Button
                    viewModel.createGuestForm(
                        venueName = venueName.orEmpty(),
                        eventName = eventName.trim(),
                        eventDateMillis = date,
                        artistName = artist,
                        offeredAccessIds = offeredIds,
                        accessMaxRequests = offeredIds.associateWith { id ->
                            accessMaxRequests[id]?.toIntOrNull() ?: 0
                        },
                        maxGuests = parsedMaxGuests,
                        expiryMode = expiryMode,
                        manualExpiryMillis = manualExpiry ?: 0L,
                        showInstitutionLogo = showInstitutionLogo && hasInstitutionLogo,
                        institutionLogoShape = institutionLogoShape,
                        institutionLogoInvert = institutionLogoInvert,
                        guestLogoDataUri = guestLogoUri,
                        guestLogoShape = guestLogoShape,
                        guestLogoInvert = guestLogoInvert,
                        askEmail = askEmail,
                        askPhone = askPhone,
                        prefillEmail = prefillEmail,
                        prefillPhone = prefillPhone,
                        fieldLabels = fieldLabels,
                        allowMultipleResponses = allowMultipleResponses,
                        onCreated = { created = it },
                    )
                },
                enabled = !artistName.isBlank() && eventDateMillis != null,
            ) {
                Text(stringResource(Res.string.guest_form_create_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        },
    )
}

@Composable
private fun GuestFormFieldLabelsSection(
    labels: Map<String, String>,
    onLabelChange: (key: String, value: String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.guest_form_labels_title),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(Res.string.guest_form_labels_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                GuestFormFieldLabelField(
                    value = labels[GuestFormFieldLabelsCodec.KEY_COMMENTS].orEmpty(),
                    onValueChange = { onLabelChange(GuestFormFieldLabelsCodec.KEY_COMMENTS, it) },
                    label = stringResource(Res.string.guest_form_label_comments),
                    placeholder = stringResource(Res.string.guest_form_label_comments_default),
                )
                GuestFormFieldLabelField(
                    value = labels[GuestFormFieldLabelsCodec.KEY_COMMENTS_PLACEHOLDER].orEmpty(),
                    onValueChange = {
                        onLabelChange(GuestFormFieldLabelsCodec.KEY_COMMENTS_PLACEHOLDER, it)
                    },
                    label = stringResource(Res.string.guest_form_label_comments_placeholder),
                    placeholder = stringResource(Res.string.guest_form_label_comments_placeholder_default),
                )
                GuestFormFieldLabelField(
                    value = labels[GuestFormFieldLabelsCodec.KEY_ACCESS_LABEL].orEmpty(),
                    onValueChange = { onLabelChange(GuestFormFieldLabelsCodec.KEY_ACCESS_LABEL, it) },
                    label = stringResource(Res.string.guest_form_label_access),
                    placeholder = stringResource(Res.string.guest_form_label_access_default),
                )
                GuestFormFieldLabelField(
                    value = labels[GuestFormFieldLabelsCodec.KEY_DISCLAIMER].orEmpty(),
                    onValueChange = { onLabelChange(GuestFormFieldLabelsCodec.KEY_DISCLAIMER, it) },
                    label = stringResource(Res.string.guest_form_label_disclaimer),
                    placeholder = stringResource(Res.string.guest_form_label_disclaimer_default),
                    maxLength = GuestFormFieldLabelsCodec.MAX_DISCLAIMER_LENGTH,
                    singleLine = false,
                    minLines = 3,
                )
                GuestFormFieldLabelField(
                    value = labels[GuestFormFieldLabelsCodec.KEY_EMAIL].orEmpty(),
                    onValueChange = { onLabelChange(GuestFormFieldLabelsCodec.KEY_EMAIL, it) },
                    label = stringResource(Res.string.guest_form_label_email),
                    placeholder = stringResource(Res.string.guest_form_label_email_default),
                )
                GuestFormFieldLabelField(
                    value = labels[GuestFormFieldLabelsCodec.KEY_PHONE].orEmpty(),
                    onValueChange = { onLabelChange(GuestFormFieldLabelsCodec.KEY_PHONE, it) },
                    label = stringResource(Res.string.guest_form_label_phone),
                    placeholder = stringResource(Res.string.guest_form_label_phone_default),
                )
                GuestFormFieldLabelField(
                    value = labels[GuestFormFieldLabelsCodec.KEY_PEOPLE_HEADING].orEmpty(),
                    onValueChange = { onLabelChange(GuestFormFieldLabelsCodec.KEY_PEOPLE_HEADING, it) },
                    label = stringResource(Res.string.guest_form_label_people),
                    placeholder = stringResource(Res.string.guest_form_label_people_default),
                )
            }
        }
    }
}

@Composable
private fun GuestFormFieldLabelField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    placeholder: String,
    maxLength: Int = GuestFormFieldLabelsCodec.MAX_LABEL_LENGTH,
    singleLine: Boolean = true,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.take(maxLength)) },
        label = { Text(label) },
        placeholder = { Text(placeholder) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = singleLine,
        minLines = minLines,
    )
}

@Composable
private fun GuestFormOfferedAccessQuotaEditor(
    venueAccesses: List<VenueAccess>,
    offeredIds: Set<String>,
    accessMaxRequests: Map<String, String>,
    maxGuests: Int,
    onToggleAccess: (String) -> Unit,
    onMaxRequestsChange: (id: String, raw: String) -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val single = venueAccesses.size == 1
            venueAccesses.forEach { access ->
                val offered = offeredIds.contains(access.id)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (single) {
                        Text(
                            text = access.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        Switch(
                            checked = offered,
                            onCheckedChange = { onToggleAccess(access.id) },
                        )
                    } else {
                        FilterChip(
                            selected = offered,
                            onClick = { onToggleAccess(access.id) },
                            modifier = Modifier.weight(1f, fill = false),
                            label = {
                                Text(
                                    access.name,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            },
                            leadingIcon = if (offered) {
                                {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        modifier = Modifier.size(FilterChipDefaults.IconSize),
                                    )
                                }
                            } else {
                                null
                            },
                        )
                    }
                    if (offered) {
                        OutlinedTextField(
                            value = accessMaxRequests[access.id].orEmpty(),
                            onValueChange = { raw ->
                                val digits = raw.filter(Char::isDigit).take(3)
                                val n = digits.toIntOrNull()
                                onMaxRequestsChange(
                                    access.id,
                                    if (n != null && n > maxGuests) maxGuests.toString() else digits,
                                )
                            },
                            label = {
                                Text(
                                    stringResource(Res.string.guest_form_access_max_requests),
                                    maxLines = 1,
                                    overflow = TextOverflow.Clip,
                                    softWrap = false,
                                )
                            },
                            modifier = Modifier
                                .widthIn(min = 168.dp)
                                .weight(1f),
                            singleLine = true,
                        )
                    }
                }
            }
            Text(
                stringResource(Res.string.guest_form_access_max_requests_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuestFormExpiryPicker(
    selected: GuestFormExpiry,
    onSelected: (GuestFormExpiry) -> Unit,
) {
    val options = listOf(
        GuestFormExpiry.AFTER_RESPONSE to stringResource(Res.string.guest_form_expiry_after_response),
        GuestFormExpiry.DAY_BEFORE to stringResource(Res.string.guest_form_expiry_day_before),
        GuestFormExpiry.EVENT_DAY to stringResource(Res.string.guest_form_expiry_event_day),
        GuestFormExpiry.DAY_AFTER to stringResource(Res.string.guest_form_expiry_day_after),
        GuestFormExpiry.MANUAL to stringResource(Res.string.guest_form_expiry_manual),
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { (mode, label) ->
            FilterChip(
                selected = selected == mode,
                onClick = { onSelected(mode) },
                label = { Text(label) },
            )
        }
    }
}

@Composable
private fun GuestFormInstitutionLogoSection(
    logoBase64: String,
    included: Boolean,
    onIncludedChange: (Boolean) -> Unit,
    shape: GuestFormLogoShape,
    onShapeChange: (GuestFormLogoShape) -> Unit,
    invert: Boolean,
    onInvertChange: (Boolean) -> Unit,
) {
    val previewBytes = remember(logoBase64) { InstitutionLogoStore.decode(logoBase64) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(Res.string.guest_form_include_logo),
                    style = MaterialTheme.typography.bodyMedium,
                )
                if (previewBytes == null) {
                    Text(
                        stringResource(Res.string.guest_form_include_logo_missing),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Switch(
                checked = included && previewBytes != null,
                onCheckedChange = onIncludedChange,
                enabled = previewBytes != null,
            )
        }
        if (previewBytes != null && included) {
            GuestFormLogoPreview(
                bytes = previewBytes,
                shape = shape,
                invert = invert,
                contentDescription = stringResource(Res.string.guest_form_include_logo),
            )
            GuestFormLogoStyleControls(
                shape = shape,
                onShapeChange = onShapeChange,
                invert = invert,
                onInvertChange = onInvertChange,
            )
        }
    }
}

@Composable
private fun GuestFormGuestLogoSection(
    currentUri: String,
    onPicked: (String) -> Unit,
    onClear: () -> Unit,
    shape: GuestFormLogoShape,
    onShapeChange: (GuestFormLogoShape) -> Unit,
    invert: Boolean,
    onInvertChange: (Boolean) -> Unit,
) {
    val pick = rememberProfilePhotoPicker { bytes ->
        if (bytes.isEmpty()) return@rememberProfilePhotoPicker
        val encoded = InstitutionLogoStore.encode(bytes)
        if (!InstitutionLogoStore.isWithinSizeLimit(encoded)) return@rememberProfilePhotoPicker
        val mime = if (bytes.size >= 8 && bytes[0] == 0x89.toByte()) "image/png" else "image/jpeg"
        onPicked("data:$mime;base64,$encoded")
    }
    val previewBytes = remember(currentUri) { decodeGuestFormLogoBytes(currentUri) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(Res.string.guest_form_guest_logo),
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            stringResource(Res.string.guest_form_guest_logo_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (previewBytes != null) {
            GuestFormLogoPreview(
                bytes = previewBytes,
                shape = shape,
                invert = invert,
                contentDescription = stringResource(Res.string.guest_form_guest_logo),
            )
            GuestFormLogoStyleControls(
                shape = shape,
                onShapeChange = onShapeChange,
                invert = invert,
                onInvertChange = onInvertChange,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = pick) {
                Text(
                    if (previewBytes != null) stringResource(Res.string.guest_form_guest_logo_change)
                    else stringResource(Res.string.guest_form_guest_logo_add),
                )
            }
            if (currentUri.isNotBlank()) {
                TextButton(onClick = onClear) {
                    Text(stringResource(Res.string.delete))
                }
            }
        }
    }
}

@Composable
private fun GuestFormLogoPreview(
    bytes: ByteArray,
    shape: GuestFormLogoShape,
    invert: Boolean,
    contentDescription: String,
) {
    val clipShape = when (shape) {
        GuestFormLogoShape.SQUARE -> RoundedCornerShape(0.dp)
        GuestFormLogoShape.ROUNDED -> RoundedCornerShape(12.dp)
        GuestFormLogoShape.CIRCLE -> CircleShape
    }
    ProfileDecodedImage(
        bytes = bytes,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        colorFilter = if (invert) invertColorFilter() else null,
        modifier = Modifier
            .size(88.dp)
            .clip(clipShape),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GuestFormLogoStyleControls(
    shape: GuestFormLogoShape,
    onShapeChange: (GuestFormLogoShape) -> Unit,
    invert: Boolean,
    onInvertChange: (Boolean) -> Unit,
) {
    Text(
        stringResource(Res.string.guest_form_logo_shape),
        style = MaterialTheme.typography.labelMedium,
    )
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterChip(
            selected = shape == GuestFormLogoShape.SQUARE,
            onClick = { onShapeChange(GuestFormLogoShape.SQUARE) },
            label = { Text(stringResource(Res.string.guest_form_logo_shape_square)) },
        )
        FilterChip(
            selected = shape == GuestFormLogoShape.ROUNDED,
            onClick = { onShapeChange(GuestFormLogoShape.ROUNDED) },
            label = { Text(stringResource(Res.string.guest_form_logo_shape_rounded)) },
        )
        FilterChip(
            selected = shape == GuestFormLogoShape.CIRCLE,
            onClick = { onShapeChange(GuestFormLogoShape.CIRCLE) },
            label = { Text(stringResource(Res.string.guest_form_logo_shape_circle)) },
        )
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(Res.string.guest_form_logo_invert),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Switch(checked = invert, onCheckedChange = onInvertChange)
    }
}

private fun invertColorFilter(): ColorFilter {
    val matrix = floatArrayOf(
        -1f, 0f, 0f, 0f, 255f,
        0f, -1f, 0f, 0f, 255f,
        0f, 0f, -1f, 0f, 255f,
        0f, 0f, 0f, 1f, 0f,
    )
    return ColorFilter.colorMatrix(ColorMatrix(matrix))
}

private fun decodeGuestFormLogoBytes(dataUri: String): ByteArray? {
    val marker = "base64,"
    val idx = dataUri.indexOf(marker)
    if (idx < 0) return null
    return InstitutionLogoStore.decode(dataUri.substring(idx + marker.length))
}

@Composable
fun GuestFormLinkDialog(
    url: String,
    artistName: String,
    allowMultipleResponses: Boolean = false,
    onDismiss: () -> Unit,
) {
    val qr: ImageBitmap? = remember(url) {
        if (url.isBlank()) null else QRCodeUtils.generateQrImageBitmap(url, 420)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.guest_form_link_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(
                        if (allowMultipleResponses) {
                            Res.string.guest_form_link_body_multi
                        } else {
                            Res.string.guest_form_link_body
                        },
                        artistName,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
                qr?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        modifier = Modifier.size(200.dp),
                    )
                }
                if (url.isNotBlank()) {
                    CopyableUrlBox(url = url)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.close))
            }
        },
    )
}

@Composable
private fun CopyableUrlBox(
    url: String,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(url) { mutableStateOf(false) }
    OutlinedTextField(
        value = url,
        onValueChange = {},
        readOnly = true,
        singleLine = true,
        modifier = modifier.fillMaxWidth(),
        trailingIcon = {
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(url))
                    copied = true
                },
            ) {
                Text(
                    if (copied) stringResource(Res.string.guest_form_link_copied)
                    else stringResource(Res.string.guest_form_copy_link),
                )
            }
        },
    )
}

@Composable
fun GuestFormPendingCard(
    viewModel: EventManagerViewModel,
    isPhone: Boolean,
    modifier: Modifier = Modifier,
) {
    val pending by viewModel.pendingGuestForms.collectAsState()
    if (pending.isEmpty()) return
    var reviewing by remember { mutableStateOf<GuestForm?>(null) }
    val palette = rememberGuestFormAttentionPalette()
    val shape = RoundedCornerShape(if (isPhone) 14.dp else 16.dp)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = if (isPhone) 4.dp else 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        pending.forEach { form ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { reviewing = form },
                shape = shape,
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                colors = CardDefaults.cardColors(
                    containerColor = palette.container,
                    contentColor = palette.onContainer,
                ),
                border = BorderStroke(1.dp, palette.border),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(IntrinsicSize.Min),
                ) {
                    Box(
                        modifier = Modifier
                            .width(4.dp)
                            .fillMaxHeight()
                            .background(palette.accent),
                    )
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .padding(if (isPhone) 12.dp else 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(if (isPhone) 38.dp else 42.dp)
                                .clip(CircleShape)
                                .background(palette.iconBg)
                                .border(1.dp, palette.border.copy(alpha = 0.35f), CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.AutoMirrored.Filled.Assignment,
                                contentDescription = null,
                                tint = palette.accent,
                                modifier = Modifier.size(if (isPhone) 20.dp else 22.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                stringResource(Res.string.guest_form_pending_title),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = palette.accent,
                            )
                            Text(
                                stringResource(Res.string.guest_form_pending_one, form.artistName),
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Medium,
                                color = palette.onContainer,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                stringResource(Res.string.guest_form_pending_cta),
                                style = MaterialTheme.typography.labelSmall,
                                color = palette.muted,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = palette.accent.copy(alpha = 0.85f),
                        )
                    }
                }
            }
        }
    }
    reviewing?.let { form ->
        GuestFormReviewDialog(
            form = form,
            viewModel = viewModel,
            onDismiss = { reviewing = null },
        )
    }
}

@Composable
fun GuestFormReviewDialog(
    form: GuestForm,
    viewModel: EventManagerViewModel,
    onDismiss: () -> Unit,
) {
    val submission = remember(form.submissionJson) {
        GuestFormSubmissionCodec.decode(form.submissionJson)
    }
    var email by remember(submission) { mutableStateOf(submission?.contactEmail.orEmpty()) }
    var phone by remember(submission) { mutableStateOf(submission?.emergencyPhone.orEmpty()) }
    var notes by remember(submission) { mutableStateOf(submission?.notes.orEmpty()) }
    var people by remember(submission) {
        mutableStateOf(submission?.people ?: emptyList())
    }
    val offered = form.offeredAccesses()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.guest_form_review_title, form.artistName)) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (form.isResponseChild) {
                    Text(
                        stringResource(Res.string.guest_form_review_response_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!form.eventName.isBlank()) {
                    Text(form.eventName, style = MaterialTheme.typography.titleSmall)
                }
                if (!form.venueName.isBlank()) {
                    Text(form.venueName, style = MaterialTheme.typography.bodySmall)
                }
                if (form.askEmail) {
                    OutlinedTextField(
                        value = email,
                        onValueChange = { email = it },
                        label = { Text(stringResource(Res.string.temp_guest_contact_email_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                if (form.askPhone) {
                    OutlinedTextField(
                        value = phone,
                        onValueChange = { phone = it },
                        label = { Text(stringResource(Res.string.temp_guest_contact_phone_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text(stringResource(Res.string.notes)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (people.isEmpty()) {
                    Text(stringResource(Res.string.guest_form_review_empty))
                }
                people.forEachIndexed { index, person ->
                    GuestFormReviewPersonCard(
                        index = index,
                        person = person,
                        offered = offered,
                        onChange = { updated ->
                            people = people.toMutableList().also { it[index] = updated }
                        },
                        onRemove = {
                            people = people.toMutableList().also { it.removeAt(index) }
                        },
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val entries = people
                        .map { ManualTemporaryGuestEntry(it.name.trim(), it.requestedAccessIds) }
                        .filter { it.name.isNotEmpty() }
                    if (entries.isEmpty()) return@Button
                    viewModel.acceptGuestForm(form, entries, email, phone, notes)
                    onDismiss()
                },
                enabled = people.any { it.name.isNotBlank() },
            ) {
                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(stringResource(Res.string.guest_form_review_accept))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedButton(
                    onClick = {
                        viewModel.rejectGuestForm(form)
                        onDismiss()
                    },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) {
                    Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(stringResource(Res.string.guest_form_review_reject))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(Res.string.cancel))
                }
            }
        },
    )
}

@Composable
private fun GuestFormReviewPersonCard(
    index: Int,
    person: GuestFormPerson,
    offered: List<GuestFormOfferedAccess>,
    onChange: (GuestFormPerson) -> Unit,
    onRemove: () -> Unit,
) {
    val accesses = offered.map { VenueAccess(id = it.id, venueName = "", name = it.name) }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = person.name,
                    onValueChange = { onChange(person.copy(name = it)) },
                    label = { Text(stringResource(Res.string.guest_form_person_n, index + 1)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                IconButton(onClick = onRemove) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(Res.string.delete))
                }
            }
            if (accesses.isNotEmpty()) {
                TemporaryGuestAccessSelector(
                    venueAccesses = accesses,
                    selectedAccessIds = person.requestedAccessIds,
                    onToggleAccess = { id ->
                        val next = if (person.requestedAccessIds.contains(id)) {
                            person.requestedAccessIds - id
                        } else {
                            person.requestedAccessIds + id
                        }
                        onChange(person.copy(requestedAccessIds = next))
                    },
                )
            }
        }
    }
}
