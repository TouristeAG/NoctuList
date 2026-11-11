package com.eventmanager.app.setup

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ToolsInstallerTest {
    private val base: File = Files.createTempDirectory("tools test").toFile()
    private val toolsDir = base.resolve("app data/tools")
    private val archiveBytes = "fake node archive".encodeToByteArray()
    private val version = "v22.11.0"
    private val macArm = "node-$version-darwin-arm64.tar.gz"

    @AfterTest
    fun cleanUp() {
        base.deleteRecursively()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun http(checksum: String = sha(archiveBytes), failDownload: Boolean = false) = object : ToolsHttp {
        override suspend fun text(url: String): String {
            assertTrue(url.startsWith("https://nodejs.org/dist/latest-v22.x/"))
            return "$checksum  $macArm\n0000  node-$version-win-x64.zip\n"
        }

        override suspend fun download(url: String, target: File, onProgress: (Float) -> Unit) {
            assertTrue(url.startsWith("https://nodejs.org/dist/latest-v22.x/"))
            if (failDownload) throw java.io.IOException("connection reset")
            target.parentFile.mkdirs()
            target.writeBytes(archiveBytes)
            onProgress(1f)
        }
    }

    /** Pretends to be tar and npm: tar creates the extracted folder, npm creates the firebase binary. */
    private fun runner() = FakeProcessRunner { cmd ->
        when {
            cmd.first() == "tar" -> {
                val dest = File(cmd[cmd.indexOf("-C") + 1])
                dest.resolve("node-$version-darwin-arm64/bin").mkdirs()
                dest.resolve("node-$version-darwin-arm64/bin/node").writeText("node")
                dest.resolve("node-$version-darwin-arm64/bin/npm").writeText("npm")
                0 to ""
            }
            cmd.contains("firebase-tools") -> {
                val prefix = File(cmd[cmd.indexOf("--prefix") + 1])
                prefix.resolve("bin").mkdirs()
                prefix.resolve("bin/firebase").writeText("fb")
                0 to "added 600 packages"
            }
            cmd.last() == "-v" -> 0 to "v22.11.0"
            else -> 0 to ""
        }
    }

    private fun installer(r: FakeProcessRunner = runner(), h: ToolsHttp = http()) =
        ToolsInstaller(toolsDir, r, h, os = "Mac OS X", arch = "aarch64")

    @Test
    fun installsVerifiedNodeThenFirebase(): Unit = runBlocking {
        val r = runner()
        val paths = installer(r).install { _, _ -> }
        assertTrue(paths.node.isFile)
        assertTrue(paths.firebase.isFile)
        val tar = r.calls.first { it.first() == "tar" }
        assertEquals("-xf", tar[1])
        assertTrue(tar[2].endsWith(macArm))
        val npm = r.calls.first { it.contains("firebase-tools") }
        assertEquals(paths.npm.path, npm.first())
        assertFalse(toolsDir.listFiles()!!.any { it.name.startsWith(".tmp") })
    }

    @Test
    fun wrongChecksumLeavesNothingInstalled(): Unit = runBlocking {
        assertFailsWith<SecurityException> { installer(h = http(checksum = "0".repeat(64))).install { _, _ -> } }
        assertFalse(toolsDir.resolve("node").exists())
        assertNull(installer().detect(pathEnv = ""))
    }

    @Test
    fun interruptedDownloadLeavesNothingInstalled(): Unit = runBlocking {
        assertFailsWith<java.io.IOException> { installer(h = http(failDownload = true)).install { _, _ -> } }
        assertNull(installer().detect(pathEnv = ""))
    }

    @Test
    fun archiveNamePerPlatform() {
        assertEquals("node-v22.1.0-darwin-arm64.tar.gz", ToolsInstaller.archiveName("v22.1.0", "Mac OS X", "aarch64"))
        assertEquals("node-v22.1.0-darwin-x64.tar.gz", ToolsInstaller.archiveName("v22.1.0", "Mac OS X", "x86_64"))
        assertEquals("node-v22.1.0-linux-x64.tar.xz", ToolsInstaller.archiveName("v22.1.0", "Linux", "amd64"))
        assertEquals("node-v22.1.0-win-x64.zip", ToolsInstaller.archiveName("v22.1.0", "Windows 11", "amd64"))
    }

    @Test
    fun detectsBundledInstall(): Unit = runBlocking {
        installer().install { _, _ -> }
        assertNotNull(installer().detect(pathEnv = ""))
    }

    @Test
    fun reusesNode22OnPathButNotNode20(): Unit = runBlocking {
        val bin = base.resolve("usr bin").apply { mkdirs() }
        listOf("node", "npm", "firebase").forEach { bin.resolve(it).writeText(it) }
        val ok = FakeProcessRunner { 0 to "v22.11.0" }
        assertEquals(bin.resolve("firebase"), ToolsInstaller(toolsDir, ok, http(), "Mac OS X", "aarch64").detect(bin.path)?.firebase)
        val old = FakeProcessRunner { 0 to "v20.1.0" }
        assertNull(ToolsInstaller(toolsDir, old, http(), "Mac OS X", "aarch64").detect(bin.path))
    }

    @Test
    fun removeDeletesTheToolsFolder(): Unit = runBlocking {
        installer().install { _, _ -> }
        installer().remove()
        assertFalse(toolsDir.exists())
    }
}
