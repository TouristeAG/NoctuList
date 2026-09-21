package com.eventmanager.app.platform.hardware

import com.github.eduramiba.webcamcapture.drivers.NativeDriver
import com.github.sarxos.webcam.Webcam
import java.awt.Dimension

/**
 * Sarxos 0.3.12 defaults to OpenIMAJ, which fails on modern macOS (UnsatisfiedLinkError).
 * The native AVFoundation driver works on current Mac/Windows/Linux releases.
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
     * Prefer 720p/1080p over the previous hardcoded 640×480. Printed card modules are
     * otherwise undersampled at typical webcam focus distance, while phone screens still
     * decode because they fill more of the frame with higher contrast.
     */
    fun applyPreferredScanResolution(webcam: Webcam) {
        val advertised = webcam.viewSizes?.toList().orEmpty()
        val hdPreferred = listOf(
            Dimension(1280, 720),
            Dimension(1920, 1080),
            Dimension(1280, 800),
            Dimension(960, 720),
        )
        val advertisedHd = hdPreferred.firstOrNull { want ->
            advertised.any { it.width == want.width && it.height == want.height }
        }
        if (advertisedHd != null) {
            webcam.viewSize = advertisedHd
            return
        }

        // Many drivers only advertise VGA even when 720p is available.
        val hd = Dimension(1280, 720)
        val appliedCustom = runCatching {
            webcam.setCustomViewSizes(hd, Dimension(640, 480))
            webcam.viewSize = hd
        }.isSuccess
        if (appliedCustom) return

        val largestUsable = advertised
            .filter { it.width in 640..1920 && it.height in 480..1080 }
            .maxByOrNull { it.width.toLong() * it.height }
            ?: advertised.maxByOrNull { it.width.toLong() * it.height }
        webcam.viewSize = largestUsable ?: Dimension(640, 480)
    }
}
