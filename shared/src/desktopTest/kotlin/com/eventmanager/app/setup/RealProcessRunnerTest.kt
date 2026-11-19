package com.eventmanager.app.setup

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RealProcessRunnerTest {
    @Test
    fun cancelStopsALongCommandRightAway(): Unit = runBlocking {
        val job = async { RealProcessRunner().run(listOf("sleep", "30"), null, emptyMap(), null) {} }
        delay(300)
        val start = System.nanoTime()
        job.cancel()
        job.join()
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(ms < 1_000, "cancel took $ms ms")
    }

    @Test
    fun readsOutputAndExitCode(): Unit = runBlocking {
        val lines = mutableListOf<String>()
        val code = RealProcessRunner().run(listOf("sh", "-c", "echo hello; cat; exit 3"), null, emptyMap(), "from stdin".encodeToByteArray()) { lines += it }
        assertEquals(3, code)
        assertEquals(listOf("hello", "from stdin"), lines)
    }

    private val windows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    @Test
    fun realRunnerDecodesUtf8(): Unit = runBlocking {
        if (windows) return@runBlocking
        val lines = mutableListOf<String>()
        RealProcessRunner().run(listOf("sh", "-c", "printf '\\303\\251 \\342\\234\\224\\n'"), null, mapOf("LC_ALL" to "C"), null) { lines += it }
        assertEquals(listOf("\u00e9 \u2714"), lines)
    }

    @Test
    fun realRunnerStopsOnIdleLimit(): Unit = runBlocking {
        if (windows) return@runBlocking
        val start = System.nanoTime()
        val e = assertFailsWith<CommandTimeoutException> {
            RealProcessRunner().run(listOf("sh", "-c", "sleep 5"), null, emptyMap(), null, CommandLimits(10_000, 300)) {}
        }
        assertTrue(e.idle)
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2_000)
    }

    @Test
    fun realRunnerStopsOnTotalLimit(): Unit = runBlocking {
        if (windows) return@runBlocking
        val e = assertFailsWith<CommandTimeoutException> {
            RealProcessRunner().run(
                listOf("sh", "-c", "while true; do echo x; sleep 0.1; done"), null, emptyMap(), null, CommandLimits(500, 10_000),
            ) {}
        }
        assertEquals(false, e.idle)
    }

    @Test
    fun realRunnerHandlesPathsWithSpacesAndAccents(): Unit = runBlocking {
        if (windows) return@runBlocking
        val dir = java.nio.file.Files.createTempDirectory("runner").toFile().resolve("l\u00e9o m").apply { mkdirs() }
        val f = dir.resolve("x.txt").apply { writeText("hi") }
        val lines = mutableListOf<String>()
        val code = RealProcessRunner().run(listOf("cat", f.path), dir, emptyMap(), null) { lines += it }
        assertEquals(0, code)
        assertEquals(listOf("hi"), lines)
    }
}
