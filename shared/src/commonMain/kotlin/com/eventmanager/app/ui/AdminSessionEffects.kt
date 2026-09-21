package com.eventmanager.app.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.platform.getAdminSessionHost
import kotlinx.coroutines.delay

@Composable
internal fun BindAdminSessionWatchdog(
    adminSurfaceActive: Boolean,
    platformContext: PlatformContext,
    onEndAdminSession: () -> Unit,
) {
    val host = getAdminSessionHost(platformContext)
    val endSession by rememberUpdatedState(onEndAdminSession)
    DisposableEffect(adminSurfaceActive, host) {
        if (host == null) return@DisposableEffect onDispose { }
        if (adminSurfaceActive) {
            host.adminSessionWatchdog.startMonitoring()
            host.adminSessionAutoLogout = { endSession() }
        } else {
            host.adminSessionWatchdog.stopMonitoring()
            host.adminSessionAutoLogout = null
        }
        onDispose {
            host.adminSessionWatchdog.stopMonitoring()
            host.adminSessionAutoLogout = null
        }
    }
    LaunchedEffect(adminSurfaceActive, host) {
        if (!adminSurfaceActive || host == null) return@LaunchedEffect
        while (true) {
            delay(ADMIN_SESSION_POLL_INTERVAL_MS)
            if (host.adminSessionWatchdog.consumeShouldEndSession()) {
                endSession()
                break
            }
        }
    }
}
