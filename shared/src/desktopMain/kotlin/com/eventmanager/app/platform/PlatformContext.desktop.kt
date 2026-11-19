package com.eventmanager.app.platform

import java.io.File

private var desktopContext: PlatformContext? = null

actual class PlatformContext internal constructor(
    val dataDir: File
)

fun desktopDataDir(): File = File(System.getProperty("user.home"), ".noctulist")

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
