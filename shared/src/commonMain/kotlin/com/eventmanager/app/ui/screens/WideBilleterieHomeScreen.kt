package com.eventmanager.app.ui.screens

import com.eventmanager.app.ui.components.KeepScreenAwakeToggle
import com.eventmanager.app.ui.components.KeepAwakeSpace
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PointOfSale
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import com.eventmanager.app.resources.billeterie_desktop_back
import com.eventmanager.app.resources.billeterie_desktop_guest_list_hint
import com.eventmanager.app.resources.billeterie_desktop_overview_label
import com.eventmanager.app.resources.billeterie_desktop_scanner_hint
import com.eventmanager.app.resources.billeterie_button_guest_list
import com.eventmanager.app.resources.billeterie_button_scanner
import com.eventmanager.app.resources.billeterie_stat_permanent_guests
import com.eventmanager.app.resources.billeterie_stat_temporary_guests_today
import com.eventmanager.app.resources.billeterie_stat_total_without_invites
import com.eventmanager.app.resources.billeterie_stat_volunteers_on_list
import com.eventmanager.app.resources.dashboard_calculation_time_info
import com.eventmanager.app.resources.dashboard_date_offset_hours
import com.eventmanager.app.resources.dashboard_date_offset_zero
import com.eventmanager.app.resources.pos_welcome_button
import com.eventmanager.app.resources.settings_title
import com.eventmanager.app.resources.setup_back
import com.eventmanager.app.resources.ticket_check_mode
import com.eventmanager.app.resources.welcome_pos_hint
import com.eventmanager.app.resources.welcome_ticketing_hint
import com.eventmanager.app.ui.components.FirebaseOrgSwitcher
import com.eventmanager.app.ui.components.FirebaseOrgSwitcherPlacement
import com.eventmanager.app.ui.components.PeopleCounter
import com.eventmanager.app.ui.components.SendAnnouncementButton
import com.eventmanager.app.ui.components.SyncStatusPill
import com.eventmanager.app.ui.utils.GuestListDefaultZoneId
import com.eventmanager.app.ui.utils.rememberGuestListEffectiveToday
import com.eventmanager.app.ui.viewmodel.EventManagerViewModel
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.Font
import org.jetbrains.compose.resources.stringResource

