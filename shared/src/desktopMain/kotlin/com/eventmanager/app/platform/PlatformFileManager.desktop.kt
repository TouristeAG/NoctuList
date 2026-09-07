package com.eventmanager.app.platform

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

actual class PlatformFileManager actual constructor(private val context: PlatformContext) {
    private fun serviceAccountPath(): File = File(context.appDataDir, "service_account.json")

    actual fun getServiceAccountFile(): File? =
        serviceAccountPath().takeIf { it.exists() }

    actual fun saveServiceAccountJson(json: String): Boolean = runCatching {
        serviceAccountPath().writeText(json)
        true
    }.getOrDefault(false)

    actual fun readServiceAccountJson(): String? =
        serviceAccountPath().takeIf { it.exists() }?.readText()

    private fun gmailOAuthClientPath(): File = File(context.appDataDir, "gmail_oauth_client.json")

    actual fun getGmailOAuthClientFile(): File? =
        gmailOAuthClientPath().takeIf { it.exists() }

    actual fun saveGmailOAuthClientJson(json: String): Boolean = runCatching {
        gmailOAuthClientPath().writeText(json)
        true
    }.getOrDefault(false)

    actual fun readGmailOAuthClientJson(): String? =
        gmailOAuthClientPath().takeIf { it.exists() }?.readText()

    actual fun getLogsDirectory(): File =
        File(context.appDataDir, "logs").also { it.mkdirs() }

    actual fun getCacheDirectory(): File =
        File(context.appDataDir, "cache").also { it.mkdirs() }

    actual fun getUpdatesDirectory(): File =
        File(context.appDataDir, "updates").also { it.mkdirs() }

    actual suspend fun pickServiceAccountJsonFile(): String? {
        val file = NativeDesktopFileDialog.pickOpen(
            title = "Select Google Service Account JSON",
            allowedExtensions = listOf("json"),
        ) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { file.readText() }.getOrNull()
        }
    }

    actual suspend fun pickGmailOAuthClientJsonFile(): String? {
        val file = NativeDesktopFileDialog.pickOpen(
            title = "Select Gmail OAuth Client JSON",
            allowedExtensions = listOf("json"),
        ) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { file.readText() }.getOrNull()
        }
    }

    private fun emailLogoPath(): File = File(context.appDataDir, "email_logo.png")

    actual fun getEmailLogoFile(): File? = emailLogoPath().takeIf { it.exists() }

    actual fun clearEmailLogoFile(): Boolean = runCatching {
        emailLogoPath().delete()
        true
    }.getOrDefault(false)

    actual fun saveEmailLogoBytes(bytes: ByteArray?): String? {
        val destination = emailLogoPath()
        if (bytes == null) {
            runCatching { destination.delete() }
            return null
        }
        return runCatching {
            destination.parentFile?.mkdirs()
            destination.writeBytes(bytes)
            destination.absolutePath
        }.getOrNull()
    }

    actual fun readEmailLogoBytes(): ByteArray? =
        runCatching { emailLogoPath().takeIf { it.exists() }?.readBytes() }.getOrNull()

    private fun walletPassCertificatePath(): File = File(context.appDataDir, "wallet_pass_certificate.p12")

    actual fun getWalletPassCertificateFile(): File? = walletPassCertificatePath().takeIf { it.exists() }

    actual fun saveWalletPassCertificate(bytes: ByteArray): Boolean = runCatching {
        walletPassCertificatePath().writeBytes(bytes)
        true
    }.getOrDefault(false)

    actual suspend fun pickWalletPassCertificateFile(): ByteArray? {
        val file = NativeDesktopFileDialog.pickOpen(
            title = "Select Apple Wallet Pass Type ID Certificate (.p12)",
            allowedExtensions = listOf("p12", "pfx"),
        ) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { file.readBytes() }.getOrNull()
        }
    }

    actual suspend fun pickEmailLogoImageFile(): String? {
        val selected = NativeDesktopFileDialog.pickOpen(
            title = "Select Email Logo Image",
            allowedExtensions = listOf("png", "jpg", "jpeg", "webp", "gif"),
        ) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching {
                val buffered = javax.imageio.ImageIO.read(selected) ?: return@runCatching null
                val destination = emailLogoPath()
                destination.parentFile?.mkdirs()
                javax.imageio.ImageIO.write(buffered, "png", destination)
                destination.absolutePath
            }.getOrNull()
        }
    }

    actual fun getAuthRelatedFilesToErase(): List<File> {
        val base = context.appDataDir
        return listOf(
            // Firebase Google OAuth token store (FileDataStoreFactory)
            File(base, "firebase_auth_tokens"),
            // GitLive / Firebase Platform KV store (id_token, refresh_token JSON)
            File(base, "firebase_platform_kv"),
            // Plain-text Firebase auth account cache
            File(base, "firebase_auth_account.properties"),
            // Cached Firebase id_token written by DesktopFirebaseAuthService
            File(base, "firebase_auth_id_token.txt"),
            // Gmail OAuth token store (FileDataStoreFactory)
            File(base, "gmail_tokens"),
            // Gmail account properties cache
            File(base, "gmail_account.properties"),
            // AES-256-GCM encrypted credential store (Firebase API key, secrets, etc.)
            File(base, "secure_credentials.enc"),
            // Debug log files (separate from the regular logs/ directory)
            File(base, "debug_logs"),
        )
    }

    actual suspend fun saveFileToUserLocation(
        sourceFile: File,
        suggestedName: String,
        mimeType: String,
    ): Boolean = withContext(Dispatchers.IO) {
        val extension = extensionForMimeType(mimeType, suggestedName)
        val destination = NativeDesktopFileDialog.pickSave(
            title = "Save as",
            suggestedName = suggestedName,
            forcedExtension = extension,
        ) ?: return@withContext false
        runCatching {
            sourceFile.copyTo(destination, overwrite = true)
            true
        }.getOrDefault(false)
    }

    private fun extensionForMimeType(mimeType: String, suggestedName: String): String {
        val fromName = suggestedName.substringAfterLast('.', missingDelimiterValue = "")
            .lowercase()
            .takeIf { it.isNotBlank() && it.length <= 5 }
        if (fromName != null) return fromName
        return when (mimeType.lowercase()) {
            "application/pdf" -> "pdf"
            "image/jpeg", "image/jpg" -> "jpg"
            "image/png" -> "png"
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "xlsx"
            else -> "bin"
        }
    }
}
