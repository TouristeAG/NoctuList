package com.eventmanager.app.ui.components

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.platform.isDesktop
import com.eventmanager.app.ui.utils.getDialogHeightFraction
import com.eventmanager.app.ui.utils.getDialogWidthFraction
import com.eventmanager.app.ui.utils.getScreenHeightDp
import com.eventmanager.app.ui.utils.getScreenWidthDp
import com.eventmanager.app.ui.utils.getTabletConstrainedDialogMaxHeight
import com.eventmanager.app.ui.utils.getTabletConstrainedDialogMaxWidth
import com.eventmanager.app.ui.utils.isTablet

/**
 * Sizing profiles matching the legacy NoctuList Android app.
 */
enum class FractionalDialogProfile {
    /** Export options: portrait 90%×90%, landscape 60%×80% */
    Export,
    /** Preview / tall content: portrait 90%×95%, landscape 70%×85% */
    Preview,
    /** Help / announcements: 92%×88% */
    Compact,
    /** Simple card dialogs: 92% width, up to 90% height */
    Card,
}

fun phoneFractionDialogProperties(
    dismissOnBackPress: Boolean = true,
    dismissOnClickOutside: Boolean = true,
): DialogProperties = DialogProperties(
    usePlatformDefaultWidth = false,
    dismissOnBackPress = dismissOnBackPress,
    dismissOnClickOutside = dismissOnClickOutside,
)

fun formDialogProperties(isTabletDevice: Boolean, isDesktopPlatform: Boolean): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = !isTabletDevice && !isDesktopPlatform)

fun fullScreenDialogProperties(): DialogProperties =
    DialogProperties(usePlatformDefaultWidth = false)

/**
 * Computes max dialog width/height from available space.
 * Mirrors [StatsGraphsPanel] export/preview dialogs in the legacy app.
 */
@Composable
fun DialogFractionSizer(
    profile: FractionalDialogProfile,
    modifier: Modifier = Modifier.fillMaxSize(),
    contentAlignment: Alignment = Alignment.Center,
    desktopMaxWidth: Dp = 440.dp,
    content: @Composable BoxScope.(maxDialogWidth: Dp, maxDialogHeight: Dp) -> Unit,
) {
    val isDesktopPlatform = LocalPlatformContext.current.isDesktop
    val isTabletDevice = isTablet()
    val screenWidth = getScreenWidthDp()
    val screenHeight = getScreenHeightDp()

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = contentAlignment,
    ) {
        // Dialogs with usePlatformDefaultWidth=false can report unbounded/huge
        // constraints. Never let those leak into fillMaxWidth() children.
        val availableWidth = finiteDialogConstraint(maxWidth, screenWidth)
        val availableHeight = finiteDialogConstraint(maxHeight, screenHeight)
        val isLandscape = availableWidth > availableHeight
        val (widthFrac, heightFrac) = when (profile) {
            FractionalDialogProfile.Export ->
                if (isLandscape) 0.6f to 0.8f else 0.9f to 0.9f
            FractionalDialogProfile.Preview ->
                if (isLandscape) 0.7f to 0.85f else 0.9f to 0.95f
            FractionalDialogProfile.Compact -> 0.92f to 0.88f
            FractionalDialogProfile.Card -> 0.92f to 0.9f
        }

        val maxDialogWidth = when {
            isDesktopPlatform -> availableWidth.coerceAtMost(desktopMaxWidth)
            isTabletDevice -> minOf(availableWidth * getDialogWidthFraction(), getTabletConstrainedDialogMaxWidth())
            else -> availableWidth * widthFrac
        }

        val maxDialogHeight = when {
            isDesktopPlatform -> availableHeight
            isTabletDevice -> minOf(availableHeight * getDialogHeightFraction(), getTabletConstrainedDialogMaxHeight())
            else -> availableHeight * heightFrac
        }

        content(maxDialogWidth, maxDialogHeight)
    }
}

internal fun finiteDialogConstraint(constraint: Dp, screenSize: Dp): Dp {
    val value = constraint.value
    return when {
        !value.isFinite() || constraint <= 0.dp -> screenSize
        else -> constraint.coerceAtMost(screenSize)
    }
}

/**
 * Centered card dialog with fraction-based sizing on phones (legacy NoctuList behavior).
 */
@Composable
fun FractionalDialogShell(
    profile: FractionalDialogProfile = FractionalDialogProfile.Export,
    desktopMaxWidth: Dp = 440.dp,
    outerPadding: Dp = 16.dp,
    content: @Composable ColumnScope.(Modifier) -> Unit,
) {
    DialogFractionSizer(
        profile = profile,
        desktopMaxWidth = desktopMaxWidth,
    ) { maxDialogWidth, maxDialogHeight ->
        Card(
            modifier = Modifier
                .widthIn(max = maxDialogWidth)
                .heightIn(max = maxDialogHeight)
                .padding(outerPadding),
            shape = RoundedCornerShape(20.dp),
        ) {
            content(Modifier.fillMaxWidth())
        }
    }
}
