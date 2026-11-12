package com.eventmanager.app.ui.components.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.add
import com.eventmanager.app.resources.cancel
import com.eventmanager.app.resources.close
import com.eventmanager.app.resources.firebase_email_domains_label
import com.eventmanager.app.resources.guest_form_link_copied
import com.eventmanager.app.resources.portal_password_copy
import com.eventmanager.app.resources.remove
import com.eventmanager.app.resources.settings_help_cd
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Settings UI kit for the sync pages: calm surfaces, no decorative icons.
 * Icons appear only for actions (help, copy, close, chevron).
 */
object SettingsKitDefaults {
    /** Horizontal padding of a row inside a block surface. Use it for free content placed in a block. */
    val RowHorizontalPadding = 16.dp

    /** Vertical padding of a row inside a block surface. */
    val RowVerticalPadding = 14.dp

    /** Extra start indent of the sub-options revealed by [SettingsSwitchRow]. */
    val SubOptionIndent = 16.dp

    val BlockShape = RoundedCornerShape(16.dp)
    val BlockSpacing = 28.dp
    val PageMaxWidth = 720.dp
    /** Space under the sub-options revealed by [SettingsSwitchRow], before the next divider. */
    val SubOptionBottomPadding = 6.dp

    internal val RowMinHeight = 56.dp
    internal const val DisabledAlpha = 0.6f
}

data class HelpContent(val title: String, val body: String)

data class ChoiceOption(val title: String, val subtitle: String)

enum class PillTone { OK, ATTENTION, NEUTRAL }

/**
 * A settings page: title, one muted subtitle line, an optional read-only notice, then the blocks
 * spaced by 28 dp. Content is limited to 720 dp and aligned to the start. The page does not scroll
 * by itself: the hosting settings screen owns the scroll container.
 */
@Composable
fun SettingsPage(
    title: String,
    subtitle: String,
    readOnlyNotice: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        // widthIn first: the cap must apply before fillMaxWidth fills what is left.
        modifier = Modifier
            .widthIn(max = SettingsKitDefaults.PageMaxWidth)
            .fillMaxWidth(),
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (subtitle.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!readOnlyNotice.isNullOrBlank()) {
            Spacer(Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f),
                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ) {
                Text(
                    readOnlyNotice,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(SettingsKitDefaults.BlockSpacing),
            content = content,
        )
    }
}

/**
 * A block: title with an optional "?" opening [HelpDialog], then a single `surfaceContainerLow`
 * surface (16 dp corners, no elevation) holding the rows. Separate rows with [SettingsDivider].
 */
