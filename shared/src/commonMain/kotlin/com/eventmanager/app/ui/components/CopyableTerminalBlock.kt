package com.eventmanager.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.guest_form_commands_copied
import com.eventmanager.app.resources.guest_form_copy_commands
import com.eventmanager.app.resources.guest_form_terminal_caption
import com.eventmanager.app.resources.guest_form_terminal_title
import org.jetbrains.compose.resources.stringResource

private val TerminalBackground = Color(0xFF0D1117)
private val TerminalBorder = Color(0xFF30363D)
private val TerminalText = Color(0xFFE6EDF3)
private val TerminalChrome = Color(0xFF161B22)
private val TerminalMuted = Color(0xFF8B949E)

/** Three commands that publish the form site once. [projectId] is filled in when we know it. */
fun guestFormHostingCommands(projectId: String): String {
    val id = projectId.trim().ifBlank { "YOUR_PROJECT_ID" }
    return buildString {
        appendLine("npm install -g firebase-tools")
        appendLine("firebase login")
        append("firebase deploy --only hosting --project $id")
    }
}

/**
 * A read-only terminal-style text field. The user can select the commands, copy with the
 * system shortcut, or tap the button — the previous plain [Text] lines were not copyable.
 */
@Composable
fun CopyableTerminalBlock(
    commands: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
) {
    val clipboard = LocalClipboardManager.current
    var copied by remember(commands) { mutableStateOf(false) }
    val resolvedCaption = caption ?: stringResource(Res.string.guest_form_terminal_caption)
    val shape = RoundedCornerShape(12.dp)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                resolvedCaption,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            TextButton(
                onClick = {
                    clipboard.setText(AnnotatedString(commands))
                    copied = true
                },
            ) {
                Text(
                    if (copied) stringResource(Res.string.guest_form_commands_copied)
                    else stringResource(Res.string.guest_form_copy_commands),
                )
            }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .border(1.dp, TerminalBorder, shape)
                .background(TerminalBackground),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(TerminalChrome)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(Res.string.guest_form_terminal_title),
                    color = TerminalMuted,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
            BasicTextField(
                value = commands,
                onValueChange = {},
                readOnly = true,
                textStyle = TextStyle(
                    color = TerminalText,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 13.sp,
                    lineHeight = 20.sp,
                ),
                cursorBrush = SolidColor(TerminalText),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 88.dp)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxWidth()) { inner() }
                },
            )
        }
    }
}
