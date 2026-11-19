package com.eventmanager.app.platform

import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.channels.OverlappingFileLockException

/**
 * Keeps a single NoctuList process per data folder. The first process holds a file lock and
 * listens on a loopback port (stored in app.port). A later launch connects to that port, asks
 * the first one to show its window, and exits.
 */
class SingleInstance(private val dir: File) {
    private val lockFile = File(dir, "app.lock")
    private val portFile = File(dir, "app.port")

    private var raf: RandomAccessFile? = null
    private var channel: FileChannel? = null
    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    /** Returns true if this process is the first one. [onActivate] runs on a daemon thread. */
    @Synchronized
    fun acquire(onActivate: () -> Unit): Boolean {
        if (lock != null) return true
        dir.mkdirs()
        val file = RandomAccessFile(lockFile, "rw")
        val ch = file.channel
        val acquired = try {
            ch.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        } catch (_: Exception) {
            null
        }
        if (acquired == null) {
            runCatching { ch.close() }
            runCatching { file.close() }
            return false
        }
        raf = file
        channel = ch
        lock = acquired

        val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
        server = socket
        portFile.writeText(socket.localPort.toString())

        Thread({
            while (!socket.isClosed) {
                val client = try {
                    socket.accept()
                } catch (_: Exception) {
                    break
                }
                handle(client, onActivate)
            }
        }, "single-instance-listener").apply { isDaemon = true }.start()
        return true
    }

    private fun handle(client: Socket, onActivate: () -> Unit) {
        client.use {
            try {
                it.soTimeout = 2000
                val line = it.getInputStream().bufferedReader().readLine()
                if (line == "show") onActivate()
            } catch (_: Exception) {
                // Ignore malformed or slow clients.
            }
        }
    }

    /** Asks the running instance to show its window. Returns true if it answered. */
    fun signalFirst(): Boolean {
        val port = runCatching { portFile.readText().trim().toInt() }.getOrNull() ?: return false
        return try {
            Socket(InetAddress.getLoopbackAddress(), port).use {
                it.getOutputStream().apply {
                    write("show\n".toByteArray())
                    flush()
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized
    fun release() {
        runCatching { server?.close() }
        server = null
        val held = lock != null
        runCatching { lock?.release() }
        lock = null
        runCatching { channel?.close() }
        runCatching { raf?.close() }
        channel = null
        raf = null
        if (held) runCatching { portFile.delete() }
    }
}
