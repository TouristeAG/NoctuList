package com.eventmanager.app.ui.desktop

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.round

/**
 * Linux UI scale, applied before AWT starts.
 *
 * Skiko's autodpi reads `Xft.dpi` once, the first time its native library is extracted.
 * On Ubuntu that database is often still empty, so the scale becomes 1 (or 0) and every
 * Compose dp is drawn tiny on a HiDPI / fractional-scaled desktop. The next launch sees
 * a populated `Xft.dpi` and looks normal. GNOME's `GDK_SCALE` and `monitors.xml` are
 * already correct on that first launch, so we trust those and stop Skiko from overwriting them.
 *
 * On Wayland, Xwayland already downscales by the fractional part. Java must render at the
 * integer `GDK_SCALE` (usually 2). Multiplying by `GDK_DPI_SCALE` makes the UI small again.
 */
internal object LinuxUiScale {
    fun install() {
        if (!isLinux()) return
        if (System.getProperty("noctulist.linux.uiScale").equals("false", ignoreCase = true)) return
        // Skiko Setup.init overwrites sun.java2d.uiScale whenever autodpi is still true.
        // Leave autodpi alone when we found no scale, so a later Xft.dpi can still apply.
        if (!System.getProperty("sun.java2d.uiScale").isNullOrBlank()) {
            System.setProperty("sun.java2d.uiScale.enabled", "true")
            System.setProperty("skiko.linux.autodpi", "false")
            return
        }
        val scale = resolveLinuxUiScale(detectSignals()) ?: return
        System.setProperty("sun.java2d.uiScale.enabled", "true")
        System.setProperty("sun.java2d.uiScale", formatUiScale(scale))
        System.setProperty("skiko.linux.autodpi", "false")
        System.err.println(
            "NoctuList: Linux UI scale=${formatUiScale(scale)} " +
                "(override with -Dsun.java2d.uiScale=… or disable with -Dnoctulist.linux.uiScale=false)",
        )
    }

    private fun isLinux(): Boolean =
        System.getProperty("os.name").orEmpty().contains("linux", ignoreCase = true)

    private fun detectSignals(): LinuxScaleSignals {
        val home = System.getProperty("user.home").orEmpty()
        val config = File(home, ".config")
        val wayland = !System.getenv("WAYLAND_DISPLAY").isNullOrBlank() ||
            System.getenv("XDG_SESSION_TYPE").equals("wayland", ignoreCase = true)
        return LinuxScaleSignals(
            wayland = wayland,
            gdkScale = parseScaleNumber(System.getenv("GDK_SCALE")),
            gdkDpiScale = parseScaleNumber(System.getenv("GDK_DPI_SCALE")),
            gnomeMonitorScale = readText(File(config, "monitors.xml"))?.let(::gnomePrimaryScale),
            kdeScale = kdeUiScale(
                readText(File(config, "kwinrc")).orEmpty(),
                readText(File(config, "kdeglobals")).orEmpty(),
            ),
            gnomeSettingsScale = readProcess(listOf("gsettings", "get", "org.gnome.desktop.interface", "scaling-factor"))
                ?.let(::parseGnomeSettingsScale),
            xftDpi = readProcess(listOf("xrdb", "-query"))?.let(::parseXftDpi),
        )
    }

    private fun readText(file: File): String? =
        if (file.isFile) runCatching { file.readText() }.getOrNull() else null

    private fun readProcess(command: List<String>): String? = runCatching {
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        if (!process.waitFor(400, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            return null
        }
        process.inputStream.bufferedReader().use { it.readText() }
    }.getOrNull()
}

internal data class LinuxScaleSignals(
    val wayland: Boolean = false,
    val gdkScale: Double? = null,
    val gdkDpiScale: Double? = null,
    val gnomeMonitorScale: Double? = null,
    val kdeScale: Double? = null,
    val gnomeSettingsScale: Int? = null,
    val xftDpi: Double? = null,
)

internal fun resolveLinuxUiScale(signals: LinuxScaleSignals): Double? {
    signals.gdkScale?.takeIf { it >= 1.0 }?.let { gdk ->
        val dpi = signals.gdkDpiScale
        val scale = when {
            // Wayland already applied the fractional part. A bare 1 is the unset default.
            signals.wayland && gdk > 1.0 -> gdk
            !signals.wayland && dpi != null -> gdk * dpi
            gdk > 1.0 -> gdk
            else -> null
        }
        scale?.asUiScale()?.let { return it }
    }
    signals.gnomeMonitorScale?.asUiScale()?.let { return it }
    signals.kdeScale?.asUiScale()?.let { return it }
    signals.gnomeSettingsScale?.takeIf { it > 0 }?.toDouble()?.asUiScale()?.let { return it }
    // 96 dpi is the X11 default when the desktop has not published a scale yet.
    signals.xftDpi?.takeIf { it >= 120.0 }?.let { return (it / 96.0).asUiScale() }
    return null
}

internal fun formatUiScale(scale: Double): String {
    val rounded = round(scale * 100.0) / 100.0
    val asInt = rounded.toInt()
    return if (rounded == asInt.toDouble()) asInt.toString() else rounded.toString()
}

internal fun parseScaleNumber(raw: String?): Double? {
    val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return text.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
}

internal fun parseGnomeSettingsScale(output: String): Int? {
    val match = Regex("""uint32\s+(\d+)""").find(output)
    return (match?.groupValues?.get(1) ?: output.trim()).toIntOrNull()
}

internal fun parseXftDpi(xrdbQuery: String): Double? {
    val line = xrdbQuery.lineSequence().firstOrNull { raw ->
        raw.trim().startsWith("Xft.dpi", ignoreCase = true)
    } ?: return null
    return line.substringAfter(':').trim().toDoubleOrNull()
}

/** Primary logical monitor in the first GNOME configuration, else the first scale found there. */
internal fun gnomePrimaryScale(monitorsXml: String): Double? {
    val config = Regex("(?s)<configuration>(.*?)</configuration>")
        .find(monitorsXml)
        ?.groupValues
        ?.get(1)
        ?: monitorsXml
    val blocks = Regex("(?s)<logicalmonitor>(.*?)</logicalmonitor>").findAll(config).toList()
    fun scaleOf(block: String): Double? =
        Regex("""<scale>\s*([0-9]+(?:\.[0-9]+)?)\s*</scale>""")
            .find(block)
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull()
    val primary = blocks.firstOrNull { it.value.contains("<primary>yes</primary>") }
    return primary?.let { scaleOf(it.value) } ?: blocks.firstNotNullOfOrNull { scaleOf(it.value) }
}

internal fun kdeUiScale(kwinrc: String, kdeglobals: String): Double? =
    iniValue(kwinrc, "Xwayland", "Scale")
        ?: iniValue(kdeglobals, "KScreen", "ScaleFactor")

private fun iniValue(text: String, section: String, key: String): Double? {
    if (text.isBlank()) return null
    val body = Regex("(?m)^\\[${Regex.escape(section)}]\\s*\$([\\s\\S]*?)(?=^\\[|\\z)")
        .find(text)
        ?.groupValues
        ?.get(1)
        ?: return null
    return Regex("(?m)^${Regex.escape(key)}\\s*=\\s*([0-9]+(?:\\.[0-9]+)?)")
        .find(body)
        ?.groupValues
        ?.get(1)
        ?.toDoubleOrNull()
}

private fun Double.asUiScale(): Double? {
    if (!isFinite()) return null
    val rounded = round(this * 100.0) / 100.0
    return rounded.takeIf { it in 1.0..4.0 }
}
