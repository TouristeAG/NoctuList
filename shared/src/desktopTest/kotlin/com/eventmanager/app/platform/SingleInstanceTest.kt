package com.eventmanager.app.platform

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
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
            assertEquals(SingleInstance.Acquire.FIRST, first.acquire { })
            assertEquals(SingleInstance.Acquire.ALREADY_RUNNING, second.acquire { })
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
            assertEquals(SingleInstance.Acquire.FIRST, first.acquire { latch.countDown() })
            assertEquals(SingleInstance.Acquire.ALREADY_RUNNING, second.acquire { })
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
            assertEquals(SingleInstance.Acquire.FIRST, instance.acquire { })
        } finally {
            instance.release()
        }
    }

    @Test
    fun lockIoErrorStartsUnprotected() {
        val dir = tempDir()
        File(dir, "app.lock").mkdir() // opening a directory as a lock file fails with an IOException
        val instance = SingleInstance(dir)
        try {
            assertEquals(SingleInstance.Acquire.UNPROTECTED, instance.acquire { })
        } finally {
            instance.release()
        }
    }

    @Test
    fun acquireNeverThrowsWhenDirIsUnwritable() {
        val notADir = File.createTempFile("single-instance", ".file")
        val instance = SingleInstance(notADir)
        try {
            assertEquals(SingleInstance.Acquire.UNPROTECTED, instance.acquire { })
        } finally {
            instance.release()
            notADir.delete()
        }
    }

    @Test
    fun signalFirstFailsFastOnDeadPort() {
        val dir = tempDir()
        val port = java.net.ServerSocket(0).use { it.localPort }
        File(dir, "app.port").writeText(port.toString())
        val start = System.nanoTime()
        assertFalse(SingleInstance(dir).signalFirst())
        assertTrue((System.nanoTime() - start) / 1_000_000 < 2000)
    }
}
