package com.eventmanager.app.ui.components.settings.certificate

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.FormatAlignCenter
import androidx.compose.material.icons.filled.FormatAlignJustify
import androidx.compose.material.icons.filled.FormatAlignLeft
import androidx.compose.material.icons.filled.FormatAlignRight
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatUnderlined
import androidx.compose.material.icons.filled.Wc
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eventmanager.app.portal.certificate.CertAlign
import com.eventmanager.app.portal.certificate.CertSize
import com.eventmanager.app.resources.*
import com.mohamedrejeb.richeditor.model.RichTextState
import com.mohamedrejeb.richeditor.ui.BasicRichTextEditor
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

internal fun blockTitle(key: String): StringResource = when (key) {
    "header" -> Res.string.certificate_block_header
    "title" -> Res.string.certificate_block_title
    "intro" -> Res.string.certificate_block_intro
    "MANDATE" -> Res.string.certificate_block_mandate
    "SHIFT" -> Res.string.certificate_block_shift
    "PHOTO" -> Res.string.certificate_block_photo
    "GRAPHIC_EVENT" -> Res.string.certificate_block_graphic_event
    "GRAPHIC_ASSOCIATION" -> Res.string.certificate_block_graphic_association
    "OTHER" -> Res.string.certificate_block_other
    "MEETING" -> Res.string.certificate_block_meeting
    "closing" -> Res.string.certificate_block_closing
    "dateLine" -> Res.string.certificate_block_date_line
    else -> Res.string.certificate_block_footer
}

private fun variantLabel(variant: CertVariant): StringResource = when (variant) {
    CertVariant.ONE -> Res.string.certificate_variant_one
    CertVariant.MANY -> Res.string.certificate_variant_many
    CertVariant.MANY_ONE_ROLE -> Res.string.certificate_variant_many_one_role
    CertVariant.LINE -> Res.string.certificate_variant_line
}

internal fun variableLabel(key: String): StringResource = when (key) {
    "firstName" -> Res.string.certificate_var_firstName
    "lastName" -> Res.string.certificate_var_lastName
    "association" -> Res.string.certificate_var_association
    "period" -> Res.string.certificate_var_period
    "start" -> Res.string.certificate_var_start
    "end" -> Res.string.certificate_var_end
    "date" -> Res.string.certificate_var_date
    "count" -> Res.string.certificate_var_count
    "role" -> Res.string.certificate_var_role
    "description" -> Res.string.certificate_var_description
    "roles" -> Res.string.certificate_var_roles
    "place" -> Res.string.certificate_var_place
    else -> Res.string.certificate_var_today
}

private fun sizeLabel(size: CertSize): StringResource = when (size) {
    CertSize.SMALL -> Res.string.certificate_size_small
    CertSize.NORMAL -> Res.string.certificate_size_normal
    CertSize.LARGE -> Res.string.certificate_size_large
    CertSize.TITLE -> Res.string.certificate_size_title
}

