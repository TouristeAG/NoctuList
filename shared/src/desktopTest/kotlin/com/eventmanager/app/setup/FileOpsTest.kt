package com.eventmanager.app.setup

import kotlinx.coroutines.runBlocking
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileOpsTest {
    private val base: File = Files.createTempDirectory("fileops test").toFile()

    @AfterTest
    fun cleanUp() {
        base.deleteRecursively()
    }

    @Test
    fun retryingSucceedsAfterTwoFailures(): Unit = runBlocking {
        var calls = 0
        val sleeps = mutableListOf<Long>()
        val result = FileOps.retrying(sleep = { sleeps += it }) {
            calls++
            if (calls < 3) throw IOException("locked")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(3, calls)
        assertEquals(listOf(500L, 1000L), sleeps)
    }

    @Test
    fun retryingRethrowsAfterLastAttempt(): Unit = runBlocking {
        var calls = 0
        val e = assertFailsWith<IOException> {
            FileOps.retrying(attempts = 3, sleep = {}) {
                calls++
                throw IOException("failure $calls")
            }
        }
        assertEquals(3, calls)
        assertEquals("failure 3", e.message)
    }

    @Test
    fun deleteTreeThatStaysLockedThrowsFileLocked(): Unit = runBlocking {
        val dir = base.resolve("node").apply { mkdirs() }
        val e = assertFailsWith<FileLockedException> {
            FileOps.deleteTree(dir, sleep = {}, delete = { false })
        }
        assertEquals(dir, e.path)
    }

    @Test
    fun deleteTreeRemovesTheFolder(): Unit = runBlocking {
        val dir = base.resolve("node/lib").apply { mkdirs() }
        dir.resolve("a").writeText("x")
        FileOps.deleteTree(base.resolve("node"), sleep = {})
        assertFalse(base.resolve("node").exists())
    }

    @Test
    fun moveThatStaysLockedThrowsFileLocked(): Unit = runBlocking {
        val from = base.resolve("from").apply { mkdirs() }
        val to = base.resolve("to")
        val e = assertFailsWith<FileLockedException> {
            FileOps.move(from, to, sleep = {}, rename = { _, _ -> false })
        }
        assertEquals(to, e.path)
        FileOps.move(from, to, sleep = {})
        assertTrue(to.isDirectory)
    }
}
