package com.eventmanager.app.ui.components

import android.app.Activity
import android.content.ClipData
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Share a PNG so the receiving app gets the image, not a caption.
 *
 * ACTION_SEND with EXTRA_TEXT + EXTRA_STREAM is treated as text-only by many
 * targets (Messages, WhatsApp, Telegram, mail). URI grants also fail unless
 * ClipData is set, so those apps drop the stream and keep the caption.
 */
internal fun sharePngImage(
    context: Context,
    image: ImageBitmap,
    fileName: String,
    chooserTitle: String,
    subject: String? = null,
) {
    val app = context.applicationContext
    val file = File(app.cacheDir, fileName)
    val source = image.asAndroidBitmap()
    val bitmap = if (source.config == Bitmap.Config.HARDWARE) {
        source.copy(Bitmap.Config.ARGB_8888, false)
            ?: error("Unable to copy hardware bitmap")
    } else {
        source
    }
    FileOutputStream(file).use { out ->
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
            "Failed to compress PNG"
        }
        out.flush()
    }
    val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        if (!subject.isNullOrBlank()) {
            putExtra(Intent.EXTRA_SUBJECT, subject)
        }
        clipData = ClipData.newRawUri(fileName, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    val chooser = Intent.createChooser(send, chooserTitle).apply {
        clipData = send.clipData
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (context.findActivityOrNull() == null) {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }
    context.startActivity(chooser)
}

private tailrec fun Context.findActivityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityOrNull()
    else -> null
}