@Composable
fun WideBilleterieHomeScreen(
    guests: List<Guest>,
    viewModel: EventManagerViewModel,
    onBack: () -> Unit,
    onOpenGuestList: () -> Unit,
    onOpenScanner: () -> Unit,
    onOpenPos: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    hoverEnabled: Boolean = false,
    /**
     * Tablet portrait: stack the Guest list / POS buttons side by side above a full-width
     * people counter instead of the landscape two-column row (counter text needs width).
     */
    stackCounterBelowActions: Boolean = false,
) {
    val platformContext = LocalPlatformContext.current
    val settingsManager = remember(platformContext) { settingsManagerFor(platformContext) }
    val isPeopleCounterVisible = settingsManager.isPeopleCounterVisible()
    val isClockVisible = settingsManager.isBilleterieClockVisible()
    val dateChangeOffsetHours = remember { settingsManager.getDateChangeOffsetHours() }
    val guestListZone = GuestListDefaultZoneId
    val guestListEffectiveToday = rememberGuestListEffectiveToday(
        zone = guestListZone,
        offsetHours = dateChangeOffsetHours,
    )
    val announcementsSendEnabled by viewModel.announcementsBilleterieSendEnabled.collectAsState()

    LaunchedEffect(isPeopleCounterVisible) {
        if (isPeopleCounterVisible) {
            viewModel.refreshVenuesForPeopleCounterQuietly()
        }
    }

    val (permanentGuestCount, temporaryGuestCount) = remember(guests, guestListEffectiveToday) {
        var permanent = 0
        var temporary = 0
        guests.forEach { guest ->
            when {
                guest.isTemporaryGuest -> {
                    val ts = guest.temporaryEventDate ?: return@forEach
                    val eventDate = java.time.Instant.ofEpochMilli(ts)
                        .atZone(guestListZone)
                        .toLocalDate()
                    if (eventDate == guestListEffectiveToday) temporary++
                }
                guest.isVolunteerBenefit -> Unit
                else -> permanent++
            }
        }
        permanent to temporary
    }
    val volunteersOnList = remember(guests) { guests.count { it.isVolunteerBenefit } }
    val totalWithoutInvites = permanentGuestCount + temporaryGuestCount + volunteersOnList

    Column(modifier = modifier.fillMaxSize()) {
        WideBilleterieTopBar(
            viewModel = viewModel,
            onBack = onBack,
            onOpenSettings = onOpenSettings,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 48.dp, vertical = 32.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                if (isPeopleCounterVisible) {
                    WideBilleterieHeaderBanner(
                        settingsManager = settingsManager,
                        showClock = isClockVisible,
                        permanentGuestCount = permanentGuestCount,
                        temporaryGuestCount = temporaryGuestCount,
                        volunteersOnList = volunteersOnList,
                        totalWithoutInvites = totalWithoutInvites,
                    )

                    WideBilleterieScannerHero(
                        title = stringResource(Res.string.billeterie_button_scanner),
                        subtitle = stringResource(Res.string.billeterie_desktop_scanner_hint),
                        onClick = onOpenScanner,
                        hoverEnabled = hoverEnabled,
                    )

                    if (stackCounterBelowActions) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Max),
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            WideBilleterieSecondaryAction(
                                title = stringResource(Res.string.billeterie_button_guest_list),
                                subtitle = stringResource(Res.string.billeterie_desktop_guest_list_hint),
                                icon = Icons.Default.List,
                                onClick = onOpenGuestList,
                                prominent = true,
                                hoverEnabled = hoverEnabled,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                            WideBilleterieSecondaryAction(
                                title = stringResource(Res.string.pos_welcome_button),
                                subtitle = stringResource(Res.string.welcome_pos_hint),
                                icon = Icons.Default.PointOfSale,
                                onClick = onOpenPos,
                                prominent = true,
                                hoverEnabled = hoverEnabled,
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight(),
                            )
                        }
                        PeopleCounter(
                            isPhone = false,
                            useDesktopLayout = true,
                            viewModel = viewModel,
                            // The desktop counter fills its height (its count area uses weight),
                            // so inside this scrolling column it needs a bounded height: use the
                            // landscape height (two action cards + spacing) or the count collapses.
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(WideBilleterieActionCardHeight * 2 + 16.dp),
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(IntrinsicSize.Max),
                            horizontalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            Column(
                                modifier = Modifier
                                    .weight(0.34f)
                                    .fillMaxHeight()
                                    .widthIn(min = 300.dp, max = 420.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                WideBilleterieSecondaryAction(
                                    title = stringResource(Res.string.billeterie_button_guest_list),
                                    subtitle = stringResource(Res.string.billeterie_desktop_guest_list_hint),
                                    icon = Icons.Default.List,
                                    onClick = onOpenGuestList,
                                    prominent = true,
                                    hoverEnabled = hoverEnabled,
                                    modifier = Modifier.weight(1f),
                                )
                                WideBilleterieSecondaryAction(
                                    title = stringResource(Res.string.pos_welcome_button),
                                    subtitle = stringResource(Res.string.welcome_pos_hint),
                                    icon = Icons.Default.PointOfSale,
                                    onClick = onOpenPos,
                                    prominent = true,
                                    hoverEnabled = hoverEnabled,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            PeopleCounter(
                                isPhone = false,
                                useDesktopLayout = true,
                                viewModel = viewModel,
                                modifier = Modifier
                                    .weight(0.66f)
                                    .fillMaxHeight(),
                            )
                        }
                    }
                } else {
                    WideBilleterieHeaderBanner(
                        settingsManager = settingsManager,
                        showClock = isClockVisible,
                    )

                    WideBilleterieScannerHero(
                        title = stringResource(Res.string.billeterie_button_scanner),
                        subtitle = stringResource(Res.string.billeterie_desktop_scanner_hint),
                        onClick = onOpenScanner,
                        hoverEnabled = hoverEnabled,
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        WideBilleterieSecondaryAction(
                            title = stringResource(Res.string.billeterie_button_guest_list),
                            subtitle = stringResource(Res.string.billeterie_desktop_guest_list_hint),
                            icon = Icons.Default.List,
                            onClick = onOpenGuestList,
                            hoverEnabled = hoverEnabled,
                            modifier = Modifier.weight(1f),
                        )
                        WideBilleterieSecondaryAction(
                            title = stringResource(Res.string.pos_welcome_button),
                            subtitle = stringResource(Res.string.welcome_pos_hint),
                            icon = Icons.Default.PointOfSale,
                            onClick = onOpenPos,
                            hoverEnabled = hoverEnabled,
                            modifier = Modifier.weight(1f),
                        )
                    }

                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            text = stringResource(Res.string.billeterie_desktop_overview_label),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            letterSpacing = 0.5.sp,
                        )
                        WideBilleterieStatsPanel(
                            permanentGuestCount = permanentGuestCount,
                            temporaryGuestCount = temporaryGuestCount,
                            volunteersOnList = volunteersOnList,
                            totalWithoutInvites = totalWithoutInvites,
                        )
                    }
                }

                if (announcementsSendEnabled) {
                    SendAnnouncementButton(
                        isPhone = false,
                        onClick = { viewModel.openSendAnnouncementDialog() },
                    )
                }
            }
        }
    }
}

