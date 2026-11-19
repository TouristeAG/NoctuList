package com.eventmanager.app.setup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicLong

/** Time limits for one command: [totalMs] overall, [idleMs] without any output line. */
data class CommandLimits(val totalMs: Long, val idleMs: Long) {
    companion object {
        val READ = CommandLimits(120_000, 60_000)
        val LOGIN = CommandLimits(120_000, 60_000)
        val NPM = CommandLimits(600_000, 300_000)
        val DEPLOY = CommandLimits(1_500_000, 600_000)
    }
}

/** A command was stopped because it ran too long ([idle] false) or went quiet too long ([idle] true). */
class CommandTimeoutException(val idle: Boolean) : Exception(if (idle) "command produced no output" else "command took too long")

/** Runs one command given as an argument array (never through a shell). */
interface ProcessRunner {
    suspend fun run(
        cmd: List<String>,
        workDir: File?,
        env: Map<String, String>,
        stdin: ByteArray?,
        limits: CommandLimits = CommandLimits.READ,
        onLine: (String) -> Unit,
    ): Int
}

class RealProcessRunner : ProcessRunner {
    override suspend fun run(
        cmd: List<String>,
        workDir: File?,
        env: Map<String, String>,
        stdin: ByteArray?,
        limits: CommandLimits,
        onLine: (String) -> Unit,
    ): Int = coroutineScope {
        val builder = ProcessBuilder(cmd).redirectErrorStream(true)
        if (workDir != null) builder.directory(workDir)
        builder.environment().putAll(env)
        val process = withContext(Dispatchers.IO) { builder.start() }
        var timeout: CommandTimeoutException? = null
        try {
            withContext(Dispatchers.IO) { process.outputStream.use { out -> if (stdin != null) out.write(stdin) } }
            val started = System.nanoTime()
            val lastLine = AtomicLong(started)
            // Output is read on its own thread; waiting on the exit future is what gets cancelled.
            val reader = launch(Dispatchers.IO) {
                InputStreamReader(process.inputStream, Charsets.UTF_8).buffered().useLines { lines ->
                    lines.forEach {
                        lastLine.set(System.nanoTime())
                        onLine(it)
                    }
                }
            }
            val watchdog = launch {
                while (isActive) {
                    delay(WATCH_INTERVAL_MS)
                    val now = System.nanoTime()
                    val tooLong = (now - started) / 1_000_000 > limits.totalMs
                    val tooQuiet = (now - lastLine.get()) / 1_000_000 > limits.idleMs
                    if (tooLong || tooQuiet) {
                        timeout = CommandTimeoutException(idle = !tooLong)
                        killTree(process)
                        break
                    }
                }
            }
            val code = process.onExit().await().exitValue()
            watchdog.cancel()
            reader.join()
            timeout?.let { throw it }
            code
        } finally {
            if (process.isAlive) killTree(process)
        }
    }

    private fun killTree(process: Process) {
        process.descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    private companion object {
        const val WATCH_INTERVAL_MS = 50L
    }
}
