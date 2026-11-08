package com.eventmanager.app.ui.utils

import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import org.jetbrains.compose.resources.stringResource

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Venue

enum class ScreenSize {
    COMPACT,    // Phone portrait
    MEDIUM,     // Phone landscape or small tablet
    EXPANDED    // Tablet
}

@Composable
private fun getScreenWidthDpLocal(): Dp = getScreenWidthDp()

@Composable
private fun getScreenHeightDpLocal(): Dp = getScreenHeightDp()

@Composable
fun getScreenSize(): ScreenSize {
    val screenWidth = getScreenWidthDpLocal()
    val screenHeight = getScreenHeightDpLocal()
    val smallestWidth = minOf(screenWidth, screenHeight)
    
    return when {
        smallestWidth < 480.dp -> ScreenSize.COMPACT  // Phones
        smallestWidth < 720.dp -> ScreenSize.MEDIUM  // Small tablets
        else -> ScreenSize.EXPANDED                    // Large tablets
    }
}

@Composable
fun isPhonePortrait(): Boolean {
    val screenWidth = getScreenWidthDp()
    val screenHeight = getScreenHeightDp()
    val smallestWidth = minOf(screenWidth, screenHeight)
    
    // Only consider it phone portrait if it's a small screen AND in portrait orientation
    return screenHeight > screenWidth && smallestWidth < 480.dp
}

@Composable
fun getResponsivePadding(): Dp {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> 8.dp
        ScreenSize.MEDIUM -> 16.dp
        ScreenSize.EXPANDED -> 32.dp
    }
}

@Composable
fun getResponsiveSpacing(): Dp {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> 8.dp
        ScreenSize.MEDIUM -> 16.dp
        ScreenSize.EXPANDED -> 24.dp
    }
}

@Composable
fun getResponsiveCardElevation(): Dp {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> 2.dp
        ScreenSize.MEDIUM -> 4.dp
        ScreenSize.EXPANDED -> 8.dp
    }
}

@Composable
fun getResponsiveIconSize(): Dp {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> 16.dp
        ScreenSize.MEDIUM -> 24.dp
        ScreenSize.EXPANDED -> 32.dp
    }
}

@Composable
fun getResponsiveAvatarSize(): Dp {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> 32.dp
        ScreenSize.MEDIUM -> 48.dp
        ScreenSize.EXPANDED -> 64.dp
    }
}

@Composable
fun getResponsiveButtonHeight(): Dp {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> 36.dp
        ScreenSize.MEDIUM -> 48.dp
        ScreenSize.EXPANDED -> 56.dp
    }
}

@Composable
fun isCompactScreen(): Boolean {
    return getScreenSize() == ScreenSize.COMPACT
}

@Composable
fun isMediumScreen(): Boolean {
    return getScreenSize() == ScreenSize.MEDIUM
}

@Composable
fun isTablet(): Boolean {
    val screenWidth = getScreenWidthDp()
    val screenHeight = getScreenHeightDp()
    val smallestWidth = minOf(screenWidth, screenHeight)
    
    // Consider devices with smallest width >= 480dp as tablets
    return smallestWidth >= 480.dp
}

@Composable
fun isLargeTablet(): Boolean {
    val screenWidth = getScreenWidthDp()
    val screenHeight = getScreenHeightDp()
    val smallestWidth = minOf(screenWidth, screenHeight)
    
    // Consider devices with smallest width >= 720dp as large tablets
    return smallestWidth >= 720.dp
}

@Composable
fun getResponsiveTypography(): TextStyle {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> MaterialTheme.typography.headlineSmall.copy(fontSize = 20.sp)
        ScreenSize.MEDIUM -> MaterialTheme.typography.headlineMedium.copy(fontSize = 28.sp)
        ScreenSize.EXPANDED -> MaterialTheme.typography.headlineLarge.copy(fontSize = 36.sp)
    }
}

@Composable
fun getResponsiveTitleTypography(): TextStyle {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp)
        ScreenSize.MEDIUM -> MaterialTheme.typography.titleLarge.copy(fontSize = 24.sp)
        ScreenSize.EXPANDED -> MaterialTheme.typography.titleLarge.copy(fontSize = 28.sp)
    }
}

