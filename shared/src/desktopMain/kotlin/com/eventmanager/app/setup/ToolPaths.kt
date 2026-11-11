package com.eventmanager.app.setup

import java.io.File

data class ToolPaths(val node: File, val npm: File, val firebase: File, val binDir: File)

object ToolPathsResolver {
    private fun isWindows(os: String) = os.lowercase().startsWith("windows")

    /** Tools downloaded by the app into [toolsDir]. */
    fun bundled(toolsDir: File, os: String): ToolPaths {
        val nodeDir = toolsDir.resolve("node")
        val globalDir = toolsDir.resolve("npm-global")
        return if (isWindows(os)) {
            ToolPaths(
                node = nodeDir.resolve("node.exe"),
                npm = nodeDir.resolve("npm.cmd"),
                firebase = globalDir.resolve("firebase.cmd"),
                binDir = nodeDir,
            )
        } else {
            val bin = nodeDir.resolve("bin")
            ToolPaths(
                node = bin.resolve("node"),
                npm = bin.resolve("npm"),
                firebase = globalDir.resolve("bin/firebase"),
                binDir = bin,
            )
        }
    }

    /** Tools the admin already installed, found on PATH. */
    fun fromPath(pathEnv: String, os: String, exists: (File) -> Boolean): ToolPaths? {
        val win = isWindows(os)
        val dirs = pathEnv.split(if (win) ';' else ':').filter { it.isNotBlank() }.map(::File)
        fun find(name: String): File? = dirs.map { it.resolve(name) }.firstOrNull(exists)
        val node = find(if (win) "node.exe" else "node") ?: return null
        val npm = find(if (win) "npm.cmd" else "npm") ?: return null
        val firebase = find(if (win) "firebase.cmd" else "firebase") ?: return null
        return ToolPaths(node, npm, firebase, node.parentFile)
    }
}