@Composable
private fun WideBilleterieTopBar(
    viewModel: EventManagerViewModel,
    onBack: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colorScheme = MaterialTheme.colorScheme

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colorScheme.surface.copy(alpha = 0.98f),
        tonalElevation = 1.dp,
        shadowElevation = 2.dp,
        border = androidx.compose.foundation.BorderStroke(
            width = 1.dp,
            color = colorScheme.outlineVariant.copy(alpha = 0.35f),
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(Res.string.setup_back),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(Res.string.billeterie_desktop_back))
                }
                Spacer(Modifier.weight(1f))
                FirebaseOrgSwitcher(
                    viewModel = viewModel,
                    placement = FirebaseOrgSwitcherPlacement.TopBarBeforeSync,
                )
                KeepScreenAwakeToggle(KeepAwakeSpace.Billeterie)
                SyncStatusPill(viewModel = viewModel)
                IconButton(onClick = onOpenSettings) {
                    Icon(
                        Icons.Default.Settings,
                        contentDescription = stringResource(Res.string.settings_title),
                        // Match the phone TopAppBar action colour; the translucent surface copy
                        // above does not resolve a content colour, which left this icon black.
                        tint = colorScheme.onSurface,
                    )
                }
            }
            HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.28f))
        }
    }
}

@Composable
private fun WideBilleterieHeaderBanner(
    settingsManager: SettingsManager,
    showClock: Boolean,
    permanentGuestCount: Int? = null,
    temporaryGuestCount: Int? = null,
    volunteersOnList: Int? = null,
    totalWithoutInvites: Int? = null,
) {
    val colorScheme = MaterialTheme.colorScheme
    val fontFamily = rememberWideBilleterieFontFamily()
    val shape = RoundedCornerShape(24.dp)
    val showInlineStats = permanentGuestCount != null &&
        temporaryGuestCount != null &&
        volunteersOnList != null &&
        totalWithoutInvites != null

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = colorScheme.surface.copy(alpha = 0.92f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            colorScheme.outlineVariant.copy(alpha = 0.36f),
        ),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 28.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(54.dp)
                        .clip(RoundedCornerShape(999.dp))
                        .background(colorScheme.primary.copy(alpha = 0.72f)),
                )
                Spacer(Modifier.width(18.dp))
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = stringResource(Res.string.ticket_check_mode),
                        style = MaterialTheme.typography.headlineLarge.copy(
                            fontFamily = fontFamily,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 0.2.sp,
                        ),
                        color = colorScheme.onSurface,
                    )
                    Text(
                        text = stringResource(Res.string.welcome_ticketing_hint),
                        style = MaterialTheme.typography.bodyLarge.copy(
                            fontFamily = fontFamily,
                            fontWeight = FontWeight.Medium,
                        ),
                        color = colorScheme.onSurfaceVariant,
                        modifier = Modifier.widthIn(max = 500.dp),
                    )
                }

                if (showClock) {
                    VerticalDivider(
                        modifier = Modifier
                            .padding(horizontal = 28.dp)
                            .height(58.dp),
                        color = colorScheme.outlineVariant.copy(alpha = 0.32f),
                    )
                    WideBilleterieClockReadout(settingsManager = settingsManager)
                }
            }

            if (showInlineStats) {
                HorizontalDivider(color = colorScheme.outlineVariant.copy(alpha = 0.28f))
                WideBilleterieInlineStatsRow(
                    permanentGuestCount = permanentGuestCount!!,
                    temporaryGuestCount = temporaryGuestCount!!,
                    volunteersOnList = volunteersOnList!!,
                    totalWithoutInvites = totalWithoutInvites!!,
                )
            }
        }
    }
}

