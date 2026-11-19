package com.eventmanager.app.setup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SetupLogFilesTest {
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun logFilesAreRedactedAndPruned() {
        val dir = java.nio.file.Files.createTempDirectory("setup logs").toFile()
        val now = 1_800_000_000_000L
        val logs = SetupLogFiles(dir, OutputRedactor { listOf("hunter2") }) { now }
        val write = logs.sink(SetupStepId.SERVER)
        write("password is hunter2 ok")
        write("second line")
        val file = assertNotNull(logs.latest(SetupStepId.SERVER))
        assertTrue(file.name.endsWith("-server.log"))
        val text = file.readText()
        assertTrue("***" in text)
        assertFalse("hunter2" in text)
        assertEquals(2, text.lines().filter { it.isNotBlank() }.size)

        val old = dir.resolve("old.log").apply { writeText("x"); setLastModified(now - 8 * day) }
        val recent = dir.resolve("recent.log").apply { writeText("x"); setLastModified(now - 6 * day) }
        logs.prune()
        assertFalse(old.exists())
        assertTrue(recent.exists())
    }
}
