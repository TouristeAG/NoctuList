package com.eventmanager.app.setup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/** Runs one command given as an argument array (never through a shell). */
interface ProcessRunner {
    suspend fun run(
        cmd: List<String>,
        workDir: File?,
        env: Map<String, String>,
        stdin: ByteArray?,
        onLine: (String) -> Unit,
    ): Int
}

class RealProcessRunner : ProcessRunner {
    override suspend fun run(
        cmd: List<String>,
        workDir: File?,
        env: Map<String, String>,
        stdin: ByteArray?,
        onLine: (String) -> Unit,
    ): Int = withContext(Dispatchers.IO) {
        val builder = ProcessBuilder(cmd).redirectErrorStream(true)
        if (workDir != null) builder.directory(workDir)
        builder.environment().putAll(env)
        val process = builder.start()
        try {
            process.outputStream.use { out -> if (stdin != null) out.write(stdin) }
            runInterruptible {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
                process.waitFor()
            }
        } finally {
            if (process.isAlive) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
            coroutineContext.ensureActive()
        }
    }
}
