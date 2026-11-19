package com.eventmanager.app.setup

import java.io.File

/** Proxy the machine uses to reach the internet, when there is one. */
data class ProxyInfo(val host: String, val port: Int)

/**
 * The one place that decides where setup commands run and what they see: a writable work folder
 * (the app itself may be started from a read-only "/"), the app's own Firebase sign-in folder, and logs.
 */
class SetupEnvironment(appData: File, private val proxy: ProxyInfo?) {
    private val root = appData.resolve("setup")
    val workDir: File = root.resolve("work").also { it.mkdirs() }
    val configDir: File = root.resolve("config").also { it.mkdirs() }
    val logDir: File = appData.resolve("logs").resolve("setup").also { it.mkdirs() }

    fun childEnv(binDir: File, basePath: String): Map<String, String> {
        val env = mutableMapOf(
            "PATH" to listOf(binDir.path, basePath).filter { it.isNotBlank() }.joinToString(File.pathSeparator),
            "XDG_CONFIG_HOME" to configDir.path,
            "NO_UPDATE_NOTIFIER" to "1",
            "NO_COLOR" to "1",
            "FORCE_COLOR" to "0",
        )
        if (proxy != null) {
            val url = "http://${proxy.host}:${proxy.port}"
            env["HTTPS_PROXY"] = url
            env["HTTP_PROXY"] = url
        }
        return env
    }
}
