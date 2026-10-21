package com.eventmanager.app.platform

import com.eventmanager.app.ui.desktop.LinuxUiScale

/**
 * Linux process guards that must run before AWT, Skiko, or Firestore/gRPC load.
 *
 * Netty's epoll and OpenSSL JNI libraries SIGSEGV on some Ubuntu installs the first
 * time GitLive opens a Firestore channel. Forcing the pure-Java transports has to
 * happen before those classes initialize.
 */
internal object LinuxDesktopRuntime {
    fun install() {
        if (!System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true)) return
        disableNativeNetty()
        LinuxUiScale.install()
    }

    private fun disableNativeNetty() {
        listOf(
            "io.netty.transport.noNative",
            "io.grpc.netty.shaded.io.netty.transport.noNative",
            "io.netty.handler.ssl.noOpenSsl",
            "io.grpc.netty.shaded.io.netty.handler.ssl.noOpenSsl",
        ).forEach { key ->
            if (System.getProperty(key).isNullOrBlank()) {
                System.setProperty(key, "true")
            }
        }
    }
}