@Composable
private fun rememberWideBilleterieFontFamily(): FontFamily {
    val light = Font(Res.font.nunito_light, weight = FontWeight.Light)
    val regular = Font(Res.font.nunito_regular, weight = FontWeight.Normal)
    val medium = Font(Res.font.nunito_medium, weight = FontWeight.Medium)
    val semiBold = Font(Res.font.nunito_semibold, weight = FontWeight.SemiBold)
    val bold = Font(Res.font.nunito_bold, weight = FontWeight.Bold)
    val extraBold = Font(Res.font.nunito_extrabold, weight = FontWeight.ExtraBold)
    val black = Font(Res.font.nunito_black, weight = FontWeight.Black)
    val lightItalic = Font(Res.font.nunito_light_italic, weight = FontWeight.Light, style = FontStyle.Italic)
    val regularItalic = Font(Res.font.nunito_regular_italic, weight = FontWeight.Normal, style = FontStyle.Italic)
    val extraLight = Font(Res.font.nunito_light, weight = FontWeight.ExtraLight)
    val extraLightItalic = Font(Res.font.nunito_light_italic, weight = FontWeight.ExtraLight, style = FontStyle.Italic)
    return remember(
        light, regular, medium, semiBold, bold, extraBold, black,
        lightItalic, regularItalic, extraLight, extraLightItalic,
    ) {
        FontFamily(
            light, regular, medium, semiBold, bold, extraBold, black,
            lightItalic, regularItalic, extraLight, extraLightItalic,
        )
    }
}

