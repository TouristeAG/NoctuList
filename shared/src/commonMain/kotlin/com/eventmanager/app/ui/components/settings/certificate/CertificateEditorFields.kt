package com.eventmanager.app.ui.components.settings.certificate

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.eventmanager.app.resources.*
import com.eventmanager.app.ui.components.rememberProfilePhotoPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
}

/** The published logo, which the certificate letterhead uses, and a way to the setting that changes it. */
@Composable
internal fun CertificateLogoSection(logoBase64: String, onChangeLogo: () -> Unit) {
    val logo = remember(logoBase64) { decodeBase64Image(logoBase64) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionTitle(stringResource(Res.string.certificate_logo))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (logo != null) {
                Image(
                    logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .heightIn(max = 48.dp)
                        .widthIn(max = 200.dp)
                        .background(Color.White, RoundedCornerShape(6.dp))
                        .padding(4.dp),
                )
            } else {
                Text(
                    stringResource(Res.string.certificate_logo_none),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
            TextButton(onClick = onChangeLogo) { Text(stringResource(Res.string.certificate_logo_change)) }
        }
    }
}

@Composable
internal fun CertificatePlaceField(model: CertificateEditorModel) {
    val missing = model.place.isBlank()
    OutlinedTextField(
        value = model.place,
        onValueChange = { model.place = it },
        label = { Text(stringResource(Res.string.certificate_var_place)) },
        singleLine = true,
        isError = missing,
        supportingText = if (missing) {
            { Text(stringResource(Res.string.certificate_place_required)) }
        } else {
            null
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Signer name, position and an optional signature image (shrunk to 600 px wide, PNG). */
@Composable
internal fun CertificateSignatureSection(model: CertificateEditorModel) {
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    val tooLarge = stringResource(Res.string.certificate_signature_too_large)
    val unreadable = stringResource(Res.string.certificate_signature_unreadable)
    val pick = rememberProfilePhotoPicker { bytes ->
        scope.launch {
            val png = withContext(Dispatchers.Default) {
                resizeSignatureToPng(bytes, CertificateEditorModel.SIGNATURE_MAX_WIDTH)
            }
            if (png == null) {
                error = unreadable
                return@launch
            }
            val encoded = java.util.Base64.getEncoder().encodeToString(png)
            if (encoded.length > CertificateEditorModel.MAX_SIGNATURE_CHARS) {
                error = tooLarge
            } else {
                error = null
                model.signerImage = encoded
            }
        }
    }
    val image = remember(model.signerImage) { decodeBase64Image(model.signerImage) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionTitle(stringResource(Res.string.certificate_block_signature))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = model.signerName,
                onValueChange = { model.signerName = it },
                label = { Text(stringResource(Res.string.certificate_signer_name)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = model.signerTitle,
                onValueChange = { model.signerTitle = it },
                label = { Text(stringResource(Res.string.certificate_signer_title)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (image != null) {
                Image(
                    image,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .heightIn(max = 56.dp)
                        .widthIn(max = 160.dp)
                        .background(Color.White, RoundedCornerShape(6.dp))
                        .padding(4.dp),
                )
                OutlinedButton(onClick = pick) { Text(stringResource(Res.string.certificate_signature_change)) }
                TextButton(onClick = {
                    model.signerImage = ""
                    error = null
                }) { Text(stringResource(Res.string.certificate_signature_remove)) }
            } else {
                OutlinedButton(onClick = pick) { Text(stringResource(Res.string.certificate_signature_add)) }
            }
        }
        Text(
            stringResource(Res.string.certificate_signature_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Sender, reply-to, subject and body of the e-mail that carries the PDF. Folded by default. */
@Composable
internal fun CertificateMailSection(model: CertificateEditorModel, gmailAddress: String) {
    val open = model.mailOpen
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { model.mailOpen = !open }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                stringResource(Res.string.certificate_mail_section),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, contentDescription = null)
        }
        if (!open) return@Column
        OutlinedTextField(
            value = model.fromName,
            onValueChange = { model.fromName = it },
            label = { Text(stringResource(Res.string.certificate_mail_from_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model.fromAddress,
            onValueChange = { model.fromAddress = it.trim() },
            label = { Text(stringResource(Res.string.certificate_mail_from_address)) },
            placeholder = if (gmailAddress.isNotBlank()) {
                { Text(gmailAddress) }
            } else {
                null
            },
            supportingText = { Text(stringResource(Res.string.certificate_mail_from_note)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model.replyTo,
            onValueChange = { model.replyTo = it.trim() },
            label = { Text(stringResource(Res.string.certificate_mail_reply_to)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model.subject,
            onValueChange = { model.subject = it },
            label = { Text(stringResource(Res.string.certificate_mail_subject)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model.body,
            onValueChange = { model.body = it },
            label = { Text(stringResource(Res.string.certificate_mail_body)) },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
