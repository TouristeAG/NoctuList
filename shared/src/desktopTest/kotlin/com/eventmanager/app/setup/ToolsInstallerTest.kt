package com.eventmanager.app.setup

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ToolsInstallerTest {
    private val base: File = Files.createTempDirectory("tools test").toFile()
    private val appData = base.resolve("app data")
    private val toolsDir = appData.resolve("tools")
    private val archiveBytes = "fake node archive".encodeToByteArray()
    private val version = ToolsManifest.NODE_VERSION
    private val macArm = "node-$version-darwin-arm64.tar.gz"
    private val dist = "https://nodejs.org/dist/$version/"

    @AfterTest
    fun cleanUp() {
        base.deleteRecursively()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun http(checksum: String = sha(archiveBytes), failDownload: Boolean = false, onDownload: suspend () -> Unit = {}) = object : ToolsHttp {
        val downloads = mutableListOf<String>()
        override suspend fun text(url: String): String {
            assertTrue(url.startsWith(dist))
            return "$checksum  $macArm\n0000  node-$version-win-x64.zip\n"
        }

        override suspend fun download(url: String, target: File, onProgress: (Float) -> Unit) {
            assertTrue(url.startsWith(dist))
            downloads += url
            onDownload()
            if (failDownload) throw java.io.IOException("connection reset")
            target.parentFile.mkdirs()
            target.writeBytes(archiveBytes)
            onProgress(1f)
        }
    }

    private var nodeAnswer = 0 to ManifestVersions.node
    private var firebaseAnswer = 0 to ManifestVersions.firebase

    /** Pretends to be tar, npm, node -v and firebase --version. */
    private fun runner() = FakeProcessRunner { cmd ->
        when {
            cmd.first() == "tar" -> {
                val dest = File(cmd[cmd.indexOf("-C") + 1])
                dest.resolve("node-$version-darwin-arm64/bin").mkdirs()
                dest.resolve("node-$version-darwin-arm64/bin/node").writeText("node")
                dest.resolve("node-$version-darwin-arm64/lib/node_modules/npm/bin").mkdirs()
                dest.resolve("node-$version-darwin-arm64/lib/node_modules/npm/bin/npm-cli.js").writeText("npm")
                0 to ""
            }
            cmd.any { it.startsWith("firebase-tools") } -> {
                val prefix = File(cmd[cmd.indexOf("--prefix") + 1])
                prefix.resolve("lib/node_modules/firebase-tools/lib/bin").mkdirs()
                prefix.resolve("lib/node_modules/firebase-tools/lib/bin/firebase.js").writeText("fb")
                0 to "added 600 packages"
            }
            cmd.last() == "-v" -> nodeAnswer
            cmd.last() == "--version" -> firebaseAnswer
            else -> 0 to ""
        }
    }

    private fun installer(
        r: FakeProcessRunner = runner(),
        h: ToolsHttp = http(),
        free: Long = 10L * 1024 * 1024 * 1024,
    ) = ToolsInstaller(
        toolsDir, r, h, os = "Mac OS X", arch = "aarch64",
        environment = SetupEnvironment(appData, null),
        basePath = "",
        freeBytes = { free },
        sleep = {},
    )

    /** Lays the files down as an install would, without running it. */
    private fun fakeInstalledFiles() {
        val p = ToolPathsResolver.bundled(toolsDir, "Mac OS X")
        listOf(p.node, p.npm, p.firebase).forEach { it.parentFile.mkdirs(); it.writeText("x") }
    }

    @Test
    fun installsVerifiedNodeThenFirebase(): Unit = runBlocking {
        val r = runner()
        val paths = installer(r).install { _, _ -> }
        assertTrue(paths.node.isFile)
        assertTrue(paths.firebase.isFile)
        val tar = r.calls.first { it.first() == "tar" }
        assertEquals("-xf", tar[1])
        assertTrue(tar[2].endsWith(macArm))
        val npm = r.calls.first { it.any { a -> a.startsWith("firebase-tools") } }
        assertEquals(listOf(paths.node.path, paths.npm.path), npm.take(2))
        assertFalse(toolsDir.listFiles()!!.any { it.name.startsWith(".tmp") })
    }

    @Test
    fun installPinsFirebaseToolsVersion(): Unit = runBlocking {
        val r = runner()
        installer(r).install { _, _ -> }
        val npm = r.calls.first { it.any { a -> a.startsWith("firebase-tools") } }
        assertTrue("firebase-tools@15.36.0" in npm)
    }

    @Test
    fun wrongChecksumLeavesNothingInstalled(): Unit = runBlocking {
        assertFailsWith<SecurityException> { installer(h = http(checksum = "0".repeat(64))).install { _, _ -> } }
        assertFalse(toolsDir.resolve("node").exists())
        assertIs<ToolsStatus.Missing>(installer().status())
    }

    @Test
    fun interruptedDownloadLeavesNothingInstalled(): Unit = runBlocking {
        assertFailsWith<java.io.IOException> { installer(h = http(failDownload = true)).install { _, _ -> } }
        assertIs<ToolsStatus.Missing>(installer().status())
    }

    @Test
    fun archiveNamePerPlatform() {
        assertEquals("node-v22.1.0-darwin-arm64.tar.gz", ToolsInstaller.archiveName("v22.1.0", "Mac OS X", "aarch64"))
        assertEquals("node-v22.1.0-darwin-x64.tar.gz", ToolsInstaller.archiveName("v22.1.0", "Mac OS X", "x86_64"))
        assertEquals("node-v22.1.0-linux-x64.tar.xz", ToolsInstaller.archiveName("v22.1.0", "Linux", "amd64"))
        assertEquals("node-v22.1.0-win-x64.zip", ToolsInstaller.archiveName("v22.1.0", "Windows 11", "amd64"))
    }

    @Test
    fun statusIsReadyOnlyWhenBothToolsAnswerManifestVersions(): Unit = runBlocking {
        fakeInstalledFiles()
        val r = runner()
        val status = installer(r).status()
        assertIs<ToolsStatus.Ready>(status)
        assertEquals(toolsDir.resolve("node/bin/node"), status.paths.node)
        val fb = r.calls.first { it.last() == "--version" }
        assertEquals(listOf(status.paths.node.path, status.paths.firebase.path, "--version"), fb)
        assertTrue(r.limitsSeen.all { it == CommandLimits(20_000, 20_000) })
        assertTrue(r.envs.all { it["XDG_CONFIG_HOME"] != null })
    }

    @Test
    fun statusIgnoresExtraLinesFromFirebase(): Unit = runBlocking {
        fakeInstalledFiles()
        firebaseAnswer = 0 to "Update available\n${ManifestVersions.firebase}\n"
        assertIs<ToolsStatus.Ready>(installer().status())
    }

    @Test
    fun statusIsBrokenWhenFilesExistButNodeFails(): Unit = runBlocking {
        fakeInstalledFiles()
        nodeAnswer = 1 to ""
        assertIs<ToolsStatus.Broken>(installer().status())
    }

    @Test
    fun statusIsBrokenWhenFirebaseFails(): Unit = runBlocking {
        fakeInstalledFiles()
        firebaseAnswer = 1 to "Error"
        assertIs<ToolsStatus.Broken>(installer().status())
    }

    @Test
    fun statusIsBrokenWhenAFileIsMissing(): Unit = runBlocking {
        fakeInstalledFiles()
        ToolPathsResolver.bundled(toolsDir, "Mac OS X").firebase.delete()
        assertIs<ToolsStatus.Broken>(installer().status())
    }

    @Test
    fun statusIsMissingWhenNothingInstalled(): Unit = runBlocking {
        assertIs<ToolsStatus.Missing>(installer().status())
    }

    @Test
    fun statusIgnoresSystemNodeOnPath(): Unit = runBlocking {
        val bin = base.resolve("usr bin").apply { mkdirs() }
        bin.resolve("node").writeText("node")
        bin.resolve("firebase").writeText("fb")
        val i = ToolsInstaller(
            toolsDir, runner(), http(), "Mac OS X", "aarch64",
            environment = SetupEnvironment(appData, null), basePath = bin.path, freeBytes = { Long.MAX_VALUE }, sleep = {},
        )
        assertIs<ToolsStatus.Missing>(i.status())
    }

    @Test
    fun statusIsOutdatedOnOtherFirebaseVersion(): Unit = runBlocking {
        fakeInstalledFiles()
        firebaseAnswer = 0 to "15.10.0"
        val s = installer().status()
        assertIs<ToolsStatus.Outdated>(s)
        assertEquals("15.10.0", s.firebase)
        assertEquals(ManifestVersions.node, s.node)
    }

    @Test
    fun statusIsOutdatedOnOtherNodeVersion(): Unit = runBlocking {
        fakeInstalledFiles()
        nodeAnswer = 0 to "v20.1.0"
        assertIs<ToolsStatus.Outdated>(installer().status())
    }

    @Test
    fun installedToolsAreReady(): Unit = runBlocking {
        installer().install { _, _ -> }
        assertIs<ToolsStatus.Ready>(installer().status())
    }

    @Test
    fun installFailsEarlyWhenDiskIsFull(): Unit = runBlocking {
        val h = http()
        val e = assertFailsWith<DiskSpaceException> { installer(h = h, free = 100L * 1024 * 1024).install { _, _ -> } }
        assertEquals(ToolsManifest.MIN_FREE_BYTES, e.neededBytes)
        assertTrue(e.message.orEmpty().contains("600"))
        assertTrue(h.downloads.isEmpty())
        assertFalse(toolsDir.resolve("node").exists())
    }

    @Test
    fun cancelDuringInstallLeavesNoHalfFolder(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val never = CompletableDeferred<Unit>()
        val h = http(onDownload = { started.complete(Unit); never.await() })
        val job = async(start = CoroutineStart.UNDISPATCHED) { installer(h = h).install { _, _ -> } }
        started.await()
        job.cancelAndJoin()
        assertFalse(toolsDir.resolve("node").exists())
        assertFalse(toolsDir.listFiles().orEmpty().any { it.name.startsWith(".tmp") })
    }

    @Test
    fun reinstallWithFullDiskKeepsOldTools(): Unit = runBlocking {
        fakeInstalledFiles()
        val p = ToolPathsResolver.bundled(toolsDir, "Mac OS X")
        assertFailsWith<DiskSpaceException> { installer(free = 1L).reinstall { _, _ -> } }
        assertTrue(p.node.isFile)
        assertTrue(p.firebase.isFile)
    }

    @Test
    fun reinstallReplacesBrokenTools(): Unit = runBlocking {
        fakeInstalledFiles()
        val old = toolsDir.resolve("node/bin/leftover").apply { writeText("old") }
        installer().reinstall { _, _ -> }
        assertFalse(old.exists())
        assertIs<ToolsStatus.Ready>(installer().status())
    }

    @Test
    fun removeDeletesTheToolsFolder(): Unit = runBlocking {
        installer().install { _, _ -> }
        installer().remove()
        assertFalse(toolsDir.exists())
    }
}

private object ManifestVersions {
    val node = ToolsManifest.NODE_VERSION
    val firebase = ToolsManifest.FIREBASE_TOOLS_VERSION
}