@Composable
fun SettingsBlock(
    title: String,
    help: HelpContent? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var showHelp by remember { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(start = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (help != null) {
                IconButton(onClick = { showHelp = true }) {
                    Icon(
                        Icons.AutoMirrored.Outlined.HelpOutline,
                        contentDescription = stringResource(Res.string.settings_help_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = SettingsKitDefaults.BlockShape,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
        ) {
            Column(modifier = Modifier.fillMaxWidth(), content = content)
        }
    }
    if (showHelp && help != null) {
        HelpDialog(content = help, onDismiss = { showHelp = false })
    }
}

@Composable
private fun RowTexts(title: String, subtitle: String?, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        if (!subtitle.isNullOrBlank()) {
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * A row: title, optional effect line (only when it says something the title does not), control at
 * the end. When [onClick] is set the whole row is clickable and, without [trailing], shows a chevron.
 * When [enabled] is false the row is not clickable and its texts are dimmed; [trailing] controls
 * handle their own enabled state.
 */
@Composable
fun SettingsRow(
    title: String,
    subtitle: String? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                } else {
                    Modifier
                },
            )
            .heightIn(min = SettingsKitDefaults.RowMinHeight)
            .padding(
                horizontal = SettingsKitDefaults.RowHorizontalPadding,
                vertical = SettingsKitDefaults.RowVerticalPadding,
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowTexts(
            title,
            subtitle,
            Modifier
                .weight(1f)
                .alpha(if (enabled) 1f else SettingsKitDefaults.DisabledAlpha),
        )
        when {
            trailing != null -> {
                Spacer(Modifier.width(16.dp))
                trailing()
            }
            onClick != null -> {
                Spacer(Modifier.width(12.dp))
                Icon(
                    Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * A row carrying a switch; the whole row toggles. While [checked], [expanded] animates in under the
 * row, indented by [SettingsKitDefaults.SubOptionIndent]. Put [SettingsRow]/[SettingsSwitchRow] in
 * it directly (they bring their own padding); pad free content with
 * [SettingsKitDefaults.RowHorizontalPadding].
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    expanded: @Composable (ColumnScope.() -> Unit)? = null,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = checked,
                    enabled = enabled,
                    role = Role.Switch,
                    onValueChange = onCheckedChange,
                )
                .heightIn(min = SettingsKitDefaults.RowMinHeight)
                .padding(
                    horizontal = SettingsKitDefaults.RowHorizontalPadding,
                    vertical = SettingsKitDefaults.RowVerticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowTexts(
                title,
                subtitle,
                Modifier
                    .weight(1f)
                    .alpha(if (enabled) 1f else SettingsKitDefaults.DisabledAlpha),
            )
            Spacer(Modifier.width(16.dp))
            Switch(checked = checked, onCheckedChange = null, enabled = enabled)
        }
        if (expanded != null) {
            AnimatedVisibility(
                visible = checked,
                enter = expandVertically(animationSpec = tween(220)) + fadeIn(animationSpec = tween(220, delayMillis = 60)),
                exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(200)),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = SettingsKitDefaults.SubOptionIndent, bottom = SettingsKitDefaults.SubOptionBottomPadding),
                    content = expanded,
                )
            }
        }
    }
}

/** Thin separator between rows of a block: `outlineVariant` at 40 %, inset by the row padding. */
@Composable
fun SettingsDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(horizontal = SettingsKitDefaults.RowHorizontalPadding),
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

private data class PillColors(val container: Color, val content: Color)

@Composable
private fun pillColors(tone: PillTone): PillColors {
    val scheme = MaterialTheme.colorScheme
    val dark = scheme.surface.luminance() < 0.5f
    return when (tone) {
        PillTone.OK -> if (dark) {
            PillColors(Color(0xFF1F3A2A), Color(0xFFA6DCB6))
        } else {
            PillColors(Color(0xFFDDF1E3), Color(0xFF1B5E35))
        }
        PillTone.ATTENTION -> if (dark) {
            PillColors(Color(0xFF41301A), Color(0xFFF4C98A))
        } else {
            PillColors(Color(0xFFFCEBD3), Color(0xFF7A4800))
        }
        PillTone.NEUTRAL -> PillColors(scheme.surfaceContainerHighest, scheme.onSurfaceVariant)
    }
}

/** Status label: text only on a tinted, 8 dp rounded background. */
@Composable
fun StatusPill(text: String, tone: PillTone) {
    val colors = pillColors(tone)
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Medium,
        color = colors.content,
        maxLines = 1,
        modifier = Modifier
            .background(colors.container, RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/**
 * Exclusive choice as cards: side by side when there is room, stacked on narrow screens. The chosen
 * card has a 2 dp `primary` outline and a check.
 */
@Composable
fun ChoiceCards(
    options: List<ChoiceOption>,
    selected: Int,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val stacked = maxWidth < 480.dp || options.size > 3
        if (stacked) {
            Column(
                modifier = Modifier.fillMaxWidth().selectableGroup(),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                options.forEachIndexed { index, option ->
                    ChoiceCard(option, index == selected, enabled, { onSelect(index) }, Modifier.fillMaxWidth())
                }
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(IntrinsicSize.Min)
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                options.forEachIndexed { index, option ->
                    ChoiceCard(
                        option,
                        index == selected,
                        enabled,
                        { onSelect(index) },
                        Modifier.weight(1f).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ChoiceCard(
    option: ChoiceOption,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val borderColor by animateColorAsState(
        if (selected) scheme.primary else scheme.outlineVariant,
        animationSpec = tween(180),
    )
    val containerColor by animateColorAsState(
        if (selected) scheme.primary.copy(alpha = 0.06f) else Color.Transparent,
        animationSpec = tween(180),
    )
    val borderWidth = if (selected) 2.dp else 1.dp
    Surface(
        modifier = modifier
            .alpha(if (enabled) 1f else SettingsKitDefaults.DisabledAlpha)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick),
        shape = SettingsKitDefaults.BlockShape,
        color = containerColor,
        border = BorderStroke(borderWidth, borderColor),
    ) {
        // Keep the text in place when the outline grows from 1 to 2 dp.
        val inset = 16.dp - (borderWidth - 1.dp)
        Column(
            modifier = Modifier.padding(inset),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    option.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = scheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                if (selected) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = null,
                        tint = scheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            if (option.subtitle.isNotBlank()) {
                Text(
                    option.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = scheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Domain list as chips removable from their × only, plus an add chip (the `add` string, "Ajouter"
 * in French) that opens an inline field.
 * [onAdd] receives the trimmed text and returns an error message to show, or null on success.
 * When [enabled] is false the chips are read-only and the add chip is hidden.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DomainChips(
    domains: List<String>,
    enabled: Boolean,
    onRemove: (String) -> Unit,
    onAdd: suspend (String) -> String?,
) {
    var adding by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val removeLabel = stringResource(Res.string.remove)

    fun closeField() {
        adding = false
        draft = ""
        error = null
    }

    fun submit() {
        val value = draft.trim()
        if (value.isEmpty() || busy) return
        busy = true
        scope.launch {
            val result = try {
                onAdd(value)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: e.toString()
            } finally {
                busy = false
            }
            if (result == null) closeField() else error = result
        }
    }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            domains.forEach { domain ->
                DomainChip(
                    domain = domain,
                    removeDescription = "$removeLabel @$domain",
                    onRemove = if (enabled) {
                        { onRemove(domain) }
                    } else {
                        null
                    },
                )
            }
            if (enabled && !adding) {
                AssistChip(
                    onClick = { adding = true },
                    label = { Text(stringResource(Res.string.add)) },
                    leadingIcon = {
                        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    },
                )
            }
        }
        AnimatedVisibility(
            visible = enabled && adding,
            enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
            exit = fadeOut(animationSpec = tween(120)) + shrinkVertically(animationSpec = tween(180)),
        ) {
            LaunchedEffect(Unit) {
                // The field may not be attached yet on the first frame; never let focus crash the page.
                runCatching { focusRequester.requestFocus() }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = {
                        draft = it
                        error = null
                    },
                    label = { Text(stringResource(Res.string.firebase_email_domains_label)) },
                    prefix = { Text("@") },
                    singleLine = true,
                    enabled = !busy,
                    isError = error != null,
                    supportingText = error?.let { message -> { Text(message) } },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                )
                Row(
                    modifier = Modifier.padding(top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { submit() }, enabled = !busy && draft.isNotBlank()) {
                        if (busy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        } else {
                            Text(stringResource(Res.string.add))
                        }
                    }
                    IconButton(onClick = { closeField() }, enabled = !busy) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(Res.string.cancel))
                    }
                }
            }
        }
    }
}

/**
 * One domain: an inert label, and when [onRemove] is set, an × that is its own control. The chip
 * itself is not clickable, so a stray tap on the label never removes a domain.
 */
@Composable
private fun DomainChip(domain: String, removeDescription: String, onRemove: (() -> Unit)?) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color.Transparent,
        border = BorderStroke(1.dp, scheme.outlineVariant),
        contentColor = scheme.onSurfaceVariant,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "@$domain",
                style = MaterialTheme.typography.labelLarge,
                color = scheme.onSurface,
                maxLines = 1,
                modifier = Modifier.padding(
                    start = 12.dp,
                    end = if (onRemove != null) 0.dp else 12.dp,
                    top = 6.dp,
                    bottom = 6.dp,
                ),
            )
            if (onRemove != null) {
                // 32 dp visually; Compose hit testing extends it to the 48 dp minimum touch target.
                IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = removeDescription,
                        modifier = Modifier.size(InputChipDefaults.IconSize),
                    )
                }
            }
        }
    }
}

/** Help shown by the "?" of a block. Paragraphs in [HelpContent.body] are separated by blank lines. */
@Composable
fun HelpDialog(content: HelpContent, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(content.title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content.body.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }.forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.close)) }
        },
    )
}

/** Read-only value in monospace (selectable) with a copy button that confirms for two seconds. */
@Composable
fun CopyField(value: String) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(value) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(2000)
            copied = false
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 48.dp)
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SelectionContainer(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(
                    value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(
                onClick = {
                    clipboard.setText(AnnotatedString(value))
                    copied = true
                },
            ) {
                Icon(
                    if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
                    contentDescription = if (copied) {
                        stringResource(Res.string.guest_form_link_copied)
                    } else {
                        stringResource(Res.string.portal_password_copy)
                    },
                    tint = if (copied) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}