@Composable
fun getResponsiveBodyTypography(): TextStyle {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
        ScreenSize.MEDIUM -> MaterialTheme.typography.bodyLarge.copy(fontSize = 18.sp)
        ScreenSize.EXPANDED -> MaterialTheme.typography.bodyLarge.copy(fontSize = 20.sp)
    }
}

@Composable
fun getResponsiveCardPadding(): Dp {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> 12.dp
        ScreenSize.MEDIUM -> 20.dp
        ScreenSize.EXPANDED -> 32.dp
    }
}

@Composable
fun getResponsiveDialogMaxHeight(): Dp {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> 400.dp
        ScreenSize.MEDIUM -> 600.dp
        ScreenSize.EXPANDED -> 800.dp
    }
}

@Composable
fun getPhonePortraitDialogMaxHeight(): Dp {
    return if (isPhonePortrait()) 300.dp else getResponsiveDialogMaxHeight()
}

@Composable
fun getResponsiveButtonPadding(): Dp {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> 8.dp
        ScreenSize.MEDIUM -> 16.dp
        ScreenSize.EXPANDED -> 24.dp
    }
}

@Composable
fun getPhonePortraitPadding(): Dp {
    return if (isPhonePortrait()) 4.dp else getResponsivePadding()
}

@Composable
fun getPhonePortraitSpacing(): Dp {
    return if (isPhonePortrait()) 4.dp else getResponsiveSpacing()
}

@Composable
fun getPhonePortraitCardPadding(): Dp {
    return if (isPhonePortrait()) 8.dp else getResponsiveCardPadding()
}

@Composable
fun getPhonePortraitTypography(): TextStyle {
    return if (isPhonePortrait()) {
        MaterialTheme.typography.headlineSmall.copy(fontSize = 18.sp)
    } else {
        getResponsiveTypography()
    }
}

@Composable
fun getPhonePortraitBodyTypography(): TextStyle {
    return if (isPhonePortrait()) {
        MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
    } else {
        getResponsiveBodyTypography()
    }
}

@Composable
fun getResponsiveSmallTypography(): TextStyle {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp)
        ScreenSize.MEDIUM -> MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
        ScreenSize.EXPANDED -> MaterialTheme.typography.bodyLarge.copy(fontSize = 16.sp)
    }
}

@Composable
fun getResponsiveLargeTypography(): TextStyle {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> MaterialTheme.typography.headlineMedium.copy(fontSize = 24.sp)
        ScreenSize.MEDIUM -> MaterialTheme.typography.headlineLarge.copy(fontSize = 32.sp)
        ScreenSize.EXPANDED -> MaterialTheme.typography.headlineLarge.copy(fontSize = 40.sp)
    }
}

@Composable
fun getResponsiveListItemHeight(): Dp {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> 56.dp
        ScreenSize.MEDIUM -> 72.dp
        ScreenSize.EXPANDED -> 88.dp
    }
}

@Composable
fun getResponsiveMinTouchTarget(): Dp {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> 48.dp
        ScreenSize.MEDIUM -> 56.dp
        ScreenSize.EXPANDED -> 64.dp
    }
}

// ============================================================================
// TABLET DIALOG UTILITIES
// On tablets, dialogs may use most of the inner area but keep a generous fixed
// inset from each physical screen edge so windows never feel edge-to-edge.
// Phones are unchanged.
// ============================================================================

/**
 * Horizontal/vertical inset between dialog content and the physical screen edge on tablets.
 * Phones use 0.dp (callers apply their own padding where needed).
 */
@Composable
fun getTabletDialogScreenEdgeInset(): Dp {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> 0.dp
        ScreenSize.MEDIUM -> 48.dp
        ScreenSize.EXPANDED -> 72.dp
    }
}

/**
 * Inset between a full-screen guest/volunteer profile window and the physical screen edge.
 * Tablets keep [getTabletDialogScreenEdgeInset]; phones get a small margin so the profile card
 * reads as a window (the panel still fills the remaining space, so nothing can overflow).
 */
