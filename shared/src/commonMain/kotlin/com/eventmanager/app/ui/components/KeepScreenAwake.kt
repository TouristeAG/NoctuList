package com.eventmanager.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.data.sync.settingsManagerFor
import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.platform.isDesktop
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.keep_screen_on_billeterie_description
import com.eventmanager.app.resources.keep_screen_on_off_tooltip
import com.eventmanager.app.resources.keep_screen_on_on_tooltip
import com.eventmanager.app.resources.keep_screen_on_pos_description
import com.eventmanager.app.resources.keep_screen_on_title
import com.eventmanager.app.ui.utils.isTablet
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.jetbrains.compose.resources.stringResource

/**
 * Spaces that may keep the screen awake. Admin is deliberately absent: it must be able to lock
 * so the data it shows stays protected when the device is left alone.
 */
enum class KeepAwakeSpace { Pos, Billeterie }

/**
 * Single source of truth for the keep-awake switches, shared by the header toggles (desktop and
 * tablet), the phone settings rows and the effect that actually holds the platform wake lock.
 */
object KeepScreenAwakePreferences {
    private var loaded = false
    private val pos = MutableStateFlow(false)
    private val billeterie = MutableStateFlow(false)

    private fun ensureLoaded(settingsManager: SettingsManager) {
        if (loaded) return
        pos.value = settingsManager.isKeepScreenOnPosEnabled()
        billeterie.value = settingsManager.isKeepScreenOnBilleterieEnabled()
        loaded = true
    }

    fun flow(space: KeepAwakeSpace, settingsManager: SettingsManager): StateFlow<Boolean> {
        ensureLoaded(settingsManager)
        return when (space) {
            KeepAwakeSpace.Pos -> pos
            KeepAwakeSpace.Billeterie -> billeterie
        }.asStateFlow()
    }

    fun set(space: KeepAwakeSpace, enabled: Boolean, settingsManager: SettingsManager) {
        ensureLoaded(settingsManager)
        when (space) {
            KeepAwakeSpace.Pos -> {
                settingsManager.setKeepScreenOnPosEnabled(enabled)
                pos.value = enabled
            }
            KeepAwakeSpace.Billeterie -> {
                settingsManager.setKeepScreenOnBilleterieEnabled(enabled)
                billeterie.value = enabled
            }
        }
    }
}

@Composable
fun rememberKeepScreenAwakeEnabled(space: KeepAwakeSpace): Boolean {
    val settingsManager = settingsManagerFor(LocalPlatformContext.current)
    val enabled by KeepScreenAwakePreferences.flow(space, settingsManager).collectAsState()
    return enabled
}

/**
 * Holds the platform wake lock while [active] and the user enabled it for [space].
 * Place once at the root of the space so the lock is released as soon as the space is left.
 */
@Composable
fun KeepScreenAwakeForSpace(space: KeepAwakeSpace, active: Boolean = true) {
    val enabled = rememberKeepScreenAwakeEnabled(space)
    KeepScreenAwakeEffect(enabled = active && enabled)
}

/** Header toggles exist on desktop and tablets only; phones use the settings row instead. */
@Composable
fun isKeepScreenAwakeToggleInHeader(): Boolean =
    LocalPlatformContext.current.isDesktop || isTablet()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeepScreenAwakeToggle(
    space: KeepAwakeSpace,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.Unspecified,
) {
    if (!isKeepScreenAwakeToggleInHeader()) return
    val settingsManager = settingsManagerFor(LocalPlatformContext.current)
    val enabled = rememberKeepScreenAwakeEnabled(space)
    KeepScreenAwakeToggleButton(
        enabled = enabled,
        onToggle = { KeepScreenAwakePreferences.set(space, it, settingsManager) },
        modifier = modifier,
        contentColor = contentColor,
    )
}

/** Stateless look of [KeepScreenAwakeToggle]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun KeepScreenAwakeToggleButton(
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    contentColor: Color = Color.Unspecified,
) {
    val tooltip = stringResource(
        if (enabled) Res.string.keep_screen_on_on_tooltip else Res.string.keep_screen_on_off_tooltip,
    )
    val idleColor = if (contentColor == Color.Unspecified) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        contentColor
    }
    // Off: a faint icon with no background, so the header stays calm.
    // On: the sync pill's own surface with a primary icon, so the two read as one group.
    val background by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent,
        animationSpec = tween(180),
        label = "keep_awake_bg",
    )
    val iconColor by animateColorAsState(
        targetValue = if (enabled) MaterialTheme.colorScheme.primary else idleColor.copy(alpha = 0.6f),
        animationSpec = tween(180),
        label = "keep_awake_icon",
    )
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.92f else 1f,
        animationSpec = tween(100),
        label = "keep_awake_scale",
    )
    val description = stringResource(Res.string.keep_screen_on_title)

    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tooltip) } },
        state = rememberTooltipState(),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .padding(vertical = 4.dp)
                .size(32.dp)
                .scale(scale)
                .clip(CircleShape)
                .background(background)
                .toggleable(
                    value = enabled,
                    interactionSource = interactionSource,
                    indication = ripple(bounded = true),
                    role = Role.Switch,
                    onValueChange = onToggle,
                )
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (enabled) Icons.Filled.LightMode else Icons.Outlined.LightMode,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** Phone-only settings row; desktop and tablets use [KeepScreenAwakeToggle] in the header. */
@Composable
fun KeepScreenAwakeSettingRow(space: KeepAwakeSpace, modifier: Modifier = Modifier) {
    if (isKeepScreenAwakeToggleInHeader()) return
    val settingsManager = settingsManagerFor(LocalPlatformContext.current)
    val enabled = rememberKeepScreenAwakeEnabled(space)
    KeepScreenAwakeSettingRowContent(
        space = space,
        enabled = enabled,
        onToggle = { KeepScreenAwakePreferences.set(space, it, settingsManager) },
        modifier = modifier,
    )
}

/** Stateless look of [KeepScreenAwakeSettingRow]. */
@Composable
internal fun KeepScreenAwakeSettingRowContent(
    space: KeepAwakeSpace,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(Res.string.keep_screen_on_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = stringResource(
                    when (space) {
                        KeepAwakeSpace.Pos -> Res.string.keep_screen_on_pos_description
                        KeepAwakeSpace.Billeterie -> Res.string.keep_screen_on_billeterie_description
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onToggle,
        )
    }
}

/** Keeps the display on and blocks the idle lock while [enabled] is true. */
@Composable
expect fun KeepScreenAwakeEffect(enabled: Boolean)
