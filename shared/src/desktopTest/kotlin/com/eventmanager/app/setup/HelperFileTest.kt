package com.eventmanager.app.setup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class HelperFileTest {
    private val base = Files.createTempDirectory("helper file").toFile()
    private val target = base.resolve("setup/helper/noctulist-gcp.cjs")

    @AfterTest
    fun cleanUp() {
        base.deleteRecursively()
    }

    @Test
    fun parallelFirstUsesLeaveOneWholeFile(): Unit = runBlocking {
        val bytes = ByteArray(512 * 1024) { (it % 251).toByte() }
        (1..16).map { async(Dispatchers.Default) { writeHelperFile(target, bytes) } }.awaitAll()
        assertContentEquals(bytes, target.readBytes())
        assertEquals(listOf(target.name), target.parentFile.list()!!.toList())
    }

    @Test
    fun aChangedHelperReplacesTheOldOne(): Unit = runBlocking {
        target.parentFile.mkdirs()
        target.writeText("old helper")
        writeHelperFile(target, "new helper".encodeToByteArray())
        assertEquals("new helper", target.readText())
        assertEquals(listOf(target.name), target.parentFile.list()!!.toList())
    }
}
