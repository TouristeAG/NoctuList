package com.eventmanager.app.platform

import android.os.Build

actual fun localDeviceName(): String {
    val maker = Build.MANUFACTURER.orEmpty().trim()
    val model = Build.MODEL.orEmpty().trim()
    return if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model".trim()
}
