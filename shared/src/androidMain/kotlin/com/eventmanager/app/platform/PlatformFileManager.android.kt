package com.eventmanager.app.platform

import com.eventmanager.app.data.sync.FileManager
import java.io.File

actual class PlatformFileManager actual constructor(private val context: PlatformContext) {
    private val delegate = FileManager(context.androidContext)
    private val serviceAccountFileName = "service_account_key.json"

    actual fun getServiceAccountFile(): File? {
        val path = delegate.getServiceAccountKeyPath() ?: return null
        return File(path)
    }

    actual fun saveServiceAccountJson(json: String): Boolean = runCatching {
        val assetsDir = File(context.androidContext.filesDir, "assets").also { it.mkdirs() }
        File(assetsDir, serviceAccountFileName).writeText(json)
        true
    }.getOrDefault(false)

    actual fun readServiceAccountJson(): String? = getServiceAccountFile()?.readText()

    actual fun getGmailOAuthClientFile(): File? = null

    actual fun saveGmailOAuthClientJson(json: String): Boolean = false

    actual fun readGmailOAuthClientJson(): String? = null

    actual fun getLogsDirectory(): File =
        File(context.androidContext.filesDir, "logs").also { it.mkdirs() }

    actual fun getCacheDirectory(): File = context.androidContext.cacheDir

    actual fun getUpdatesDirectory(): File =
        File(context.androidContext.cacheDir, "updates").also { it.mkdirs() }

    actual suspend fun pickServiceAccountJsonFile(): String? = null

    actual suspend fun pickGmailOAuthClientJsonFile(): String? = null

    actual suspend fun pickEmailLogoImageFile(): String? = null

    actual fun getEmailLogoFile(): File? = null

    actual fun clearEmailLogoFile(): Boolean = false

    private fun emailLogoPath(): File = File(context.androidContext.filesDir, "email_logo.png")

    /** A `file://` URI, because the e-mail code reads the logo through the content resolver. */
    actual fun saveEmailLogoBytes(bytes: ByteArray?): String? {
        val destination = emailLogoPath()
        if (bytes == null) {
            runCatching { destination.delete() }
            return null
        }
        return runCatching {
            destination.parentFile?.mkdirs()
            destination.writeBytes(bytes)
            android.net.Uri.fromFile(destination).toString()
        }.getOrNull()
    }

    actual fun readEmailLogoBytes(): ByteArray? =
        runCatching { emailLogoPath().takeIf { it.exists() }?.readBytes() }.getOrNull()

    private fun walletPassCertificatePath(): File =
        File(context.androidContext.filesDir, "wallet_pass_certificate.p12")

    actual fun getWalletPassCertificateFile(): File? =
        walletPassCertificatePath().takeIf { it.exists() }

    actual fun saveWalletPassCertificate(bytes: ByteArray): Boolean = runCatching {
        walletPassCertificatePath().writeBytes(bytes)
        true
    }.getOrDefault(false)

    actual suspend fun pickWalletPassCertificateFile(): ByteArray? = null

    actual suspend fun saveFileToUserLocation(
        sourceFile: File,
        suggestedName: String,
        mimeType: String,
    ): Boolean = false

    // Android Firebase SDK manages its own auth token storage internally; we have no
    // additional files to erase beyond what signOut() and clearAllData() handle.
    actual fun getAuthRelatedFilesToErase(): List<File> = emptyList()
}
