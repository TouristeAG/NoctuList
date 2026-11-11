package com.eventmanager.app.setup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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

/**
 * Gets Node.js 22 and the Firebase CLI for the admin, on request only. Everything lands in
 * [toolsDir]; a half-finished install is never visible because extraction happens in a temp
 * folder that is renamed at the end.
 */
class ToolsInstaller(
    private val toolsDir: File,
    private val runner: ProcessRunner,
    private val http: ToolsHttp,
    private val os: String,
    private val arch: String,
) {
    private val bundled get() = ToolPathsResolver.bundled(toolsDir, os)

    suspend fun detect(pathEnv: String): ToolPaths? {
        val own = bundled
        if (own.node.isFile && own.npm.isFile && own.firebase.isFile) return own
        val system = ToolPathsResolver.fromPath(pathEnv, os) { it.isFile } ?: return null
        val version = StringBuilder()
        val code = runCatching { runner.run(listOf(system.node.path, "-v"), null, emptyMap(), null) { version.append(it) } }
            .getOrDefault(1)
        return system.takeIf { code == 0 && majorVersion(version.toString()) >= MIN_NODE_MAJOR }
    }

    suspend fun install(onProgress: (Float, String) -> Unit): ToolPaths {
        toolsDir.mkdirs()
        onProgress(0f, "node")
        val sums = http.text("$NODE_DIST/SHASUMS256.txt")
        val version = sums.lineSequence().mapNotNull { VERSION.find(it)?.value }.firstOrNull()
            ?: throw IOException("Node.js version not found")
        val name = archiveName(version, os, arch)
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
            val code = runner.run(listOf("tar", "-xf", archive.path, "-C", tmp.path), null, emptyMap(), null) {}
            if (code != 0) throw IOException("could not extract Node.js")
            val extracted = tmp.listFiles()?.singleOrNull { it.isDirectory } ?: throw IOException("unexpected Node.js archive layout")
            val nodeDir = toolsDir.resolve("node")
            nodeDir.deleteRecursively()
            if (!extracted.renameTo(nodeDir)) throw IOException("could not move Node.js into place")
        } finally {
            tmp.deleteRecursively()
            downloads.deleteRecursively()
        }
        onProgress(0.75f, "firebase")
        val paths = bundled
        val prefix = toolsDir.resolve("npm-global")
        val env = mapOf("PATH" to listOf(paths.binDir.path, System.getenv("PATH").orEmpty()).joinToString(File.pathSeparator))
        val code = runner.run(
            paths.npmCommand() + listOf("install", "--global", "--prefix", prefix.path, "--no-audit", "--no-fund", "firebase-tools"),
            null,
            env,
            null,
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
        const val NODE_DIST = "https://nodejs.org/dist/latest-v22.x"
        private const val MIN_NODE_MAJOR = 22
        private val VERSION = Regex("v22\\.[0-9]+\\.[0-9]+")

        fun archiveName(version: String, os: String, arch: String): String {
            val o = os.lowercase()
            val cpu = if (arch.lowercase() in setOf("aarch64", "arm64")) "arm64" else "x64"
            return when {
                o.startsWith("windows") -> "node-$version-win-$cpu.zip"
                o.startsWith("mac") || o.contains("darwin") -> "node-$version-darwin-$cpu.tar.gz"
                else -> "node-$version-linux-$cpu.tar.xz"
            }
        }

        fun majorVersion(text: String): Int =
            Regex("v?([0-9]+)\\.").find(text.trim())?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }
}
