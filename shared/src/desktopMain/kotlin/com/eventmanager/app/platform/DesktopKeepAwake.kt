package com.eventmanager.app.platform

import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.util.concurrent.CountDownLatch

/**
 * Keeps the display on and blocks the idle lock on desktop while at least one holder is active.
 *
 * - macOS: a `caffeinate -d -i -w <pid>` child holds the "no display sleep" and "no idle sleep"
 *   assertions. The screen saver and the "require password after display sleep" lock both wait
 *   for display idle, so they never trigger. `-w` ends the child if the app dies.
 * - Windows: a power request (display + system required), the API browsers use for video, plus
 *   SetThreadExecutionState on a dedicated thread as a fallback for older builds.
 * - Linux: best effort through `systemd-inhibit --what=idle:sleep`, tied to the app's pid.
 */
object DesktopKeepAwake {
    private val osName = System.getProperty("os.name").orEmpty()
    private val isMac = osName.startsWith("Mac")
    private val isWindows = osName.startsWith("Windows")

    private val lock = Any()
    private var holders = 0
    private var macOrLinuxProcess: Process? = null
    private var windowsSession: WindowsSession? = null
    private var shutdownHookInstalled = false

    fun acquire() {
        synchronized(lock) {
            holders++
            if (holders == 1) start()
        }
    }

    fun release() {
        synchronized(lock) {
            if (holders == 0) return
            holders--
            if (holders == 0) stop()
        }
    }

    private fun start() {
        installShutdownHook()
        try {
            when {
                isMac -> macOrLinuxProcess = ProcessBuilder(
                    "/usr/bin/caffeinate", "-d", "-i", "-w", ProcessHandle.current().pid().toString(),
                ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
                isWindows -> windowsSession = WindowsSession.start()
                else -> macOrLinuxProcess = ProcessBuilder(
                    "systemd-inhibit",
                    "--what=idle:sleep",
                    "--who=NoctuList",
                    "--why=Keep screen on",
                    "--mode=block",
                    "tail", "--pid=${ProcessHandle.current().pid()}", "-f", "/dev/null",
                ).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
            }
        } catch (e: Throwable) {
            println("Keep screen on could not start: ${e.message}")
        }
    }

    private fun stop() {
        macOrLinuxProcess?.let { process ->
            process.destroy()
            macOrLinuxProcess = null
        }
        windowsSession?.let { session ->
            session.stop()
            windowsSession = null
        }
    }

    private fun installShutdownHook() {
        if (shutdownHookInstalled) return
        shutdownHookInstalled = true
        Runtime.getRuntime().addShutdownHook(Thread { synchronized(lock) { stop() } })
    }

    @Suppress("FunctionName")
    private interface Kernel32 : StdCallLibrary {
        fun PowerCreateRequest(context: ReasonContext): Pointer?
        fun PowerSetRequest(request: Pointer, type: Int): Boolean
        fun PowerClearRequest(request: Pointer, type: Int): Boolean
        fun CloseHandle(handle: Pointer): Boolean
        fun SetThreadExecutionState(flags: Int): Int
    }

    /** REASON_CONTEXT with the simple-string variant; the two pads cover the rest of the union. */
    @Structure.FieldOrder("Version", "Flags", "SimpleReasonString", "Pad1", "Pad2")
    class ReasonContext : Structure() {
        @JvmField var Version: Int = 0
        @JvmField var Flags: Int = POWER_REQUEST_CONTEXT_SIMPLE_STRING
        @JvmField var SimpleReasonString: WString? = null
        @JvmField var Pad1: Pointer? = null
        @JvmField var Pad2: Pointer? = null
    }

    private class WindowsSession(
        private val kernel32: Kernel32,
        private val request: Pointer?,
        private val releaseThread: CountDownLatch,
    ) {
        fun stop() {
            request?.let {
                runCatching { kernel32.PowerClearRequest(it, POWER_REQUEST_DISPLAY_REQUIRED) }
                runCatching { kernel32.PowerClearRequest(it, POWER_REQUEST_SYSTEM_REQUIRED) }
                runCatching { kernel32.CloseHandle(it) }
            }
            releaseThread.countDown()
        }

        companion object {
            fun start(): WindowsSession {
                val kernel32 = Native.load("kernel32", Kernel32::class.java)
                val request = runCatching {
                    val context = ReasonContext().apply {
                        SimpleReasonString = WString("NoctuList keeps the screen on")
                    }
                    kernel32.PowerCreateRequest(context)?.also { handle ->
                        kernel32.PowerSetRequest(handle, POWER_REQUEST_DISPLAY_REQUIRED)
                        kernel32.PowerSetRequest(handle, POWER_REQUEST_SYSTEM_REQUIRED)
                    }
                }.getOrNull()

                // The execution state belongs to the calling thread, so a dedicated thread holds it
                // and clears it itself once released.
                val latch = CountDownLatch(1)
                Thread({
                    runCatching {
                        kernel32.SetThreadExecutionState(ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED)
                    }
                    try {
                        latch.await()
                    } catch (_: InterruptedException) {
                    }
                    runCatching { kernel32.SetThreadExecutionState(ES_CONTINUOUS) }
                }, "keep-screen-on").apply {
                    isDaemon = true
                    start()
                }
                return WindowsSession(kernel32, request, latch)
            }
        }
    }

    private const val POWER_REQUEST_CONTEXT_SIMPLE_STRING = 0x1
    private const val POWER_REQUEST_DISPLAY_REQUIRED = 0
    private const val POWER_REQUEST_SYSTEM_REQUIRED = 1
    private const val ES_SYSTEM_REQUIRED = 0x00000001
    private const val ES_DISPLAY_REQUIRED = 0x00000002
    private const val ES_CONTINUOUS = 0x80000000.toInt()
}
