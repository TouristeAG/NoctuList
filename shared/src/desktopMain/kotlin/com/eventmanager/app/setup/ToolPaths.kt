package com.eventmanager.app.setup

import java.io.File

/**
 * Node.js and the JavaScript entry points of npm and the Firebase CLI. Commands always start
 * with [node] followed by a script, so Windows never goes through `.cmd` shims (cmd.exe quoting).
 */
data class ToolPaths(val node: File, val npm: File, val firebase: File, val binDir: File) {
    fun nodeCommand(): List<String> = listOf(node.path, "-v")
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
}