@Composable
fun getProfileDialogScreenEdgeInset(): Dp {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> 12.dp
        else -> getTabletDialogScreenEdgeInset()
    }
}

/**
 * Maximum width for tablet dialogs that size themselves with [Modifier.widthIn] (e.g. QR sheets,
 * alert-style cards). Equals the screen width minus twice [getTabletDialogScreenEdgeInset].
 * On phones, returns a large value (effectively no constraint for those call sites).
 */
@Composable
fun getTabletConstrainedDialogMaxWidth(): Dp {
    val screenWidth = getScreenWidthDp()
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> 600.dp
        ScreenSize.MEDIUM, ScreenSize.EXPANDED -> {
            val inset = getTabletDialogScreenEdgeInset()
            (screenWidth - inset * 2).coerceAtLeast(280.dp)
        }
    }
}

/**
 * Maximum height for tablet dialogs using [Modifier.heightIn], same rule as max width.
 */
@Composable
fun getTabletConstrainedDialogMaxHeight(): Dp {
    val screenHeight = getScreenHeightDp()
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> 800.dp
        ScreenSize.MEDIUM, ScreenSize.EXPANDED -> {
            val inset = getTabletDialogScreenEdgeInset()
            (screenHeight - inset * 2).coerceAtLeast(280.dp)
        }
    }
}

/**
 * Returns a constrained QR code size for different screen sizes.
 * On phones, the QR code can be larger relative to screen.
 * On tablets, the QR code is limited to a reasonable size.
 */
@Composable
fun getTabletConstrainedQRCodeSize(): Dp {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> 280.dp
        ScreenSize.MEDIUM -> 300.dp
        ScreenSize.EXPANDED -> 340.dp
    }
}

/**
 * Returns the fraction of screen width that dialogs should use.
 * On phones, dialogs use most of the width.
 * On tablets, dialogs use a smaller fraction to avoid being too wide.
 */
@Composable
fun getDialogWidthFraction(): Float {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> 0.92f   // Phones: use most of the width
        ScreenSize.MEDIUM -> 0.55f    // Small tablets: use about half
        ScreenSize.EXPANDED -> 0.45f  // Large tablets: use less than half
    }
}

/**
 * Returns the fraction of screen height that dialogs should use.
 * On phones, dialogs can use more of the height.
 * On tablets, dialogs use a smaller fraction to avoid being too tall.
 */
@Composable
fun getDialogHeightFraction(): Float {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> 0.90f   // Phones: use most of the height
        ScreenSize.MEDIUM -> 0.65f    // Small tablets: use about 65%
        ScreenSize.EXPANDED -> 0.55f  // Large tablets: use about 55%
    }
}

/**
 * Returns constrained typography for tablet dialogs.
 * On phones, uses the standard phone typography.
 * On tablets, uses smaller font sizes to prevent oversized text.
 */
@Composable
fun getTabletConstrainedTitleTypography(): TextStyle {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> MaterialTheme.typography.titleLarge.copy(fontSize = 18.sp)
        ScreenSize.MEDIUM -> MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp)  // Same as phone
        ScreenSize.EXPANDED -> MaterialTheme.typography.titleMedium.copy(fontSize = 20.sp) // Slightly larger
    }
}

/**
 * Returns constrained body typography for tablet dialogs.
 * On phones, uses the standard phone typography.
 * On tablets, uses smaller font sizes to prevent oversized text.
 */
@Composable
fun getTabletConstrainedBodyTypography(): TextStyle {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)
        ScreenSize.MEDIUM -> MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp)  // Same as phone
        ScreenSize.EXPANDED -> MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp) // Slightly larger
    }
}

/**
 * Returns constrained padding for tablet dialogs.
 * On phones, uses standard padding.
 * On tablets, uses similar padding to phones to keep dialogs compact.
 */
@Composable
fun getTabletConstrainedDialogPadding(): Dp {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> 16.dp
        ScreenSize.MEDIUM, ScreenSize.EXPANDED -> 0.dp // Outer max size already includes screen inset
    }
}

/**
 * Returns constrained spacing for tablet dialogs.
 * On phones, uses standard spacing.
 * On tablets, uses similar spacing to phones to keep dialogs compact.
 */
