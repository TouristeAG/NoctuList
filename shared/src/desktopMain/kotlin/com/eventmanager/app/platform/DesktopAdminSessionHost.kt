package com.eventmanager.app.platform

import com.eventmanager.app.ui.AdminSessionHost
import com.eventmanager.app.ui.AdminSessionWatchdog

internal object DesktopAdminSessionHost : AdminSessionHost {
    override val adminSessionWatchdog = AdminSessionWatchdog()
    override var adminSessionAutoLogout: (() -> Unit)? = null

    @Volatile
    private var started = false

    fun ensureStarted() {
        if (started) return
        synchronized(this) {
            if (started) return
            DesktopUserActivityMonitor.install(adminSessionWatchdog)
            DesktopSessionLockMonitor.install(this)
            started = true
        }
    }

    fun requestSecureLogout() {
        java.awt.EventQueue.invokeLater {
            if (!adminSessionWatchdog.shouldEndSession()) return@invokeLater
            val logout = adminSessionAutoLogout ?: return@invokeLater
            adminSessionWatchdog.consumeShouldEndSession()
            logout.invoke()
        }
    }
}
