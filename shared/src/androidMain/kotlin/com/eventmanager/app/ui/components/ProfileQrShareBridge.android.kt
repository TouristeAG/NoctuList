package com.eventmanager.app.ui.components

import com.eventmanager.app.R
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.utils.QRCodeUtils

internal actual object ProfileQrShareBridge {
    actual fun shareProfileQrCode(
        platformContext: PlatformContext,
        qrPayload: String,
        fileName: String,
        title: String,
    ) {
        val context = platformContext.androidContext
        val bitmap = QRCodeUtils.generateQrImageBitmap(qrPayload, 512) ?: return
        runCatching {
            sharePngImage(
                context = context,
                image = bitmap,
                fileName = fileName,
                chooserTitle = context.getString(R.string.share_qr_code),
                subject = title,
            )
        }
    }
}
