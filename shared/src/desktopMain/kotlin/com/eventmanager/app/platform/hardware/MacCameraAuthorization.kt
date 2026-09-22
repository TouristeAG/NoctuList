package com.eventmanager.app.platform.hardware

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.File

internal enum class MacCameraAccessResult {
    Authorized,
    Denied,
    Skipped,
    ;

    companion object {
        fun fromNative(code: Int): MacCameraAccessResult = when (code) {
            0 -> Authorized
            1 -> Denied
            else -> Skipped
        }
    }
}

/**
 * macOS TCC camera authorization + AVFoundation capture fallback.
 *
 * NativeDriver lists devices *before* asking for permission. On recent macOS
 * (especially Apple Silicon), `AVCaptureDevice.devices(for:)` then returns an
 * empty list and never shows the system prompt — so the scanner thinks there
 * is no webcam. We request access first, then enumerate / capture.
 */
internal object MacCameraAuthorization {
    private val macOs: Boolean =
        System.getProperty("os.name").orEmpty().startsWith("Mac OS")

    fun ensureAccess(): MacCameraAccessResult {
        if (!macOs) return MacCameraAccessResult.Skipped
        val lib = MacCameraNative.libOrNull() ?: return MacCameraAccessResult.Skipped
        return MacCameraAccessResult.fromNative(lib.requestCameraAccess())
    }

    fun deviceCount(): Int {
        if (!macOs) return 0
        val lib = MacCameraNative.libOrNull() ?: return 0
        return runCatching { lib.cameraDeviceCount() }.getOrDefault(0)
    }
}

internal class MacAvfFrameGrabber : AutoCloseable {
    private var started = false
    private var buffer: ByteArray = ByteArray(1280 * 720 * 3)
    private var nativeBuf: com.sun.jna.Memory = com.sun.jna.Memory(buffer.size.toLong())

    fun start(preferredWidth: Int = 1280, preferredHeight: Int = 720): Boolean {
        val lib = MacCameraNative.libOrNull() ?: return false
        if (lib.startCameraCapture(preferredWidth, preferredHeight) != 0) return false
        started = true
        return true
    }

    fun grab(): BufferedImage? {
        if (!started) return null
        val lib = MacCameraNative.libOrNull() ?: return null
        val width = IntByReference()
        val height = IntByReference()
        val result = lib.grabCameraBgr(nativeBuf, nativeBuf.size().toInt(), width, height)
        if (result == -1) {
            buffer = ByteArray(buffer.size * 2)
            nativeBuf = com.sun.jna.Memory(buffer.size.toLong())
            return null
        }
        if (result != 1) return null
        val w = width.value
        val h = height.value
        if (w <= 0 || h <= 0) return null
        val needed = w * h * 3
        if (needed > buffer.size) {
            buffer = ByteArray(needed)
            nativeBuf = com.sun.jna.Memory(needed.toLong())
            return null
        }
        nativeBuf.read(0, buffer, 0, needed)
        return bgrToImage(buffer, w, h)
    }

    override fun close() {
        if (!started) return
        started = false
        MacCameraNative.libOrNull()?.stopCameraCapture()
    }

    private fun bgrToImage(packedBgr: ByteArray, width: Int, height: Int): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
        val data = (image.raster.dataBuffer as DataBufferByte).data
        System.arraycopy(packedBgr, 0, data, 0, width * height * 3)
        return image
    }
}

private interface MacCameraNativeLib : Library {
    fun requestCameraAccess(): Int
    fun cameraAuthorizationStatus(): Int
    fun cameraDeviceCount(): Int
    fun startCameraCapture(preferredWidth: Int, preferredHeight: Int): Int
    fun stopCameraCapture()
    fun grabCameraBgr(dst: Pointer, dstCapacity: Int, outWidth: IntByReference, outHeight: IntByReference): Int
}

private object MacCameraNative {
    private const val LIB_NAME = "CameraAuthorizationEngine"

    private val instance: MacCameraNativeLib? by lazy {
        if (!System.getProperty("os.name").orEmpty().startsWith("Mac OS")) return@lazy null
        runCatching { Native.load(extractDylib(), MacCameraNativeLib::class.java) }.getOrNull()
    }

    fun libOrNull(): MacCameraNativeLib? = instance

    private fun extractDylib(): String {
        val resourceName = "$LIB_NAME.dylib"
        val loaders = listOfNotNull(
            MacCameraNativeLib::class.java.classLoader,
            Thread.currentThread().contextClassLoader,
            ClassLoader.getSystemClassLoader(),
        )
        val stream = loaders.firstNotNullOfOrNull { it.getResourceAsStream(resourceName) }
            ?: error("Native camera library not found on classpath: $resourceName")
        val tmp = File.createTempFile(LIB_NAME, ".dylib")
        tmp.deleteOnExit()
        stream.use { input -> tmp.outputStream().use { output -> input.copyTo(output) } }
        return tmp.absolutePath
    }
}
