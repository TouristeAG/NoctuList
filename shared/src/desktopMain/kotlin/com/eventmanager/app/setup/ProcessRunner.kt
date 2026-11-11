package com.eventmanager.app.setup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

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
    ): Int = coroutineScope {
        val builder = ProcessBuilder(cmd).redirectErrorStream(true)
        if (workDir != null) builder.directory(workDir)
        builder.environment().putAll(env)
        val process = withContext(Dispatchers.IO) { builder.start() }
        try {
            withContext(Dispatchers.IO) { process.outputStream.use { out -> if (stdin != null) out.write(stdin) } }
            // Output is read on its own thread; waiting on the exit future is what gets cancelled.
            val reader = launch(Dispatchers.IO) {
                process.inputStream.bufferedReader().useLines { lines -> lines.forEach(onLine) }
            }
            val code = process.onExit().await().exitValue()
            reader.join()
            code
        } finally {
            if (process.isAlive) {
                process.descendants().forEach { it.destroyForcibly() }
                process.destroyForcibly()
            }
        }
    }
}
