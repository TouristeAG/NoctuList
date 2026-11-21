package com.eventmanager.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/** Overlapping holders (space transitions) must not clear the flag under each other. Main thread only. */
private var holders = 0

/**
 * FLAG_KEEP_SCREEN_ON keeps the display lit while the activity is in the foreground, so the
 * screen never times out and the keyguard never engages on its own. Android drops the flag's
 * effect automatically when the app goes to the background.
 */
@Composable
actual fun KeepScreenAwakeEffect(enabled: Boolean) {
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity, enabled) {
        val window = activity?.window
        if (!enabled || window == null) return@DisposableEffect onDispose {}
        if (holders++ == 0) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            if (--holders == 0) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
}
