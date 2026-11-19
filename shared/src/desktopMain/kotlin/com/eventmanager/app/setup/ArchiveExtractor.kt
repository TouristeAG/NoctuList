package com.eventmanager.app.setup

import java.io.File
import java.io.IOException
import java.util.zip.ZipFile

object ArchiveExtractor {
    /** Zips are read in Java (Windows may find Git's `tar` first, which cannot read them); tar archives use `tar`. */
    fun extract(archive: File, into: File, os: String, runner: ProcessRunner) {
        if (archive.name.endsWith(".zip")) {
            extractZip(archive, into)
        } else {
            // tar runs to completion even if the caller is cancelled; the caller's finally block cleans up.
            val code = kotlinx.coroutines.runBlocking {
                runner.run(listOf("tar", "-xf", archive.path, "-C", into.path), null, emptyMap(), null) {}
            }
            if (code != 0) throw IOException("could not extract ${archive.name}")
        }
    }

    private fun extractZip(archive: File, into: File) {
        into.mkdirs()
        val root = into.canonicalFile
        ZipFile(archive).use { zip ->
            for (entry in zip.entries()) {
                val target = File(root, entry.name).canonicalFile
                if (target != root && !target.path.startsWith(root.path + File.separator)) {
                    throw SecurityException("Archive entry outside the target folder: ${entry.name}")
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                    continue
                }
                target.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use { input.copyTo(it) } }
                if (target.parentFile.name == "bin") target.setExecutable(true)
            }
        }
    }
}
