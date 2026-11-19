package com.eventmanager.app.setup

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArchiveExtractorTest {
    private val base: File = Files.createTempDirectory("extract test").toFile()

    @AfterTest
    fun cleanUp() {
        base.deleteRecursively()
    }

    private fun zip(vararg entries: Pair<String, String>): File {
        val file = base.resolve("node.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            entries.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.encodeToByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    @Test
    fun zipExtractsWithoutTar() {
        val runner = FakeProcessRunner()
        val target = base.resolve("out").apply { mkdirs() }
        ArchiveExtractor.extract(zip("node-v22/bin/node.exe" to "exe", "node-v22/README.md" to "hi"), target, "Windows 11", runner)
        assertTrue(target.resolve("node-v22/bin/node.exe").isFile)
        assertEquals("hi", target.resolve("node-v22/README.md").readText())
        assertTrue(runner.calls.isEmpty())
    }

    @Test
    fun zipRejectsEntriesOutsideTarget() {
        val runner = FakeProcessRunner()
        val target = base.resolve("out").apply { mkdirs() }
        assertFailsWith<SecurityException> {
            ArchiveExtractor.extract(zip("../evil" to "x"), target, "Windows 11", runner)
        }
        assertFalse(base.resolve("evil").exists())
    }

    @Test
    fun zipKeepsExecutableBitUnderBin() {
        if (System.getProperty("os.name").orEmpty().lowercase().startsWith("windows")) return
        val target = base.resolve("out").apply { mkdirs() }
        ArchiveExtractor.extract(zip("n/bin/node" to "x", "n/lib/a.js" to "y"), target, "Linux", FakeProcessRunner())
        assertTrue(target.resolve("n/bin/node").canExecute())
        assertFalse(target.resolve("n/lib/a.js").canExecute())
    }

    @Test
    fun tarArchivesGoThroughTar() {
        val runner = FakeProcessRunner()
        val archive = base.resolve("node.tar.gz")
        val target = base.resolve("out")
        ArchiveExtractor.extract(archive, target, "Mac OS X", runner)
        assertEquals(listOf("tar", "-xf", archive.path, "-C", target.path), runner.calls.single())
    }

    @Test
    fun failedTarIsAnError() {
        val runner = FakeProcessRunner(answer = { 2 to "" })
        assertFailsWith<java.io.IOException> {
            ArchiveExtractor.extract(base.resolve("a.tar.xz"), base.resolve("out"), "Linux", runner)
        }
    }
}
