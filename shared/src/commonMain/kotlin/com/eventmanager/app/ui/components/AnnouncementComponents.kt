package com.eventmanager.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.platform.playAnnouncementReceivedFeedback
import com.eventmanager.app.ui.utils.isTablet
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import org.jetbrains.compose.resources.stringResource
import kotlinx.coroutines.delay

private const val ANNOUNCEMENT_AUTO_DISMISS_MS = 5 * 60 * 1000L

@Composable
fun SendAnnouncementButton(
    isPhone: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    OutlinedButton(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(
            1.dp,
            colorScheme.outlineVariant.copy(alpha = 0.45f),
        ),
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = colorScheme.surface,
            contentColor = colorScheme.onSurfaceVariant,
        ),
        contentPadding = PaddingValues(
            horizontal = if (isPhone) 14.dp else 16.dp,
            vertical = if (isPhone) 10.dp else 12.dp,
        ),
    ) {
        Icon(
            Icons.Default.Campaign,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(Res.string.announcement_button_label),
            style = MaterialTheme.typography.bodyMedium,
            color = colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SendAnnouncementDialog(
    venues: List<VenueEntity>,
    isSending: Boolean,
    onDismiss: () -> Unit,
    onSend: (targetVenueIds: List<Long>, title: String, message: String) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var allVenues by remember { mutableStateOf(true) }
    var selectedVenueIds by remember { mutableStateOf(setOf<Long>()) }
    val scrollState = rememberScrollState()
    val colorScheme = MaterialTheme.colorScheme
    val activeVenues = remember(venues) { venues.filter { it.isActive } }
    val compactLayout = !isTablet()
    val contentPadding = if (compactLayout) 16.dp else 20.dp
    val fieldMinHeight = if (compactLayout) 88.dp else 120.dp

    Dialog(
        onDismissRequest = { if (!isSending) onDismiss() },
        properties = phoneFractionDialogProperties(
            dismissOnBackPress = !isSending,
            dismissOnClickOutside = false,
        ),
    ) {
        DialogFractionSizer(
            profile = FractionalDialogProfile.Card,
            desktopMaxWidth = 480.dp,
        ) { maxDialogWidth, maxDialogHeight ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = maxDialogWidth)
                    .heightIn(max = maxDialogHeight.coerceAtMost(640.dp))
                    .padding(16.dp),
                shape = RoundedCornerShape(20.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(contentPadding),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = stringResource(Res.string.announcement_send_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = { if (!isSending) onDismiss() },
                            enabled = !isSending,
                            modifier = Modifier.size(40.dp),
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = stringResource(Res.string.close),
                            )
                        }
                    }

                    Text(
                        text = stringResource(Res.string.announcement_button_label),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                    )

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .fillMaxWidth()
                            .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(Res.string.announcement_all_venues),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    text = stringResource(Res.string.announcement_destination_label),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = colorScheme.onSurfaceVariant,
                                )
                            }
                            Switch(
                                checked = allVenues,
                                onCheckedChange = {
                                    allVenues = it
                                    if (it) selectedVenueIds = emptySet()
                                },
                                enabled = !isSending,
                            )
                        }

                        if (!allVenues) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                activeVenues.forEach { venue ->
                                    val selected = venue.id in selectedVenueIds
                                    FilterChip(
                                        selected = selected,
                                        onClick = {
                                            selectedVenueIds = if (selected) {
                                                selectedVenueIds - venue.id
                                            } else {
                                                selectedVenueIds + venue.id
                                            }
                                        },
                                        enabled = !isSending,
                                        label = { Text(venue.name) },
                                        leadingIcon = if (selected) {
                                            {
                                                Icon(
                                                    Icons.Default.Place,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp),
                                                )
                                            }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        }

                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text(stringResource(Res.string.announcement_title_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            enabled = !isSending,
                        )
                        OutlinedTextField(
                            value = message,
                            onValueChange = { message = it },
                            label = { Text(stringResource(Res.string.announcement_message_label)) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = fieldMinHeight),
                            minLines = if (compactLayout) 3 else 4,
                            enabled = !isSending,
                        )
                    }

                    val canSend = !isSending && title.isNotBlank() && message.isNotBlank() &&
                        (allVenues || selectedVenueIds.isNotEmpty())

                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            enabled = !isSending,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                text = stringResource(Res.string.cancel),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Button(
                            onClick = {
                                val targets = if (allVenues) {
                                    activeVenues.map { it.id }
                                } else {
                                    selectedVenueIds.toList()
                                }
                                onSend(targets, title.trim(), message.trim())
                            },
                            enabled = canSend,
                            modifier = Modifier.weight(1.4f),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(
                                horizontal = if (compactLayout) 10.dp else 16.dp,
                                vertical = 10.dp,
                            ),
                        ) {
                            if (isSending) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = colorScheme.onPrimary,
                                )
                            } else {
                                Icon(
                                    Icons.AutoMirrored.Filled.Send,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = stringResource(Res.string.announcement_send_button),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AnnouncementPopup(
    announcement: AnnouncementDisplay,
    onDismiss: () -> Unit,
) {
    val platformContext = LocalPlatformContext.current
    val messageScroll = rememberScrollState()
    val colorScheme = MaterialTheme.colorScheme
    val cardShape = RoundedCornerShape(20.dp)
    val remainingProgress = remember(announcement.venueKey, announcement.sentAt) { Animatable(1f) }

    LaunchedEffect(announcement.venueKey, announcement.sentAt) {
        playAnnouncementReceivedFeedback(platformContext)
        remainingProgress.snapTo(1f)
        remainingProgress.animateTo(
            targetValue = 0f,
            animationSpec = tween(
                durationMillis = ANNOUNCEMENT_AUTO_DISMISS_MS.toInt(),
                easing = LinearEasing,
            ),
        )
        onDismiss()
    }

    var entered by remember(announcement.venueKey, announcement.sentAt) { mutableStateOf(false) }
    LaunchedEffect(announcement.venueKey, announcement.sentAt) {
        entered = false
        delay(16)
        entered = true
    }
    val slideOffset by animateFloatAsState(
        targetValue = if (entered) 0f else -24f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "announcementSlide",
    )
    val alpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(220),
        label = "announcementFade",
    )

    Popup(
        alignment = Alignment.TopCenter,
        onDismissRequest = onDismiss,
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Card(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .widthIn(max = 480.dp)
                .graphicsLayer {
                    translationY = slideOffset
                    this.alpha = alpha
                },
            shape = cardShape,
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            colors = CardDefaults.cardColors(containerColor = colorScheme.surfaceContainerHigh),
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 20.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Campaign,
                        contentDescription = null,
                        tint = colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(Res.string.announcement_popup_title),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = colorScheme.primary,
                        )
                        Text(
                            text = stringResource(Res.string.announcement_popup_from, announcement.venueName),
                            style = MaterialTheme.typography.bodySmall,
                            color = colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(Res.string.close),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .padding(horizontal = 20.dp)
                        .heightIn(max = 220.dp)
                        .verticalScroll(messageScroll),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (announcement.title.isNotBlank()) {
                        Text(
                            text = announcement.title,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = colorScheme.onSurface,
                        )
                    }
                    Text(
                        text = announcement.message,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 24.sp),
                        color = colorScheme.onSurface,
                    )
                }

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .align(Alignment.End)
                        .padding(end = 8.dp),
                ) {
                    Text(stringResource(Res.string.announcement_popup_close))
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(colorScheme.surfaceContainerHighest),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(remainingProgress.value.coerceIn(0f, 1f))
                            .height(4.dp)
                            .background(colorScheme.primary.copy(alpha = 0.85f)),
                    )
                }
            }
        }
    }
}