@Composable
private fun WideBilleterieClockReadout(settingsManager: SettingsManager) {
    val zone = GuestListDefaultZoneId
    val offsetHours = remember { settingsManager.getDateChangeOffsetHours() }
    val timeFormatPattern = settingsManager.getTimeFormat()
    val dateFormatPattern = settingsManager.getDateFormat()
    val effectiveToday = rememberGuestListEffectiveToday(zone = zone, offsetHours = offsetHours)
    var now by remember { mutableStateOf(ZonedDateTime.now(zone)) }

    LaunchedEffect(Unit) {
        while (true) {
            now = ZonedDateTime.now(zone)
            delay(1_000L)
        }
    }

    val locale = Locale.getDefault()
    val timeFormatter = remember(timeFormatPattern, locale) {
        DateTimeFormatter.ofPattern(timeFormatPattern, locale)
    }
    val dateFormatter = remember(dateFormatPattern, locale) {
        DateTimeFormatter.ofPattern(dateFormatPattern, locale)
    }
    val effectiveNow = remember(now, offsetHours) {
        now.toLocalDateTime().minusHours(offsetHours.toLong())
    }
    val effectiveTimeText = effectiveNow.format(timeFormatter)
    val offsetValueText = when (offsetHours) {
        0 -> stringResource(Res.string.dashboard_date_offset_zero)
        else -> {
            val signedHours = if (offsetHours > 0) "+$offsetHours" else offsetHours.toString()
            stringResource(Res.string.dashboard_date_offset_hours, signedHours)
        }
    }
    val calculationTimeInfo = stringResource(
        Res.string.dashboard_calculation_time_info,
        effectiveTimeText,
        offsetValueText,
    )

    val colorScheme = MaterialTheme.colorScheme
    val fontFamily = rememberWideBilleterieFontFamily()

    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.widthIn(min = 168.dp, max = 260.dp),
    ) {
        Text(
            text = now.format(timeFormatter),
            style = MaterialTheme.typography.displaySmall.copy(
                fontFamily = fontFamily,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                lineHeight = 40.sp,
            ),
            color = colorScheme.onSurface,
        )
        Text(
            text = effectiveToday.format(dateFormatter),
            style = MaterialTheme.typography.titleMedium.copy(
                fontFamily = fontFamily,
                fontWeight = FontWeight.SemiBold,
            ),
            color = colorScheme.primary,
        )
        Text(
            text = calculationTimeInfo,
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = fontFamily,
                fontWeight = FontWeight.Medium,
                lineHeight = 14.sp,
            ),
            color = colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
            textAlign = TextAlign.End,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val WideBilleterieActionCardHeight = 116.dp
private val WideBilleterieActionCardCorner = 24.dp
private val WideBilleterieActionIconWell = 56.dp
private val WideBilleterieActionIconSize = 28.dp
private val WideBilleterieActionHorizontalPadding = 28.dp
private val WideBilleterieActionItemSpacing = 20.dp

@Composable
private fun WideBilleterieActionCardContent(
    title: String,
    subtitle: String,
    icon: ImageVector,
    titleColor: Color,
    subtitleColor: Color,
    iconTint: Color,
    iconWellBackground: Color,
    trailingContent: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(WideBilleterieActionItemSpacing),
    ) {
        Box(
            modifier = Modifier
                .size(WideBilleterieActionIconWell)
                .clip(CircleShape)
                .background(iconWellBackground),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = iconTint,
                modifier = Modifier.size(WideBilleterieActionIconSize),
            )
        }
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = subtitleColor,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        trailingContent?.invoke()
    }
}

@Composable
private fun WideBilleterieScannerHero(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    hoverEnabled: Boolean = false,
) {
    val colorScheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isHoveredRaw by interactionSource.collectIsHoveredAsState()
    val isHovered = hoverEnabled && isHoveredRaw
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = when {
            isPressed -> 0.985f
            isHovered -> 1.008f
            else -> 1f
        },
        animationSpec = tween(150),
        label = "scannerHeroScale",
    )
    val shape = RoundedCornerShape(WideBilleterieActionCardCorner)

    val bg = if (isHovered) colorScheme.primary else colorScheme.primaryContainer
    val fg = if (isHovered) colorScheme.onPrimary else colorScheme.onPrimaryContainer
    val muted = if (isHovered) colorScheme.onPrimary.copy(alpha = 0.86f) else colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
    val iconWell = if (isHovered) colorScheme.onPrimary.copy(alpha = 0.16f) else colorScheme.primary.copy(alpha = 0.14f)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(WideBilleterieActionCardHeight)
            .graphicsLayer {
                if (scale != 1f) {
                    scaleX = scale
                    scaleY = scale
                }
            }
            .then(
                if (isHovered) {
                    Modifier.shadow(
                        elevation = 12.dp,
                        shape = shape,
                        clip = false,
                        ambientColor = colorScheme.primary.copy(alpha = 0.14f),
                        spotColor = colorScheme.primary.copy(alpha = 0.2f),
                    )
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(bg)
            .border(1.dp, colorScheme.primary.copy(alpha = if (isHovered) 0f else 0.2f), shape)
            .then(if (hoverEnabled) Modifier.hoverable(interactionSource) else Modifier)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
            .padding(horizontal = WideBilleterieActionHorizontalPadding),
        contentAlignment = Alignment.CenterStart,
    ) {
        WideBilleterieActionCardContent(
            title = title,
            subtitle = subtitle,
            icon = Icons.Default.QrCodeScanner,
            titleColor = fg,
            subtitleColor = muted,
            iconTint = if (isHovered) fg else colorScheme.primary,
            iconWellBackground = iconWell,
            trailingContent = {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = fg.copy(alpha = 0.75f),
                    modifier = Modifier.size(24.dp),
                )
            },
        )
    }
}

