package com.eventmanager.app.platform

import java.io.File

private var desktopContext: PlatformContext? = null

actual class PlatformContext internal constructor(
    val dataDir: File
)

fun desktopDataDir(): File = File(System.getProperty("user.home"), ".noctulist")

/**
 * Java Preferences node holding the app settings. A development run can pass
 * `-Dnoctulist.prefsNode=...` to keep its settings apart from the installed app
 * (macOS stores Preferences per user, whatever `user.home` says).
 */
fun desktopPreferencesNode(): String =
    System.getProperty("noctulist.prefsNode")?.trim()?.takeIf { it.isNotEmpty() }
        ?: "com/eventmanager/app/noctulist"

actual fun createPlatformContext(): PlatformContext {
    return desktopContext ?: run {
        val dir = desktopDataDir().also { it.mkdirs() }
        PlatformContext(dir).also { desktopContext = it }
    }
}

actual val PlatformContext.appDataDir: File
    get() = dataDir

actual val PlatformContext.isDesktop: Boolean
    get() = true
