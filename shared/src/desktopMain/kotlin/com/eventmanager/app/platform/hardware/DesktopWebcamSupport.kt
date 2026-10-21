package com.eventmanager.app.platform.hardware

import com.github.eduramiba.webcamcapture.drivers.NativeDriver
import com.github.sarxos.webcam.Webcam
import java.awt.Dimension
import java.io.File

/**
 * Sarxos 0.3.12 defaults to OpenIMAJ, which fails on modern macOS (UnsatisfiedLinkError).
 * The native AVFoundation driver works on current Mac/Windows/Linux releases.
 *
 * On recent macOS the driver still lists cameras *before* requesting TCC access, so
 * [MacCameraAuthorization.ensureAccess] must run first or [Webcam.getWebcams] is empty
 * and the system prompt never appears.
 */
object DesktopWebcamSupport {
    @Volatile
    private var initialized = false

    fun ensureInitialized() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            Webcam.setDriver(NativeDriver())
            initialized = true
        }
    }

    /**
     * Prefer 720p over the previous hardcoded 640×480. Printed card modules are
     * otherwise undersampled at typical webcam focus distance, while phone screens still
     * decode because they fill more of the frame with higher contrast.
     *
     * On Linux the native driver (nokhwa / V4L2) freezes after a few seconds when asked
     * for an unlisted or oversized mode — often uncompressed 1080p. Stay on an advertised
     * mode at or below 720p.
     */
    fun applyPreferredScanResolution(webcam: Webcam) {
        val advertised = webcam.viewSizes?.toList().orEmpty()
        val osName = System.getProperty("os.name").orEmpty()
        when (val choice = chooseScanResolution(advertised, osName)) {
            is ScanResolutionChoice.Advertised -> webcam.viewSize = choice.size
            is ScanResolutionChoice.Custom -> {
                val appliedCustom = runCatching {
                    webcam.setCustomViewSizes(choice.size, Dimension(640, 480))
                    webcam.viewSize = choice.size
                }.isSuccess
                if (!appliedCustom) {
                    webcam.viewSize = advertised
                        .filter { it.width in 640..1920 && it.height in 480..1080 }
                        .maxByOrNull { it.width.toLong() * it.height }
                        ?: advertised.maxByOrNull { it.width.toLong() * it.height }
                        ?: Dimension(640, 480)
                }
            }
        }
        if (osName.contains("linux", ignoreCase = true)) {
            keepLinuxWebcamsAwake()
            System.err.println(
                "NoctuList: webcam scan ${webcam.viewSize.width}x${webcam.viewSize.height} " +
                    "(${advertised.size} advertised modes)",
            )
        }
    }
}

internal sealed interface ScanResolutionChoice {
    val size: Dimension

    data class Advertised(override val size: Dimension) : ScanResolutionChoice
    data class Custom(override val size: Dimension) : ScanResolutionChoice
}

internal fun chooseScanResolution(advertised: List<Dimension>, osName: String): ScanResolutionChoice {
    if (osName.contains("linux", ignoreCase = true)) {
        return ScanResolutionChoice.Advertised(chooseLinuxScanResolution(advertised))
    }
    val preferred = listOf(
        Dimension(1280, 720),
        Dimension(1920, 1080),
        Dimension(1280, 800),
        Dimension(960, 720),
    )
    preferred.firstOrNull { want ->
        advertised.any { it.width == want.width && it.height == want.height }
    }?.let { return ScanResolutionChoice.Advertised(it) }
    return ScanResolutionChoice.Custom(Dimension(1280, 720))
}

/** Advertised mode at or below 720p. Never invents a size the V4L2 device did not list. */
internal fun chooseLinuxScanResolution(advertised: List<Dimension>): Dimension {
    if (advertised.isEmpty()) return Dimension(640, 480)
    for (want in LINUX_PREFERRED_SCAN_SIZES) {
        advertised.firstOrNull { it.width == want.width && it.height == want.height }?.let { return it }
    }
    val capped = advertised.filter { size ->
        maxOf(size.width, size.height) <= 1280 && size.width >= 320 && size.height >= 240
    }
    if (capped.isNotEmpty()) {
        return capped.maxBy { it.width.toLong() * it.height }
    }
    return advertised.minBy { it.width.toLong() * it.height }
}

/**
 * USB autosuspend on Ubuntu defaults to 2 seconds and drops UVC webcams mid-stream.
 * Best-effort: writing sysfs fails without permission and is ignored.
 */
internal fun keepLinuxWebcamsAwake(videoClassRoot: File = File("/sys/class/video4linux")) {
    if (!videoClassRoot.isDirectory) return
    videoClassRoot.listFiles()?.forEach { node ->
        val control = webcamPowerControlFile(node) ?: return@forEach
        writeSysfs(control, "on")
        val powerDir = control.parentFile ?: return@forEach
        // -1 disables USB autosuspend. Ubuntu's default (~2s) drops UVC cameras mid-stream.
        writeSysfs(File(powerDir, "autosuspend_delay_ms"), "-1")
        writeSysfs(File(powerDir, "autosuspend"), "-1")
    }
}

private fun writeSysfs(file: File, value: String) {
    runCatching {
        if (file.isFile && file.canWrite()) file.writeText(value)
    }
}

internal fun webcamPowerControlFile(videoNode: File): File? {
    val interfaceDir = File(videoNode, "device")
    val direct = File(interfaceDir, "power/control")
    if (direct.isFile) return direct
    val parent = File(interfaceDir, "../power/control")
    return parent.takeIf { it.isFile }
}

private val LINUX_PREFERRED_SCAN_SIZES = listOf(
    Dimension(1280, 720),
    Dimension(1024, 576),
    Dimension(960, 540),
    Dimension(800, 600),
    Dimension(640, 480),
    Dimension(1280, 960),
)
