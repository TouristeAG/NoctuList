package com.eventmanager.app.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.eventmanager.app.data.utils.AppTimeZone
import com.eventmanager.app.data.sync.FileAppLogger
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.SingleInstance
import com.eventmanager.app.platform.createAppStorage
import com.eventmanager.app.platform.createPlatformContext
import com.eventmanager.app.platform.desktopDataDir
import com.eventmanager.app.ui.AppRoot
import com.eventmanager.app.ui.desktop.DesktopNavigationHooks
import com.eventmanager.app.ui.desktop.DesktopWindowAppearance
import com.eventmanager.app.ui.platform.bootstrapAppLocale
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import kotlin.system.exitProcess

private val isMacOs: Boolean =
    System.getProperty("os.name").orEmpty().contains("mac", ignoreCase = true)

/**
 * Installs a JVM-wide uncaught-exception handler that writes a crash report to
 * ~/Library/Logs/NoctuList/crash-<timestamp>.log (macOS) or %APPDATA%\NoctuList\logs\ (Windows).
 * This is the first line of defense for diagnosing launch failures on new platforms / arch.
 */
private fun installCrashLogger() {
    val logDir: File = when {
        isMacOs -> File(System.getProperty("user.home"), "Library/Logs/NoctuList")
        System.getProperty("os.name").orEmpty().contains("win", ignoreCase = true) ->
            File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "NoctuList/logs")
        else -> File(System.getProperty("user.home"), ".noctulist/logs")
    }
    logDir.mkdirs()

    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss").format(Date())
        val logFile = File(logDir, "crash-$timestamp.log")
        try {
            logFile.bufferedWriter().use { w ->
                w.appendLine("NoctuList crash report — $timestamp")
                w.appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})")
                w.appendLine("JVM: ${System.getProperty("java.vm.name")} ${System.getProperty("java.version")} (${System.getProperty("java.vendor")})")
                w.appendLine("Thread: ${thread.name}")
                w.appendLine()
                w.appendLine(throwable.stackTraceToString())
            }
            System.err.println("NoctuList crashed. Report written to: ${logFile.absolutePath}")
        } catch (_: Exception) {
            // If we can't write the log, at least print to stderr.
            throwable.printStackTrace()
        }
    }
}

/** Bumped from the single-instance listener thread; the window reacts on the UI thread. */
private var showRequest by mutableStateOf(0)

fun main() {
    // Single instance check first, before any Firebase or AWT work.
    val dataDir = desktopDataDir().also { it.mkdirs() }
    val singleInstance = SingleInstance(dataDir)
    val acquired = singleInstance.acquire { showRequest++ }
    if (acquired == SingleInstance.Acquire.ALREADY_RUNNING) {
        if (singleInstance.signalFirst()) exitProcess(0)
        System.err.println("NoctuList is already running but did not answer. Close the other NoctuList and try again.")
        exitProcess(1)
    }
    Runtime.getRuntime().addShutdownHook(Thread { singleInstance.release() })
    // Before the crash logger and before any AWT/Skiko/Firestore class init.
    DesktopWindowAppearance.initBeforeUiToolkit()
    installCrashLogger()
    AppTimeZone.installAsJvmDefault()
    application {
        val platformContext = createPlatformContext()
        com.eventmanager.app.data.security.SecureCredentialStoreHolder.init(
            com.eventmanager.app.data.security.createSecureCredentialStore(platformContext),
        )
        val settingsManager = SettingsManager(platformContext)
        FileAppLogger.init(platformContext, settingsManager)
        bootstrapAppLocale(settingsManager.getLanguage())
        runCatching {
            com.eventmanager.app.data.remote.FirebaseBootstrap.ensureInitialized(
                platformContext,
                com.eventmanager.app.data.remote.FirebaseOptionsReader.fromSettings(settingsManager),
            )
        }
        val windowState = rememberWindowState(width = 1280.dp, height = 900.dp)

        Window(
            onCloseRequest = ::exitApplication,
            title = "NoctuList",
            state = windowState
        ) {
            DesktopWindowThemeEffect(platformContext)
            LaunchedEffect(showRequest) {
                if (showRequest > 0) {
                    if (windowState.isMinimized) windowState.isMinimized = false
                    window.toFront()
                    window.requestFocus()
                }
            }
            LaunchedEffect(Unit) {
                window.minimumSize = java.awt.Dimension(1024, 768)
                DesktopAppIcon.loadAwtIcon()?.let { window.iconImage = it }
            }
            // Native-feeling system menu on macOS only; on Windows/Linux the in-window
            // File/Help bar is redundant with in-app navigation.
            if (isMacOs) {
                MenuBar {
                    Menu("File") {
                        Item(
                            "Preferences",
                            shortcut = androidx.compose.ui.input.key.KeyShortcut(
                                androidx.compose.ui.input.key.Key.Comma,
                                meta = true,
                            ),
                        ) {
                            DesktopNavigationHooks.openSettingsTab?.invoke()
                        }
                        Item(
                            "Quit",
                            shortcut = androidx.compose.ui.input.key.KeyShortcut(
                                androidx.compose.ui.input.key.Key.Q,
                                meta = true,
                            ),
                            onClick = ::exitApplication,
                        )
                    }
                    Menu("Help") {
                        Item("About NoctuList", onClick = { })
                    }
                }
            }
            AppRoot(platformContext = platformContext)
        }
    }
}
