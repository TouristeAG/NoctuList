package com.eventmanager.app.ui.platform

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.platform.PlatformFileManager
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource

actual fun supportsResolutionScaleStep(): Boolean = false

@Composable
actual fun ServiceAccountKeyUploadButton(
    platformContext: PlatformContext,
    onStatusUpdate: (String) -> Unit,
    modifier: Modifier
) {
    val scope = rememberCoroutineScope()
    val fileManager = remember(platformContext) { PlatformFileManager(platformContext) }
    val selectingFile = stringResource(Res.string.selecting_file)
    val cancelled = stringResource(Res.string.action_cancelled)
    val validatingFile = stringResource(Res.string.validating_file)
    val invalidJson = stringResource(Res.string.invalid_service_account_json)
    val uploadedSuccess = stringResource(Res.string.file_uploaded_successfully)
    val uploadFailed = stringResource(Res.string.upload_failed_generic)

    Button(
        onClick = {
            scope.launch {
                onStatusUpdate(selectingFile)
                val json = fileManager.pickServiceAccountJsonFile()
                if (json == null) {
                    onStatusUpdate(cancelled)
                    return@launch
                }
                onStatusUpdate(validatingFile)
                if (!json.contains("\"type\"") || !json.contains("service_account")) {
                    onStatusUpdate(invalidJson)
                    return@launch
                }
                val saved = withContext(Dispatchers.IO) { fileManager.saveServiceAccountJson(json) }
                onStatusUpdate(if (saved) uploadedSuccess else uploadFailed)
            }
        },
        modifier = modifier
    ) {
        Icon(Icons.Default.Upload, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(stringResource(Res.string.upload_key_file))
    }
}

@Composable
fun EmailLogoUploadSection(
    platformContext: PlatformContext,
    logoPath: String,
    onLogoPathChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val fileManager = remember(platformContext) { PlatformFileManager(platformContext) }
    val cancelled = stringResource(Res.string.action_cancelled)

    val logoBitmap = remember(logoPath) {
        val file = fileManager.getEmailLogoFile() ?: logoPath.takeIf { it.isNotBlank() }?.let { java.io.File(it) }
        file?.takeIf { it.exists() }?.let { path ->
            runCatching {
                val buffered = javax.imageio.ImageIO.read(path) ?: return@runCatching null
                val bytes = java.io.ByteArrayOutputStream().use { baos ->
                    javax.imageio.ImageIO.write(buffered, "png", baos)
                    baos.toByteArray()
                }
                org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()
            }.getOrNull()
        }
    }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (logoBitmap != null) {
                Text(
                    stringResource(Res.string.email_logo_preview),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                androidx.compose.foundation.Image(
                    bitmap = logoBitmap,
                    contentDescription = stringResource(Res.string.email_logo_preview),
                    modifier = Modifier
                        .size(100.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                val path = fileManager.pickEmailLogoImageFile()
                                if (path == null) return@launch
                                onLogoPathChanged(path)
                            }
                        }
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(Res.string.email_logo_change))
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) { fileManager.clearEmailLogoFile() }
                                onLogoPathChanged("")
                            }
                        },
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(stringResource(Res.string.email_logo_remove))
                    }
                }
            } else {
                Icon(
                    Icons.Default.Image,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = {
                        scope.launch {
                            val path = fileManager.pickEmailLogoImageFile()
                            if (path == null) {
                                showPlatformToast(platformContext, cancelled)
                                return@launch
                            }
                            onLogoPathChanged(path)
                        }
                    }
                ) {
                    Icon(Icons.Default.Upload, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(Res.string.email_logo_upload))
                }
            }
        }
    }
}

@Composable
actual fun SetupLayoutScalePage(
    resolutionScale: Float,
    onSave: (Float) -> Unit,
    onUseRecommended: () -> Unit,
    modifier: Modifier
) {
    // Resolution scaling is not used on desktop.
}
