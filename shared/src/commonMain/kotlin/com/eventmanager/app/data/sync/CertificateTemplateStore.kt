package com.eventmanager.app.data.sync

import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.platform.PlatformFileManager

/**
 * Keeps the volunteer certificate template (`certificate_template.json`) on disk.
 *
 * Same idea as [InstitutionLogoStore]: the template JSON can be far larger than the ~8 KiB that
 * desktop Preferences accept, so Preferences only holds a marker and the JSON lives in a file.
 */
object CertificateTemplateStore {

    private var diskWriter: ((String?) -> Unit)? = null
    private var diskReader: (() -> String?)? = null

    /** Installed once by the app root, which owns the platform file manager. */
    fun installDiskWriter(writer: (String?) -> Unit) {
        diskWriter = writer
    }

    fun installDiskReader(reader: () -> String?) {
        diskReader = reader
    }

    /** The stored template JSON, or null before the reader is installed or when nothing is saved. */
    fun readFromDisk(): String? = diskReader?.let { runCatching { it() }.getOrNull() }

    /** No-op before the writer is installed. A blank [json] removes the file. */
    fun mirrorToDisk(json: String) {
        val writer = diskWriter ?: return
        runCatching { writer(json.takeIf { it.isNotBlank() }) }
    }
}

/** Wires the certificate template to local storage. Called with the logo bridge by each app root. */
fun installCertificateTemplateBridge(platformContext: PlatformContext) {
    CertificateTemplateStore.installDiskWriter { json ->
        PlatformFileManager(platformContext).saveCertificateTemplateJson(json)
    }
    CertificateTemplateStore.installDiskReader {
        PlatformFileManager(platformContext).readCertificateTemplateJson()
    }
}
