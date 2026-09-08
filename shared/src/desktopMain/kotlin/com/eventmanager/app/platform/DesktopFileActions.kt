package com.eventmanager.app.platform

import java.awt.Desktop
import java.io.File
import java.util.Locale

/**
 * Opens / shares files via OS-native dialogs on desktop.
 * [Desktop.open] alone fails often on Windows when no default app is set;
 * Share was previously aliased to the same call and did nothing useful.
 */
internal object DesktopFileActions {
    private val osName: String
        get() = System.getProperty("os.name").orEmpty().lowercase(Locale.US)

    private val isWindows: Boolean get() = "win" in osName
    private val isMacOs: Boolean get() = "mac" in osName

    fun share(file: File) {
        if (!file.exists()) return
        when {
            isWindows -> shareWindows(file)
            isMacOs -> shareMac(file)
            else -> shareLinux(file)
        }
    }

    fun openWith(file: File) {
        if (!file.exists()) return
        when {
            isWindows -> openWithWindows(file)
            isMacOs -> openDefault(file)
            else -> openDefault(file)
        }
    }

    /**
     * Opens a local file with the OS handler (installer, folder item, etc.).
     *
     * Do not use [Desktop.open] on Windows: AWT ShellExecute converts the path to a
     * `file:` URI and throws `IOException: Failed to open … Unsupported URI content`
     * for downloaded `.msi` / `.exe` updates (especially under `~/.noctulist`).
     */
    fun open(file: File) {
        if (!file.exists()) return
        when {
            isWindows -> openWindows(file)
            isMacOs -> {
                if (runDetached("open", file.absolutePath)) return
                openDefault(file)
            }
            else -> {
                if (file.name.endsWith(".AppImage", ignoreCase = true) &&
                    runDetached(file.absolutePath)
                ) {
                    return
                }
                if (runDetached("xdg-open", file.absolutePath)) return
                openDefault(file)
            }
        }
    }

    private fun shareWindows(file: File) {
        val path = file.absolutePath
        val escaped = path.replace("'", "''")
        val script = """
            ${'$'}path = '$escaped'
            try {
                Start-Process -LiteralPath ${'$'}path -Verb Share
            } catch {
                Start-Process explorer.exe -ArgumentList @('/select,', ${'$'}path)
            }
        """.trimIndent()
        val started = runCatching {
            ProcessBuilder(
                "powershell.exe",
                "-NoProfile",
                "-ExecutionPolicy", "Bypass",
                "-Command", script,
            ).redirectErrorStream(true).start()
            true
        }.getOrDefault(false)
        if (!started) revealInExplorer(file)
    }

    private fun openWithWindows(file: File) {
        val started = runCatching {
            ProcessBuilder(
                "rundll32.exe",
                "shell32.dll,OpenAs_RunDLL",
                file.absolutePath,
            ).redirectErrorStream(true).start()
            true
        }.getOrDefault(false)
        if (!started) openDefault(file)
    }

    private fun openWindows(file: File) {
        val path = file.absolutePath
        val msiexec = windowsSystem32("msiexec.exe")
        val rundll32 = windowsSystem32("rundll32.exe")
        val launched = when {
            path.endsWith(".msi", ignoreCase = true) ->
                runDetached(msiexec, "/i", path) ||
                    runDetached(rundll32, "url.dll,FileProtocolHandler", path)
            path.endsWith(".exe", ignoreCase = true) ->
                runDetached(path) ||
                    runDetached(rundll32, "url.dll,FileProtocolHandler", path)
            else ->
                runDetached(rundll32, "url.dll,FileProtocolHandler", path)
        }
        if (launched) return
        revealInExplorer(file)
    }

    private fun windowsSystem32(exe: String): String {
        val root = System.getenv("SystemRoot") ?: "C:\\Windows"
        return "$root\\System32\\$exe"
    }

    private fun runDetached(vararg command: String): Boolean =
        runCatching {
            ProcessBuilder(*command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start()
            true
        }.getOrDefault(false)

    private fun shareMac(file: File) {
        // Reveal in Finder so the user can use Share from the context menu / toolbar.
        val revealed = runCatching {
            ProcessBuilder("open", "-R", file.absolutePath).start()
            true
        }.getOrDefault(false)
        if (!revealed) openDefault(file)
    }

    private fun shareLinux(file: File) {
        val parent = file.parentFile ?: run {
            openDefault(file)
            return
        }
        val opened = listOf(
            listOf("xdg-open", parent.absolutePath),
            listOf("nautilus", "--select", file.absolutePath),
        ).any { cmd ->
            runCatching {
                ProcessBuilder(cmd).start()
                true
            }.getOrDefault(false)
        }
        if (!opened) openDefault(file)
    }

    private fun revealInExplorer(file: File) {
        runCatching {
            ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start()
        }.onFailure { openDefault(file) }
    }

    private fun openDefault(file: File) {
        runCatching {
            if (!Desktop.isDesktopSupported()) return
            val desktop = Desktop.getDesktop()
            when {
                desktop.isSupported(Desktop.Action.OPEN) -> desktop.open(file)
                desktop.isSupported(Desktop.Action.BROWSE) ->
                    file.parentFile?.toURI()?.let { desktop.browse(it) }
            }
        }
    }
}
