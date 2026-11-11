package com.eventmanager.app.setup

import java.io.File

/**
 * Node.js and the JavaScript entry points of npm and the Firebase CLI. Commands always start
 * with [node] followed by a script, so Windows never goes through `.cmd` shims (cmd.exe quoting).
 */
data class ToolPaths(val node: File, val npm: File, val firebase: File, val binDir: File) {
    fun npmCommand(): List<String> = listOf(node.path, npm.path)
    fun firebaseCommand(): List<String> = listOf(node.path, firebase.path)
}

object ToolPathsResolver {
    private const val FIREBASE_JS = "node_modules/firebase-tools/lib/bin/firebase.js"
    private const val NPM_JS = "node_modules/npm/bin/npm-cli.js"

    private fun isWindows(os: String) = os.lowercase().startsWith("windows")

    /** Tools downloaded by the app into [toolsDir]. */
    fun bundled(toolsDir: File, os: String): ToolPaths {
        val nodeDir = toolsDir.resolve("node")
        val globalDir = toolsDir.resolve("npm-global")
        return if (isWindows(os)) {
            ToolPaths(
                node = nodeDir.resolve("node.exe"),
                npm = nodeDir.resolve(NPM_JS),
                firebase = globalDir.resolve(FIREBASE_JS),
                binDir = nodeDir,
            )
        } else {
            val bin = nodeDir.resolve("bin")
            ToolPaths(
                node = bin.resolve("node"),
                npm = nodeDir.resolve("lib/$NPM_JS"),
                firebase = globalDir.resolve("lib/$FIREBASE_JS"),
                binDir = bin,
            )
        }
    }

    /** Tools the admin already installed, found on PATH and followed to their scripts. */
    fun fromPath(pathEnv: String, os: String, exists: (File) -> Boolean): ToolPaths? {
        val win = isWindows(os)
        val dirs = pathEnv.split(if (win) ';' else ':').filter { it.isNotBlank() }.map(::File)
        fun find(name: String): File? = dirs.map { it.resolve(name) }.firstOrNull(exists)
        val node = find(if (win) "node.exe" else "node") ?: return null
        val (npm, firebase) = if (win) {
            val shim = find("firebase.cmd") ?: return null
            node.parentFile.resolve(NPM_JS) to shim.parentFile.resolve(FIREBASE_JS)
        } else {
            val npmLink = find("npm") ?: return null
            val fbLink = find("firebase") ?: return null
            runCatching { npmLink.canonicalFile }.getOrNull() to runCatching { fbLink.canonicalFile }.getOrNull()
        }
        if (npm == null || firebase == null) return null
        if (!npm.name.endsWith(".js") || !firebase.name.endsWith(".js")) return null
        if (!exists(npm) || !exists(firebase)) return null
        return ToolPaths(node, npm, firebase, node.parentFile)
    }
}
