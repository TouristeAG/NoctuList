package com.eventmanager.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.eventmanager.app.data.remote.loadProfilePhotoBytesForExport
import com.eventmanager.app.platform.DesktopFileActions
import com.eventmanager.app.platform.NativeDesktopFileDialog
import com.eventmanager.app.platform.PlatformContext
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image

@Composable
internal actual fun ProfileDecodedImage(
    bytes: ByteArray,
    modifier: Modifier,
    contentDescription: String?,
    contentScale: ContentScale,
    colorFilter: ColorFilter?,
) {
    val bitmap = remember(bytes) {
        runCatching { Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
    } ?: return
    Image(
        bitmap = bitmap,
        contentDescription = contentDescription,
        modifier = modifier,
        contentScale = contentScale,
        colorFilter = colorFilter,
    )
}

@Composable
actual fun rememberProfilePhotoPicker(onPicked: (ByteArray) -> Unit): () -> Unit {
    val scope = rememberCoroutineScope()
    val currentOnPicked by rememberUpdatedState(onPicked)
    return remember(scope) {
        {
            scope.launch {
                val file = NativeDesktopFileDialog.pickOpen(
                    title = "Profile photo",
                    allowedExtensions = listOf("jpg", "jpeg", "png", "webp", "heic", "heif"),
                ) ?: return@launch
                val bytes = withContext(Dispatchers.IO) {
                    runCatching { file.readBytes() }.getOrNull()
                } ?: return@launch
                currentOnPicked(bytes)
            }
        }
    }
}

internal actual object ProfilePhotoExport {
    actual suspend fun share(
        platformContext: PlatformContext,
        url: String,
        fileName: String,
        storagePath: String,
    ) {
        val bytes = withContext(Dispatchers.IO) {
            loadProfilePhotoBytesForExport(platformContext, url, storagePath)
        } ?: return
        val safeName = sanitizeProfilePhotoExportFileName(fileName)
        val file = withContext(Dispatchers.IO) {
            File(System.getProperty("java.io.tmpdir"), safeName).also { it.writeBytes(bytes) }
        }
        withContext(Dispatchers.Main) {
            DesktopFileActions.share(file)
        }
    }

    actual suspend fun download(
        platformContext: PlatformContext,
        url: String,
        fileName: String,
        storagePath: String,
    ) {
        val bytes = withContext(Dispatchers.IO) {
            loadProfilePhotoBytesForExport(platformContext, url, storagePath)
        } ?: return
        val safeName = sanitizeProfilePhotoExportFileName(fileName)
        val target = withContext(Dispatchers.Main) {
            pickSaveLocation(safeName)
        } ?: return
        withContext(Dispatchers.IO) {
            runCatching { target.writeBytes(bytes) }
        }
    }

    private fun pickSaveLocation(fileName: String): File? {
        val owner = Frame().apply {
            isUndecorated = true
            isVisible = false
        }
        return try {
            val dialog = FileDialog(owner, "Save photo", FileDialog.SAVE).apply {
                file = fileName
            }
            dialog.isVisible = true
            val dir = dialog.directory
            val file = dialog.file
            if (dir.isNullOrBlank() || file.isNullOrBlank()) null else File(dir, file)
        } finally {
            owner.dispose()
        }
    }
}