@Composable
fun getTabletConstrainedDialogSpacing(): Dp {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> 12.dp
        ScreenSize.MEDIUM -> 12.dp   // Same as phone
        ScreenSize.EXPANDED -> 14.dp // Slightly larger
    }
}

/**
 * Returns constrained button height for tablet dialogs.
 */
@Composable
fun getTabletConstrainedButtonHeight(): Dp {
    return when (getScreenSize()) {
        ScreenSize.COMPACT -> 48.dp
        ScreenSize.MEDIUM -> 48.dp   // Same as phone
        ScreenSize.EXPANDED -> 52.dp // Slightly larger
    }
}

/**
 * Data class representing a venue option for dropdowns
 */
data class VenueOption(
    val venue: Venue?,
    val displayName: String,
    val isAllOption: Boolean = false,
    val venueEntityId: Long? = null,  // Track the actual VenueEntity ID for proper selection
    val venueName: String? = null     // Store the actual venue name for better matching
)

/**
 * Generates venue options for dropdowns, including the "ALL" option
 * @param venues List of active venues from database
 * @return List of VenueOption with proper display names and translations
 */
@Composable
fun generateVenueOptions(venues: List<VenueEntity>): List<VenueOption> {
    val allOptionText = stringResource(Res.string.venue_all)

    // Memoized: the body is pure, so rebuilding the list on every recomposition was wasted work.
    // A stable list identity also keeps downstream remember() keys from invalidating.
    return remember(venues, allOptionText) {
        val activeVenues = venues.filter { it.isActive }

        val options = mutableListOf<VenueOption>()

        options.add(VenueOption(
            venue = Venue.BOTH,
            displayName = allOptionText,
            isAllOption = true
        ))

        // Add individual venue options
        activeVenues.forEach { venueEntity ->
            // Use the flexible venue mapping function
            val venueEnum = mapVenueNameToEnum(venueEntity.name, activeVenues)

            val displayName = venueEntity.name.replace("_", " ")

            options.add(VenueOption(
                venue = venueEnum,
                displayName = displayName,
                isAllOption = false,
                venueEntityId = venueEntity.id
            ))
        }

        options
    }
}

/**
 * Maps a venue entity name to a Venue enum value
 * This function handles the limitation of the fixed Venue enum by mapping
 * any venue name to one of the available enum values
 */
private fun mapVenueNameToEnum(venueName: String, venues: List<VenueEntity> = emptyList()): Venue {
    return when (venueName.trim().uppercase()) {
        "GROOVE" -> Venue.GROOVE
        "LE_TERREAU", "LE TERREAU" -> Venue.LE_TERREAU
        else -> {
            // For custom venues, map them to available enums based on their position in the list
            // This ensures consistent, position-based mapping
            if (venues.isNotEmpty()) {
                val activeVenues = venues.filter { it.isActive }
                val venuePosition = activeVenues.indexOfFirst { it.name.uppercase() == venueName.trim().uppercase() }
                if (venuePosition >= 0) {
                    val enumValues = listOf(Venue.GROOVE, Venue.LE_TERREAU)
                    val index = venuePosition % enumValues.size
                    enumValues[index]
                } else {
                    // Fallback to hash if position not found
                    val hash = venueName.hashCode()
                    val enumValues = listOf(Venue.GROOVE, Venue.LE_TERREAU)
                    val index = kotlin.math.abs(hash) % enumValues.size
                    enumValues[index]
                }
            } else {
                // No venues provided, use hash-based fallback
                val hash = venueName.hashCode()
                val enumValues = listOf(Venue.GROOVE, Venue.LE_TERREAU)
                val index = kotlin.math.abs(hash) % enumValues.size
                enumValues[index]
            }
        }
    }
}

/**
 * Finds the venue entity that maps to the given Venue enum
 * This is needed for reverse lookup when displaying venue names
 */
