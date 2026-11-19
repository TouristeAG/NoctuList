package com.eventmanager.app.setup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.UUID

interface ToolsHttp {
    suspend fun text(url: String): String
    suspend fun download(url: String, target: File, onProgress: (Float) -> Unit)
}

class JdkToolsHttp : ToolsHttp {
    private val client = HttpClient.newBuilder()
        .also { SystemProxy.detect() }
        .proxy(ProxySelector.getDefault())
        .followRedirects(HttpClient.Redirect.NORMAL)
        .connectTimeout(Duration.ofSeconds(20))
        .build()

    override suspend fun text(url: String): String = withContext(Dispatchers.IO) {
        val res = client.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) throw IOException("HTTP ${res.statusCode()}")
        res.body()
    }

    override suspend fun download(url: String, target: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val res = client.send(HttpRequest.newBuilder(URI(url)).GET().build(), HttpResponse.BodyHandlers.ofInputStream())
        if (res.statusCode() != 200) throw IOException("HTTP ${res.statusCode()}")
        val total = res.headers().firstValueAsLong("content-length").orElse(-1L)
        target.parentFile.mkdirs()
        res.body().use { input ->
            target.outputStream().use { out ->
                val buffer = ByteArray(64 * 1024)
                var read = 0L
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    read += n
                    if (total > 0) onProgress(read.toFloat() / total)
                }
            }
        }
    }
}

/** Not enough free space for the tools; [neededBytes] is what the install needs. */
class DiskSpaceException(val neededBytes: Long) :
    IOException("Not enough free disk space: ${neededBytes / (1024 * 1024)} MB needed")

/**
 * Installs the pinned Node.js and Firebase CLI into [toolsDir], on request only, and judges them by running
 * them. A half-finished install is never visible: extraction happens in a temp folder renamed at the end.
 */
class ToolsInstaller(
    private val toolsDir: File,
    private val runner: ProcessRunner,
    private val http: ToolsHttp,
    private val os: String,
    private val arch: String,
    private val environment: SetupEnvironment,
    private val basePath: String = System.getenv("PATH").orEmpty(),
    private val freeBytes: (File) -> Long = { it.usableSpace },
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) : SetupTools {
    private val bundled get() = ToolPathsResolver.bundled(toolsDir, os)

    override suspend fun status(): ToolsStatus {
        val paths = bundled
        val present = listOf(paths.node, paths.npm, paths.firebase).map { it.isFile }
        if (present.none { it }) return ToolsStatus.Missing
        if (!present.all { it }) return ToolsStatus.Broken("some tool files are missing")
        val node = answer(paths.nodeCommand(), paths) ?: return ToolsStatus.Broken("node does not start")
        val firebase = answer(paths.firebaseCommand() + "--version", paths) ?: return ToolsStatus.Broken("firebase does not start")
        return if (node == ToolsManifest.NODE_VERSION && firebase == ToolsManifest.FIREBASE_TOOLS_VERSION) {
            ToolsStatus.Ready(paths)
        } else {
            ToolsStatus.Outdated(node, firebase)
        }
    }

    /** Runs a command and returns its last non-empty line, or null when it failed. */
    private suspend fun answer(cmd: List<String>, paths: ToolPaths): String? {
        val lines = mutableListOf<String>()
        val code = try {
            runner.run(cmd, environment.workDir, environment.childEnv(paths.binDir, basePath), null, CommandLimits.READ) { lines += it }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return null
        }
        return if (code == 0) lines.map { it.trim() }.lastOrNull { it.isNotEmpty() } else null
    }

    override suspend fun reinstall(onProgress: (Float, String) -> Unit): ToolPaths {
        FileOps.deleteTree(toolsDir.resolve("node"), sleep)
        FileOps.deleteTree(toolsDir.resolve("npm-global"), sleep)
        return install(onProgress)
    }

    override suspend fun install(onProgress: (Float, String) -> Unit): ToolPaths {
        toolsDir.mkdirs()
        if (freeBytes(nearestExisting(toolsDir)) < ToolsManifest.MIN_FREE_BYTES) throw DiskSpaceException(ToolsManifest.MIN_FREE_BYTES)
        onProgress(0f, "node")
        val sums = http.text("$NODE_DIST/SHASUMS256.txt")
        val name = archiveName(ToolsManifest.NODE_VERSION, os, arch)
        val expected = sums.lineSequence()
            .map { it.trim().split(Regex("\\s+")) }
            .firstOrNull { it.size == 2 && it[1] == name }?.get(0)
            ?: throw IOException("checksum not found for $name")
        val downloads = toolsDir.resolve(".download").apply { mkdirs() }
        val archive = downloads.resolve(name)
        val tmp = toolsDir.resolve(".tmp-${UUID.randomUUID()}")
        try {
            http.download("$NODE_DIST/$name", archive) { onProgress(it * 0.7f, "node") }
            if (sha256(archive) != expected.lowercase()) throw SecurityException("Node.js download checksum mismatch")
            tmp.mkdirs()
            withContext(Dispatchers.IO) { ArchiveExtractor.extract(archive, tmp, os, runner) }
            val extracted = tmp.listFiles()?.singleOrNull { it.isDirectory } ?: throw IOException("unexpected Node.js archive layout")
            val nodeDir = toolsDir.resolve("node")
            FileOps.deleteTree(nodeDir, sleep)
            FileOps.move(extracted, nodeDir, sleep)
        } finally {
            tmp.deleteRecursively()
            downloads.deleteRecursively()
        }
        onProgress(0.75f, "firebase")
        val paths = bundled
        val prefix = toolsDir.resolve("npm-global")
        val code = runner.run(
            paths.npmCommand() + listOf(
                "install", "--global", "--prefix", prefix.path, "--no-audit", "--no-fund",
                "firebase-tools@${ToolsManifest.FIREBASE_TOOLS_VERSION}",
            ),
            environment.workDir,
            environment.childEnv(paths.binDir, basePath),
            null,
            CommandLimits.NPM,
        ) {}
        if (code != 0 || !paths.firebase.isFile) {
            prefix.deleteRecursively()
            throw IOException("could not install the Firebase CLI")
        }
        onProgress(1f, "done")
        return paths
    }

    fun remove() {
        toolsDir.deleteRecursively()
    }

    private fun nearestExisting(file: File): File {
        var f: File? = file.absoluteFile
        while (f != null && !f.exists()) f = f.parentFile
        return f ?: file
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val NODE_DIST = "https://nodejs.org/dist/${ToolsManifest.NODE_VERSION}"

        fun archiveName(version: String, os: String, arch: String): String {
            val o = os.lowercase()
            val cpu = if (arch.lowercase() in setOf("aarch64", "arm64")) "arm64" else "x64"
            return when {
                o.startsWith("windows") -> "node-$version-win-$cpu.zip"
                o.startsWith("mac") || o.contains("darwin") -> "node-$version-darwin-$cpu.tar.gz"
                else -> "node-$version-linux-$cpu.tar.xz"
            }
        }
    }
}
