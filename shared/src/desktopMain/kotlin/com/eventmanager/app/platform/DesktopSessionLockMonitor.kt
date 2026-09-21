package com.eventmanager.app.platform

import java.awt.Desktop
import java.awt.desktop.ScreenSleepEvent
import java.awt.desktop.ScreenSleepListener
import java.awt.desktop.SystemSleepEvent
import java.awt.desktop.SystemSleepListener
import java.awt.desktop.UserSessionEvent
import java.awt.desktop.UserSessionListener
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Ends the admin session when the OS locks or the display/system sleeps.
 * Uses java.awt.Desktop app-event listeners when the platform supports them.
 * On Linux, falls back to a best-effort `gdbus` monitor of logind if Desktop APIs are unavailable.
 */
internal object DesktopSessionLockMonitor {
    @Volatile
    private var installed = false

    fun install(host: DesktopAdminSessionHost) {
        if (installed) return
        val desktopListeners = installDesktopListeners(host)
        if (!desktopListeners && isLinux()) {
            installLogin1Monitor(host)
        }
        installed = true
    }

    private fun installDesktopListeners(host: DesktopAdminSessionHost): Boolean {
        if (!Desktop.isDesktopSupported()) return false
        val desktop = Desktop.getDesktop()
        var registered = false
        try {
            if (desktop.isSupported(Desktop.Action.APP_EVENT_USER_SESSION)) {
                desktop.addAppEventListener(object : UserSessionListener {
                    override fun userSessionDeactivated(event: UserSessionEvent) {
                        if (event.reason == UserSessionEvent.Reason.LOCK) {
                            host.adminSessionWatchdog.onDeviceLocked()
                            host.requestSecureLogout()
                        }
                    }

                    override fun userSessionActivated(event: UserSessionEvent) = Unit
                })
                registered = true
            }
        } catch (_: Exception) { }

        try {
            if (desktop.isSupported(Desktop.Action.APP_EVENT_SCREEN_SLEEP)) {
                desktop.addAppEventListener(object : ScreenSleepListener {
                    override fun screenAboutToSleep(event: ScreenSleepEvent) {
                        host.adminSessionWatchdog.onDisplayTurnedOff()
                        host.requestSecureLogout()
                    }

                    override fun screenAwoke(event: ScreenSleepEvent) = Unit
                })
                registered = true
            }
        } catch (_: Exception) { }

        try {
            if (desktop.isSupported(Desktop.Action.APP_EVENT_SYSTEM_SLEEP)) {
                desktop.addAppEventListener(object : SystemSleepListener {
                    override fun systemAboutToSleep(event: SystemSleepEvent) {
                        host.adminSessionWatchdog.onDisplayTurnedOff()
                        host.requestSecureLogout()
                    }

                    override fun systemAwoke(event: SystemSleepEvent) = Unit
                })
                registered = true
            }
        } catch (_: Exception) { }

        return registered
    }

    private fun installLogin1Monitor(host: DesktopAdminSessionHost) {
        val process = startLogin1Process() ?: return
        val thread = Thread({
            try {
                BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                    while (!Thread.currentThread().isInterrupted) {
                        val line = reader.readLine() ?: break
                        handleLogin1Line(line, host)
                    }
                }
            } catch (_: Exception) {
            } finally {
                process.destroy()
            }
        }, "noctulist-login1-monitor")
        thread.isDaemon = true
        thread.start()
    }

    private fun startLogin1Process(): Process? {
        val commands = listOf(
            arrayOf("gdbus", "monitor", "--system", "--dest", "org.freedesktop.login1"),
            arrayOf("dbus-monitor", "--system", "type='signal',interface='org.freedesktop.login1.Manager'"),
        )
        for (command in commands) {
            try {
                return ProcessBuilder(*command)
                    .redirectErrorStream(true)
                    .start()
            } catch (_: Exception) { }
        }
        return null
    }

    private fun handleLogin1Line(line: String, host: DesktopAdminSessionHost) {
        val compact = line.replace(" ", "")
        val locked = compact.contains("Lock(") ||
            compact.contains(".Lock") ||
            compact.contains("LockedHint") && compact.contains("true")
        val sleeping = compact.contains("PrepareForSleep") &&
            (compact.contains("true") || compact.contains("bTRUE") || compact.endsWith("true)"))
        when {
            sleeping -> {
                host.adminSessionWatchdog.onDisplayTurnedOff()
                host.requestSecureLogout()
            }
            locked -> {
                host.adminSessionWatchdog.onDeviceLocked()
                host.requestSecureLogout()
            }
        }
    }

    private fun isLinux(): Boolean {
        val os = System.getProperty("os.name").orEmpty().lowercase()
        return os.contains("linux") || os.contains("unix")
    }
}
