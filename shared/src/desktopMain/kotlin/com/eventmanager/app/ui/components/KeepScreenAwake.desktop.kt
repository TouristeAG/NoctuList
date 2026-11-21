package com.eventmanager.app.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import com.eventmanager.app.platform.DesktopKeepAwake

@Composable
actual fun KeepScreenAwakeEffect(enabled: Boolean) {
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose {}
        DesktopKeepAwake.acquire()
        onDispose { DesktopKeepAwake.release() }
    }
}