/** One toolbar for all blocks; it acts on the editor that last had the focus. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CertificateToolbar(model: CertificateEditorModel, wrap: Boolean, modifier: Modifier = Modifier) {
    val state = model.active
    val key = model.activeKey
    val enabled = state != null
    // Read so the buttons follow the caret.
    state?.selection
    state?.annotatedString
    var genderFor by remember { mutableStateOf<Pair<TextRange, String>?>(null) }

    Surface(modifier = modifier.fillMaxWidth(), tonalElevation = 2.dp, shape = RoundedCornerShape(12.dp)) {
        // Wraps on wide screens; scrolls sideways on a phone, where several rows would hide the text.
        FlowRow(
            modifier = (if (wrap) Modifier else Modifier.horizontalScroll(rememberScrollState()))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.Center,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ToolToggle(Icons.Default.FormatBold, Res.string.certificate_bold, enabled, state != null && CertificateTextActions.isBold(state)) {
                state?.let(CertificateTextActions::toggleBold)
            }
            ToolToggle(Icons.Default.FormatItalic, Res.string.certificate_italic, enabled, state != null && CertificateTextActions.isItalic(state)) {
                state?.let(CertificateTextActions::toggleItalic)
            }
            ToolToggle(Icons.Default.FormatUnderlined, Res.string.certificate_underline, enabled, state != null && CertificateTextActions.isUnderline(state)) {
                state?.let(CertificateTextActions::toggleUnderline)
            }
            ToolDivider()
            val align = state?.let(CertificateTextActions::align)
            for ((value, icon, label) in listOf(
                Triple(CertAlign.LEFT, Icons.Default.FormatAlignLeft, Res.string.certificate_align_left),
                Triple(CertAlign.CENTER, Icons.Default.FormatAlignCenter, Res.string.certificate_align_center),
                Triple(CertAlign.RIGHT, Icons.Default.FormatAlignRight, Res.string.certificate_align_right),
                Triple(CertAlign.JUSTIFY, Icons.Default.FormatAlignJustify, Res.string.certificate_align_justify),
            )) {
                ToolToggle(icon, label, enabled, align == value) { state?.let { CertificateTextActions.setAlign(it, value) } }
            }
            ToolDivider()
            SizeMenu(state)
            ToolToggle(Icons.AutoMirrored.Filled.FormatListBulleted, Res.string.certificate_bullets, enabled, state?.isUnorderedList == true) {
                state?.let(CertificateTextActions::toggleBullets)
            }
            ToolDivider()
            VariableMenu(state, key?.let { (k, v) -> CertificateBlocks.all.first { it.key == k }.variables(v) }.orEmpty())
            val word = state?.let(CertificateTextActions::selectedWord)
            val genderLabel = stringResource(Res.string.certificate_gender)
            Tooltip(if (word == null) stringResource(Res.string.certificate_gender_hint) else genderLabel) {
                TextButton(
                    onClick = { if (state != null && word != null) genderFor = state.selection to word },
                    enabled = word != null,
                    modifier = Modifier.focusProperties { canFocus = false },
                ) {
                    Icon(Icons.Default.Wc, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(genderLabel)
                }
            }
        }
    }

    val pending = genderFor
    if (pending != null && state != null) {
        GenderDialog(
            word = pending.second,
            onDismiss = { genderFor = null },
            onApply = { forms ->
                CertificateTextActions.makeAgreement(state, pending.first, forms)
                genderFor = null
            },
        )
    }
}

@Composable
private fun Tooltip(text: String, content: @Composable () -> Unit) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(text) } },
        state = rememberTooltipState(),
    ) { content() }
}

@Composable
private fun ToolToggle(icon: ImageVector, label: StringResource, enabled: Boolean, checked: Boolean, onClick: () -> Unit) {
    val text = stringResource(label)
    Tooltip(text) {
        IconToggleButton(
            checked = checked,
            onCheckedChange = { onClick() },
            enabled = enabled,
            colors = IconButtonDefaults.iconToggleButtonColors(
                checkedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                checkedContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
            modifier = Modifier.size(40.dp).focusProperties { canFocus = false },
        ) { Icon(icon, contentDescription = text, modifier = Modifier.size(20.dp)) }
    }
}

@Composable
private fun ToolDivider() {
    VerticalDivider(Modifier.height(40.dp).padding(horizontal = 4.dp, vertical = 8.dp))
}

@Composable
private fun SizeMenu(state: RichTextState?) {
    var open by remember { mutableStateOf(false) }
    val current = state?.let(CertificateTextActions::size) ?: CertSize.NORMAL
    Box {
        TextButton(
            onClick = { open = true },
            enabled = state != null,
            modifier = Modifier.width(112.dp).height(40.dp).focusProperties { canFocus = false },
        ) {
            Text(stringResource(sizeLabel(current)), modifier = Modifier.weight(1f))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (size in CertSize.entries) {
                DropdownMenuItem(
                    text = {
                        Text(
                            stringResource(sizeLabel(size)),
                            style = MaterialTheme.typography.bodyLarge.copy(fontSize = CertificateTextActions.sizeSp(size)),
                        )
                    },
                    onClick = {
                        open = false
                        state?.let { CertificateTextActions.setSize(it, size) }
                    },
                )
            }
        }
    }
}

@Composable
private fun VariableMenu(state: RichTextState?, variables: List<String>) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(
            onClick = { open = true },
            enabled = state != null && variables.isNotEmpty(),
            modifier = Modifier.focusProperties { canFocus = false },
        ) {
            Icon(Icons.Default.DataObject, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(Res.string.certificate_insert_variable))
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (key in variables) {
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(variableLabel(key)), modifier = Modifier.weight(1f, fill = false))
                            Spacer(Modifier.width(16.dp))
                            Text(
                                "{$key}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                    onClick = {
                        open = false
                        state?.let { CertificateTextActions.insertVariable(it, key) }
                    },
                )
            }
        }
    }
}

@Composable
private fun GenderDialog(word: String, onDismiss: () -> Unit, onApply: (List<String>) -> Unit) {
    var male by remember { mutableStateOf(word) }
    var female by remember { mutableStateOf(word + "e") }
    var other by remember { mutableStateOf(word + "x") }
    val valid = male.isNotBlank() && female.isNotBlank() && other.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.certificate_gender)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(male, { male = it }, label = { Text(stringResource(Res.string.certificate_gender_male)) }, singleLine = true)
                OutlinedTextField(female, { female = it }, label = { Text(stringResource(Res.string.certificate_gender_female)) }, singleLine = true)
                OutlinedTextField(other, { other = it }, label = { Text(stringResource(Res.string.certificate_gender_nonbinary)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(onClick = { onApply(listOf(male.trim(), female.trim(), other.trim())) }, enabled = valid) {
                Text(stringResource(Res.string.certificate_apply))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(Res.string.cancel)) } },
    )
}

/** A block of the document: its title, the variant tabs when it has several, and a page-like rich text area. */
@Composable
internal fun CertificateBlockCard(
    model: CertificateEditorModel,
    spec: CertBlockSpec,
    family: FontFamily,
    placeholder: String? = null,
    extra: @Composable (() -> Unit)? = null,
) {
    var variant by rememberSaveable(spec.key) { mutableStateOf(spec.variants.first()) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(blockTitle(spec.key)),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
        if (spec.variants.size > 1) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                for (v in spec.variants) {
                    FilterChip(
                        selected = v == variant,
                        onClick = {
                            variant = v
                            model.activeKey = spec.key to v
                        },
                        label = { Text(stringResource(variantLabel(v))) },
                    )
                }
            }
        }
        val key = spec.key to variant
        CertificateTextArea(
            state = model.editor(spec.key, variant),
            family = family,
            active = model.activeKey == key,
            placeholder = placeholder,
            onFocused = { model.activeKey = key },
        )
        extra?.invoke()
    }
}

@Composable
private fun CertificateTextArea(
    state: RichTextState,
    family: FontFamily,
    active: Boolean,
    placeholder: String?,
    onFocused: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val colors = MaterialTheme.colorScheme
    val borderColor = when {
        focused -> colors.primary
        active -> colors.primary.copy(alpha = 0.5f)
        else -> colors.outlineVariant
    }
    BasicRichTextEditor(
        state = state,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .background(colors.surface, RoundedCornerShape(8.dp))
            .border(if (focused) 2.dp else 1.dp, borderColor, RoundedCornerShape(8.dp))
            .onFocusChanged { if (it.isFocused) onFocused() },
        textStyle = TextStyle(
            fontFamily = family,
            fontSize = CertificateTextActions.sizeSp(CertSize.NORMAL),
            color = colors.onSurface,
        ),
        interactionSource = interaction,
        cursorBrush = SolidColor(colors.primary),
        decorationBox = { inner ->
            // Full width, so centered and right-aligned paragraphs show where they will be.
            Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), propagateMinConstraints = true) {
                if (placeholder != null && state.annotatedString.text.isEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
                inner()
            }
        },
    )
}
