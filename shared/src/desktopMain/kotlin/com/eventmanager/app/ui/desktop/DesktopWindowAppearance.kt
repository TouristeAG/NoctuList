package com.eventmanager.app.ui.desktop

import com.eventmanager.app.platform.LinuxDesktopRuntime
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.win32.StdCallLibrary
import java.awt.Window
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.round

/**
 * Syncs native window chrome (title bar / traffic lights area) with dark or light appearance,
 * and configures Windows HiDPI so Compose/Skia does not look soft on fractional scales.
 *
 * Must call [initBeforeUiToolkit] before any AWT/Swing/Compose UI is created (start of `main`).
 */
object DesktopWindowAppearance {
    private const val MAC_OS = "Mac OS"
    private const val WINDOWS = "Windows"
    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    /** Win32 DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 — must be set before any HWND exists. */
    private const val DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2 = -4L
    /** PROCESS_PER_MONITOR_DPI_AWARE — fallback for Windows 8.1+. */
    private const val PROCESS_PER_MONITOR_DPI_AWARE = 2
    private const val LOGPIXELSX = 88

    /**
     * Disable fractional-scale snapping with `-Dnoctulist.uiScaleSnap=false`.
     * Force an exact scale with `-Dsun.java2d.uiScale=1.25` (skips snapping).
     */
    fun initBeforeUiToolkit() {
        // Linux scale and Netty guards must win before Skiko or Firestore class init.
        LinuxDesktopRuntime.install()
        if (isWindows()) {
            enableWindowsPerMonitorDpi()
            snapFractionalUiScaleForSharpness()
        }
        if (System.getProperty("os.name").orEmpty().startsWith(MAC_OS)) {
            if (System.getProperty("apple.awt.application.appearance").isNullOrBlank()) {
                System.setProperty("apple.awt.application.appearance", "system")
            }
        }
    }

    fun applyToWindow(window: Window, preferDark: Boolean) {
        when {
            isMacOs() -> applyMacOs(preferDark)
            isWindows() -> applyWindows(window, preferDark)
        }
    }

    private fun isMacOs(): Boolean = System.getProperty("os.name").orEmpty().startsWith(MAC_OS)

    private fun isWindows(): Boolean = System.getProperty("os.name").orEmpty().startsWith(WINDOWS)

    /**
     * Without this, Windows treats the process as DPI-unaware and stretches a 96 DPI bitmap
     * to match display scaling (125%/150%/200%) — text and UI look soft / low-res.
     */
    private fun enableWindowsPerMonitorDpi() {
        runCatching {
            val user32 = Native.load("user32", User32Dpi::class.java)
            val ok = user32.SetProcessDpiAwarenessContext(
                Pointer.createConstant(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2)
            )
            if (ok) return
        }
        // Older Windows / already locked: try system-per-monitor (Shcore).
        runCatching {
            val shcore = Native.load("shcore", ShcoreDpi::class.java)
            shcore.SetProcessDpiAwareness(PROCESS_PER_MONITOR_DPI_AWARE)
        }
    }

    /**
     * Skia/Compose looks soft at fractional OS scales (especially 125%). Rasterizing at an
     * integer scale keeps glyphs and strokes on the pixel grid. Trade-off: UI size vs other
     * apps may differ slightly (125% → render as 100%).
     */
    private fun snapFractionalUiScaleForSharpness() {
        if (System.getProperty("noctulist.uiScaleSnap").equals("false", ignoreCase = true)) return
        if (!System.getProperty("sun.java2d.uiScale").isNullOrBlank()) return

        val dpi = queryPrimaryDpi() ?: return
        val scale = dpi / 96.0
        if (abs(scale - round(scale)) < 0.02) return

        // Prefer the nearer integer; for mid-point scales (>= 1.5) prefer ceil for sharpness.
        val snapped = when {
            scale < 1.5 -> 1
            else -> ceil(scale).toInt().coerceAtLeast(2)
        }
        System.setProperty("sun.java2d.uiScale", snapped.toString())
        System.err.println(
            "NoctuList: OS UI scale=${"%.2f".format(scale)} (dpi=$dpi) → " +
                "sun.java2d.uiScale=$snapped for sharper rendering " +
                "(disable with -Dnoctulist.uiScaleSnap=false)"
        )
    }

    private fun queryPrimaryDpi(): Int? = runCatching {
        val user32 = Native.load("user32", User32Dpi::class.java)
        val gdi32 = Native.load("gdi32", Gdi32Dpi::class.java)
        val hdc = user32.GetDC(Pointer.NULL)
        if (hdc == Pointer.NULL) return@runCatching null
        try {
            gdi32.GetDeviceCaps(hdc, LOGPIXELSX).takeIf { it > 0 }
        } finally {
            user32.ReleaseDC(Pointer.NULL, hdc)
        }
    }.getOrNull()

    private fun applyMacOs(preferDark: Boolean) {
        runCatching {
            val appClass = Class.forName("com.apple.eawt.Application")
            val application = appClass.getMethod("getApplication").invoke(null)
            val appearanceClass = Class.forName("com.apple.eawt.Application\$Appearance")
            @Suppress("UNCHECKED_CAST")
            val appearanceEnum = appearanceClass as Class<out Enum<*>>
            val constantName = if (preferDark) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua"
            val appearance = java.lang.Enum.valueOf(appearanceEnum, constantName)
            appClass.getMethod("setAppearance", appearanceClass).invoke(application, appearance)
        }
    }

    private fun applyWindows(window: Window, preferDark: Boolean) {
        runCatching {
            val hwnd = Native.getWindowPointer(window)
            val flag = intArrayOf(if (preferDark) 1 else 0)
            Dwmapi.INSTANCE.DwmSetWindowAttribute(
                hwnd,
                DWMWA_USE_IMMERSIVE_DARK_MODE,
                flag,
                flag.size * Int.SIZE_BYTES
            )
        }
    }

    private interface User32Dpi : StdCallLibrary {
        /** @return true if the process default DPI context was set. */
        fun SetProcessDpiAwarenessContext(value: Pointer): Boolean
        fun GetDC(hwnd: Pointer?): Pointer?
        fun ReleaseDC(hwnd: Pointer?, hdc: Pointer?): Int
    }

    private interface Gdi32Dpi : StdCallLibrary {
        fun GetDeviceCaps(hdc: Pointer?, index: Int): Int
    }

    private interface ShcoreDpi : StdCallLibrary {
        fun SetProcessDpiAwareness(value: Int): Int
    }

    private interface Dwmapi : StdCallLibrary {
        fun DwmSetWindowAttribute(
            hwnd: Pointer,
            dwAttribute: Int,
            pvAttribute: IntArray,
            cbAttribute: Int
        ): Int

        companion object {
            val INSTANCE: Dwmapi = Native.load("dwmapi", Dwmapi::class.java) as Dwmapi
        }
    }
}
