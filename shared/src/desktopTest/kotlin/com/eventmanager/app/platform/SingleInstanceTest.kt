package com.eventmanager.app.platform

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleInstanceTest {
    private fun tempDir(): File = Files.createTempDirectory("single-instance").toFile()

    @Test
    fun secondAcquireFailsWhileFirstHolds() {
        val dir = tempDir()
        val first = SingleInstance(dir)
        val second = SingleInstance(dir)
        try {
            assertTrue(first.acquire { })
            assertFalse(second.acquire { })
        } finally {
            second.release()
            first.release()
        }
    }

    @Test
    fun signalReachesFirst() {
        val dir = tempDir()
        val first = SingleInstance(dir)
        val second = SingleInstance(dir)
        val latch = CountDownLatch(1)
        try {
            assertTrue(first.acquire { latch.countDown() })
            assertFalse(second.acquire { })
            assertTrue(second.signalFirst())
            assertTrue(latch.await(2, TimeUnit.SECONDS))
        } finally {
            second.release()
            first.release()
        }
    }

    @Test
    fun staleFilesDoNotBlock() {
        val dir = tempDir()
        File(dir, "app.port").writeText("1")
        val instance = SingleInstance(dir)
        try {
            assertTrue(instance.acquire { })
        } finally {
            instance.release()
        }
    }
}
