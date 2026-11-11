package com.eventmanager.app.setup

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
