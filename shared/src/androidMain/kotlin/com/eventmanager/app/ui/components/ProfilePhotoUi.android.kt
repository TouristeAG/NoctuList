package com.eventmanager.app.ui.components

import android.content.ContentValues
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.eventmanager.app.R
import com.eventmanager.app.data.remote.loadProfilePhotoBytesForExport
import com.eventmanager.app.platform.PlatformContext
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal actual fun ProfileDecodedImage(
    bytes: ByteArray,
    modifier: Modifier,
    contentDescription: String?,
    contentScale: ContentScale,
    colorFilter: ColorFilter?,
) {
    val bitmap = remember(bytes) {
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
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
    val context = LocalContext.current
    val currentOnPicked by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
        if (bytes != null) currentOnPicked(bytes)
    }
    return remember(launcher) {
        {
            launcher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
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
        val context = platformContext.androidContext
        val bytes = withContext(Dispatchers.IO) {
            loadProfilePhotoBytesForExport(platformContext, url, storagePath)
        }
        if (bytes == null || bytes.isEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.profile_photo_export_failed), Toast.LENGTH_SHORT).show()
            }
            return
        }
        val safeName = sanitizeProfilePhotoExportFileName(fileName)
        val file = withContext(Dispatchers.IO) {
            File(context.cacheDir, safeName).also { it.writeBytes(bytes) }
        }
        val launched = withContext(Dispatchers.Main) {
            runCatching {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "image/jpeg"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(intent, context.getString(R.string.share_profile_photo)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(chooser)
            }.isSuccess
        }
        if (!launched) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.profile_photo_export_failed), Toast.LENGTH_SHORT).show()
            }
        }
    }

    actual suspend fun download(
        platformContext: PlatformContext,
        url: String,
        fileName: String,
        storagePath: String,
    ) {
        val context = platformContext.androidContext
        val bytes = withContext(Dispatchers.IO) {
            loadProfilePhotoBytesForExport(platformContext, url, storagePath)
        }
        if (bytes == null || bytes.isEmpty()) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, context.getString(R.string.profile_photo_export_failed), Toast.LENGTH_SHORT).show()
            }
            return
        }
        val safeName = sanitizeProfilePhotoExportFileName(fileName)
        val saved = withContext(Dispatchers.IO) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    if (Build.VERSION.SDK_INT >= 29) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                }
                val collection = if (Build.VERSION.SDK_INT >= 29) {
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                val uri = context.contentResolver.insert(collection, values)
                if (uri != null) {
                    context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                        ?: return@runCatching false
                    if (Build.VERSION.SDK_INT >= 29) {
                        val pending = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                        context.contentResolver.update(uri, pending, null, null)
                    }
                    true
                } else {
                    val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
                    File(dir, safeName).writeBytes(bytes)
                    true
                }
            }.getOrDefault(false)
        }
        withContext(Dispatchers.Main) {
            val message = if (saved) {
                context.getString(R.string.profile_photo_saved_downloads)
            } else {
                context.getString(R.string.profile_photo_export_failed)
            }
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
