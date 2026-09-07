package com.eventmanager.app.data.sync

import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.platform.PlatformFileManager
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Bridges the synced `institution_logo_png` setting to the device-local `email_logo.png` file.
 *
 * The whole e-mail pipeline reads the logo from disk through `PlatformFileManager`. Mirroring the
 * synced value back onto that file lets every existing e-mail code path keep working untouched
 * while the logo itself now travels between devices.
 */
object InstitutionLogoStore {

    /** 512 px PNG stays well under this; Firestore caps a document at 1 MiB. */
    const val MAX_BASE64_LENGTH = 400_000

    private var diskWriter: ((ByteArray?) -> Unit)? = null
    private var diskReader: (() -> ByteArray?)? = null

    /** Installed once by the app root, which owns the platform file manager. */
    fun installDiskWriter(writer: (ByteArray?) -> Unit) {
        diskWriter = writer
    }

    /** Installed once by the app root so callers can pull the local email logo on demand. */
    fun installDiskReader(reader: () -> ByteArray?) {
        diskReader = reader
    }

    /** The device-local email logo bytes, or null before the reader is installed / no logo. */
    fun readFromDisk(): ByteArray? = diskReader?.let { runCatching { it() }.getOrNull() }

    fun isWithinSizeLimit(base64Png: String): Boolean =
        base64Png.length <= MAX_BASE64_LENGTH

    /** No-op before the writer is installed, so early settings applies never crash. */
    @OptIn(ExperimentalEncodingApi::class)
    fun mirrorToDisk(base64Png: String) {
        val writer = diskWriter ?: return
        val trimmed = base64Png.trim()
        if (trimmed.isEmpty()) {
            runCatching { writer(null) }
            return
        }
        val bytes = runCatching { Base64.Default.decode(trimmed) }.getOrNull() ?: return
        runCatching { writer(bytes) }
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun encode(bytes: ByteArray): String = Base64.Default.encode(bytes)

    @OptIn(ExperimentalEncodingApi::class)
    fun decode(base64Png: String): ByteArray? =
        runCatching { Base64.Default.decode(base64Png.trim()) }.getOrNull()
}

/**
 * Wires the synced logo to local storage. Called once per app root, before settings are applied,
 * so a logo arriving from another device lands on disk where the e-mail code expects it.
 */
fun installInstitutionLogoBridge(platformContext: PlatformContext, settingsManager: SettingsManager) {
    InstitutionLogoStore.installDiskWriter { bytes ->
        val uri = PlatformFileManager(platformContext).saveEmailLogoBytes(bytes)
        settingsManager.saveEmailLogoUri(uri ?: "")
    }
    InstitutionLogoStore.installDiskReader {
        PlatformFileManager(platformContext).readEmailLogoBytes()
    }
    // Drop any oversized base64 that an older build may have left in Preferences — reading is
    // fine, but the next write would crash the desktop JVM (Preferences max value ~8 KiB).
    settingsManager.migrateInstitutionLogoOffPreferences()
    // A device that already holds the logo locally publishes it, so upgrading one device is
    // enough to give every other device the logo it never had.
    settingsManager.ensureInstitutionLogoPublishedFromDisk()
}
