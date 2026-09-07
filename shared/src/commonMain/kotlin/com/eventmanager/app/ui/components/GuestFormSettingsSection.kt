package com.eventmanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.guest_form_copy_link
import com.eventmanager.app.resources.guest_form_enable
import com.eventmanager.app.resources.guest_form_link_copied
import com.eventmanager.app.resources.guest_form_settings_body
import com.eventmanager.app.resources.guest_form_settings_title
import com.eventmanager.app.resources.guest_form_site_url
import org.jetbrains.compose.resources.stringResource

@Composable
fun GuestFormSettingsCard(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    siteOrigin: String,
    canEdit: Boolean,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    var copiedOrigin by remember { mutableStateOf(false) }

    GuidedStepCard(
        title = stringResource(Res.string.guest_form_settings_title),
        body = stringResource(Res.string.guest_form_settings_body),
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                stringResource(Res.string.guest_form_enable),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            Switch(
                checked = enabled,
                onCheckedChange = onEnabledChange,
                enabled = canEdit,
            )
        }
        if (siteOrigin.isNotBlank()) {
            Text(
                stringResource(Res.string.guest_form_site_url, siteOrigin),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(siteOrigin))
                    copiedOrigin = true
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    if (copiedOrigin) stringResource(Res.string.guest_form_link_copied)
                    else stringResource(Res.string.guest_form_copy_link),
                )
            }
        }
    }
}