private fun findVenueEntityForEnum(venue: Venue, venues: List<VenueEntity>): VenueEntity? {
    val activeVenues = venues.filter { it.isActive }
    
    // First try exact matches
    val exactMatch = activeVenues.find { entity ->
        when (venue) {
            Venue.GROOVE -> entity.name.uppercase() == "GROOVE"
            Venue.LE_TERREAU -> entity.name.uppercase() == "LE_TERREAU"
            Venue.BOTH -> false // BOTH is not a real venue entity
        }
    }
    
    if (exactMatch != null) return exactMatch
    
    // If no exact match, find venues that map to this enum
    return activeVenues.find { entity ->
        mapVenueNameToEnum(entity.name) == venue
    }
}

/**
 * Gets the display name for a venue
 * @param selectedVenue The currently selected venue enum
 * @param venues List of active venues from database
 * @return Display name for the venue (translated)
 */
@Composable
fun getVenueDisplayName(selectedVenue: Venue?, venues: List<VenueEntity>): String {
    val platformContext = LocalPlatformContext.current
    
    // Handle null case - return empty string for dropdowns
    if (selectedVenue == null) {
        return ""
    }
    
    if (selectedVenue == Venue.BOTH) {
        return stringResource(Res.string.venue_all)
    }
    
    // Handle individual venues using reverse mapping
    val venueEntity = findVenueEntityForEnum(selectedVenue, venues)
    
    val result = venueEntity?.name?.replace("_", " ") ?: selectedVenue.name.replace("_", " ")
    
    return result
}

/**
 * Gets the display name for a venue string value
 * @param venueName The venue name to display (e.g., "BOTH" or "GROOVE")
 * @param venues List of active venues from database
 * @return Display name for the venue (translated if ALL)
 */
@Composable
fun getVenueDisplayString(venueName: String?, venues: List<VenueEntity>): String {
    val platformContext = LocalPlatformContext.current
    if (venueName == null) return ""
    
    return if (venueName == "BOTH") {
        stringResource(Res.string.venue_all)
    } else {
        venueName
    }
}

/**
 * Generates venue filter options for the guest list UI
 * @param venues List of active venues from database
 * @return List of venue filter options with proper display names
 */
@Composable
fun generateVenueFilterOptions(venues: List<VenueEntity>): List<VenueOption> {
    val allOptionText = stringResource(Res.string.venue_all)

    // Memoized: the body is pure, so rebuilding the list on every recomposition was wasted work.
    // A stable list identity also keeps downstream remember() keys from invalidating.
    return remember(venues, allOptionText) {
        val activeVenues = venues.filter { it.isActive }

        val options = mutableListOf<VenueOption>()

        options.add(VenueOption(
            venue = Venue.BOTH,
            displayName = allOptionText,
            isAllOption = true
        ))

        // Add individual venue options
        activeVenues.forEach { venueEntity ->
            // Use the flexible venue mapping function
            val venueEnum = mapVenueNameToEnum(venueEntity.name, venues)

            options.add(VenueOption(
                venue = venueEnum,
                displayName = venueEntity.name.replace("_", " "),
                isAllOption = false,
                venueEntityId = venueEntity.id,
                venueName = venueEntity.name
            ))
        }

        options
    }
}

/**
 * Checks if a guest matches the selected venue filter
 * @param guest The guest to check
 * @param selectedVenue The selected venue filter (null means no filter)
 * @param venues List of active venues from database
 * @return True if the guest matches the venue filter
 */
fun matchesVenueFilter(guest: Guest, selectedVenue: Venue?, venues: List<VenueEntity>): Boolean {
    // If no venue filter is selected, show all guests
    if (selectedVenue == null) return true
    
    return when (selectedVenue) {
        Venue.BOTH -> {
            // Show only guests that are specifically marked as "Both"
            guest.venueName == "BOTH"
        }
        else -> {
            // Find the venue name for this enum
            val selectedVenueName = venues.find { entity ->
                when (selectedVenue) {
                    Venue.GROOVE -> entity.name.uppercase() == "GROOVE"
                    Venue.LE_TERREAU -> entity.name.uppercase() == "LE_TERREAU"
                    Venue.BOTH -> false
                }
            }?.name
            selectedVenueName != null && (guest.venueName == selectedVenueName || guest.venueName == "BOTH")
        }
    }
}