@Composable
private fun WideBilleterieSecondaryAction(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    prominent: Boolean = false,
    hoverEnabled: Boolean = false,
) {
    val colorScheme = MaterialTheme.colorScheme
    val interactionSource = remember { MutableInteractionSource() }
    val isHoveredRaw by interactionSource.collectIsHoveredAsState()
    val isHovered = hoverEnabled && isHoveredRaw

    if (prominent) {
        val shape = RoundedCornerShape(WideBilleterieActionCardCorner)
        Box(
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .defaultMinSize(minHeight = WideBilleterieActionCardHeight)
                .clip(shape)
                .background(
                    if (isHovered) colorScheme.surfaceContainerHigh else colorScheme.surface.copy(alpha = 0.94f),
                )
                .border(
                    1.dp,
                    if (isHovered) colorScheme.outlineVariant.copy(alpha = 0.5f)
                    else colorScheme.outlineVariant.copy(alpha = 0.38f),
                    shape,
                )
                .then(if (hoverEnabled) Modifier.hoverable(interactionSource) else Modifier)
                .clickable(interactionSource = interactionSource, indication = null, onClick = onClick)
                .padding(horizontal = WideBilleterieActionHorizontalPadding),
            contentAlignment = Alignment.CenterStart,
        ) {
            WideBilleterieActionCardContent(
                title = title,
                subtitle = subtitle,
                icon = icon,
                titleColor = colorScheme.onSurface,
                subtitleColor = colorScheme.onSurfaceVariant,
                iconTint = colorScheme.primary,
                iconWellBackground = colorScheme.primary.copy(alpha = 0.14f),
            )
        }
        return
    }

    val shape = RoundedCornerShape(if (compact) 18.dp else 20.dp)
    val cardHeight = if (compact) 84.dp else 92.dp
    val iconWellSize = if (compact) 38.dp else 42.dp
    val iconSize = if (compact) 20.dp else 22.dp
    val horizontalPadding = if (compact) 16.dp else 20.dp
    val itemSpacing = if (compact) 12.dp else 14.dp

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .height(cardHeight)
            .then(if (hoverEnabled) Modifier.hoverable(interactionSource) else Modifier)
            .clickable(interactionSource = interactionSource, indication = null, onClick = onClick),
        shape = shape,
        color = if (isHovered) colorScheme.surfaceContainerHigh else colorScheme.surface.copy(alpha = 0.94f),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            if (isHovered) colorScheme.primary.copy(alpha = 0.28f) else colorScheme.outlineVariant.copy(alpha = 0.4f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(itemSpacing),
        ) {
            Box(
                modifier = Modifier
                    .size(iconWellSize)
                    .clip(CircleShape)
                    .background(colorScheme.primary.copy(alpha = 0.1f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = colorScheme.primary, modifier = Modifier.size(iconSize))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!compact) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun WideBilleterieInlineStatsRow(
    permanentGuestCount: Int,
    temporaryGuestCount: Int,
    volunteersOnList: Int,
    totalWithoutInvites: Int,
) {
    val colorScheme = MaterialTheme.colorScheme

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WideBilleterieStatCell(
            value = permanentGuestCount.toString(),
            label = stringResource(Res.string.billeterie_stat_permanent_guests),
            icon = Icons.Default.Person,
            compact = true,
            modifier = Modifier.weight(1f),
        )
        VerticalDivider(Modifier.height(44.dp), color = colorScheme.outlineVariant.copy(alpha = 0.3f))
        WideBilleterieStatCell(
            value = temporaryGuestCount.toString(),
            label = stringResource(Res.string.billeterie_stat_temporary_guests_today),
            icon = Icons.Default.Event,
            compact = true,
            modifier = Modifier.weight(1f),
        )
        VerticalDivider(Modifier.height(44.dp), color = colorScheme.outlineVariant.copy(alpha = 0.3f))
        WideBilleterieStatCell(
            value = volunteersOnList.toString(),
            label = stringResource(Res.string.billeterie_stat_volunteers_on_list),
            icon = Icons.Default.Group,
            compact = true,
            modifier = Modifier.weight(1f),
        )
        VerticalDivider(Modifier.height(44.dp), color = colorScheme.outlineVariant.copy(alpha = 0.3f))
        WideBilleterieStatCell(
            value = totalWithoutInvites.toString(),
            label = stringResource(Res.string.billeterie_stat_total_without_invites),
            icon = Icons.Default.Star,
            emphasized = true,
            compact = true,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun WideBilleterieStatsPanel(
    permanentGuestCount: Int,
    temporaryGuestCount: Int,
    volunteersOnList: Int,
    totalWithoutInvites: Int,
) {
    val colorScheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(20.dp)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = shape,
        color = colorScheme.surface.copy(alpha = 0.94f),
        border = androidx.compose.foundation.BorderStroke(1.dp, colorScheme.outlineVariant.copy(alpha = 0.38f)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            WideBilleterieStatCell(
                value = permanentGuestCount.toString(),
                label = stringResource(Res.string.billeterie_stat_permanent_guests),
                icon = Icons.Default.Person,
                modifier = Modifier.weight(1f),
            )
            VerticalDivider(Modifier.height(60.dp), color = colorScheme.outlineVariant.copy(alpha = 0.3f))
            WideBilleterieStatCell(
                value = temporaryGuestCount.toString(),
                label = stringResource(Res.string.billeterie_stat_temporary_guests_today),
                icon = Icons.Default.Event,
                modifier = Modifier.weight(1f),
            )
            VerticalDivider(Modifier.height(60.dp), color = colorScheme.outlineVariant.copy(alpha = 0.3f))
            WideBilleterieStatCell(
                value = volunteersOnList.toString(),
                label = stringResource(Res.string.billeterie_stat_volunteers_on_list),
                icon = Icons.Default.Group,
                modifier = Modifier.weight(1f),
            )
            VerticalDivider(Modifier.height(60.dp), color = colorScheme.outlineVariant.copy(alpha = 0.3f))
            WideBilleterieStatCell(
                value = totalWithoutInvites.toString(),
                label = stringResource(Res.string.billeterie_stat_total_without_invites),
                icon = Icons.Default.Star,
                emphasized = true,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun WideBilleterieStatCell(
    value: String,
    label: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    compact: Boolean = false,
) {
    val colorScheme = MaterialTheme.colorScheme
    val horizontalPadding = if (compact) 8.dp else 18.dp
    val verticalPadding = if (compact) 12.dp else 20.dp
    val iconSize = if (compact) 18.dp else 22.dp
    val valueStyle = when {
        compact && emphasized -> MaterialTheme.typography.titleLarge
        compact -> MaterialTheme.typography.titleMedium
        emphasized -> MaterialTheme.typography.headlineMedium
        else -> MaterialTheme.typography.headlineSmall
    }
    val labelStyle = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall
    val labelLineHeight = if (compact) 14.sp else 18.sp

    Column(
        modifier = modifier.padding(horizontal = horizontalPadding, vertical = verticalPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = colorScheme.primary.copy(alpha = if (emphasized) 1f else 0.75f),
                modifier = Modifier.size(iconSize),
            )
            Text(
                text = value,
                style = valueStyle,
                fontWeight = FontWeight.Bold,
                color = if (emphasized) colorScheme.primary else colorScheme.onSurface,
            )
        }
        Text(
            text = label,
            style = labelStyle,
            color = colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = if (compact) 2 else 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = labelLineHeight,
            fontWeight = FontWeight.Medium,
        )
    }
}
