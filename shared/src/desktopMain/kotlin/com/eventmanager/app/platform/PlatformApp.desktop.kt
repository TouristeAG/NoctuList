package com.eventmanager.app.platform

import androidx.compose.runtime.Composable
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.Modifier
import com.eventmanager.app.ui.AdminSessionHost
import java.awt.Desktop
import java.net.URI

actual fun recreateActivity(platformContext: PlatformContext) { /* desktop applies settings live */ }

actual fun getAdminSessionHost(platformContext: PlatformContext): AdminSessionHost? {
    DesktopAdminSessionHost.ensureStarted()
    return DesktopAdminSessionHost
}

actual fun finishApplication(platformContext: PlatformContext) {
    kotlin.system.exitProcess(0)
}

actual fun openDateSettings(platformContext: PlatformContext) {
    when {
        isMacOs() -> openMacDateSettings()
        isWindows() -> openWindowsDateSettings()
        else -> openLinuxDateSettings()
    }
}

private fun isMacOs(): Boolean {
    val os = System.getProperty("os.name").orEmpty().lowercase()
    return "mac" in os || "darwin" in os
}

private fun isWindows(): Boolean = "win" in System.getProperty("os.name").orEmpty().lowercase()

private fun openMacDateSettings() {
    val url = "x-apple.systempreferences:com.apple.preference.datetime"
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        try {
            Desktop.getDesktop().browse(URI(url))
            return
        } catch (_: Exception) { }
    }
    try {
        Runtime.getRuntime().exec(arrayOf("open", url))
    } catch (_: Exception) { }
}

private fun openWindowsDateSettings() {
    try {
        Runtime.getRuntime().exec(arrayOf("cmd", "/c", "start", "", "ms-settings:dateandtime"))
    } catch (_: Exception) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI("ms-settings:dateandtime"))
            }
        } catch (_: Exception) { }
    }
}

private fun openLinuxDateSettings() {
    val commands = listOf(
        arrayOf("gnome-control-center", "datetime"),
        arrayOf("xdg-open", "settings://system/date-time"),
        arrayOf("kcmshell5", "kcm_clock")
    )
    for (command in commands) {
        if (runDetached(command)) return
    }
}

private fun runDetached(command: Array<String>): Boolean =
    try {
        ProcessBuilder(*command).start()
        true
    } catch (_: Exception) {
        false
    }

actual fun openExternalUrl(platformContext: PlatformContext, url: String) {
    openUrl(url)
}

@Composable
actual fun PlatformBackHandler(enabled: Boolean, onBack: () -> Unit) {
    if (!enabled) return
}

actual fun elapsedRealtimeMs(): Long = System.nanoTime() / 1_000_000L
