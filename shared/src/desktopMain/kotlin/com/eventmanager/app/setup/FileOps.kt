package com.eventmanager.app.setup

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import java.io.IOException

/** A file or folder that stayed in use (often an antivirus scan) through every attempt. */
class FileLockedException(val path: File) : IOException("File is locked: ${path.path}")

object FileOps {
    /** Runs [action], retrying with a doubling delay; the last failure is rethrown. */
    suspend fun <T> retrying(
        attempts: Int = 5,
        firstDelayMs: Long = 500,
        sleep: suspend (Long) -> Unit = { delay(it) },
        action: () -> T,
    ): T {
        var wait = firstDelayMs
        var attempt = 1
        while (true) {
            try {
                return action()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (attempt >= attempts) throw e
                sleep(wait)
                wait *= 2
                attempt++
            }
        }
    }

    suspend fun deleteTree(
        dir: File,
        sleep: suspend (Long) -> Unit = { delay(it) },
        delete: (File) -> Boolean = { it.deleteRecursively() },
    ) {
        if (!dir.exists()) return
        try {
            retrying(sleep = sleep) {
                if (dir.exists() && !delete(dir) && dir.exists()) throw IOException("could not delete ${dir.path}")
            }
        } catch (e: IOException) {
            throw FileLockedException(dir)
        }
    }

    suspend fun move(
        from: File,
        to: File,
        sleep: suspend (Long) -> Unit = { delay(it) },
        rename: (File, File) -> Boolean = { a, b -> a.renameTo(b) },
    ) {
        try {
            retrying(sleep = sleep) {
                if (!rename(from, to)) throw IOException("could not move ${from.path}")
            }
        } catch (e: IOException) {
            throw FileLockedException(to)
        }
    }
}
