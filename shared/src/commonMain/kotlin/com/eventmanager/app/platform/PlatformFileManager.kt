package com.eventmanager.app.platform

import java.io.File

/**
 * Local file operations for service account keys, logs, exports, and updates.
 */
expect class PlatformFileManager(context: PlatformContext) {
    fun getServiceAccountFile(): File?
    fun saveServiceAccountJson(json: String): Boolean
    fun readServiceAccountJson(): String?
    fun getGmailOAuthClientFile(): File?
    fun saveGmailOAuthClientJson(json: String): Boolean
    fun readGmailOAuthClientJson(): String?
    fun getLogsDirectory(): File
    fun getCacheDirectory(): File
    fun getUpdatesDirectory(): File
    suspend fun pickServiceAccountJsonFile(): String?
    suspend fun pickGmailOAuthClientJsonFile(): String?
    suspend fun pickEmailLogoImageFile(): String?
    fun getEmailLogoFile(): File?
    fun clearEmailLogoFile(): Boolean

    /**
     * Writes the institution logo synced from Firebase onto the canonical local file, or clears
     * it when [bytes] is null. Returns the URI the platform e-mail code can read it back from.
     */
    fun saveEmailLogoBytes(bytes: ByteArray?): String?

    /** Raw bytes of the current local logo, for uploading it as the synced institution logo. */
    fun readEmailLogoBytes(): ByteArray?
    fun getWalletPassCertificateFile(): File?
    fun saveWalletPassCertificate(bytes: ByteArray): Boolean
    suspend fun pickWalletPassCertificateFile(): ByteArray?
    suspend fun saveFileToUserLocation(sourceFile: File, suggestedName: String, mimeType: String): Boolean

    /**
     * Returns auth-related files and directories that should be deleted on factory reset.
     * These include OAuth token stores, Firebase auth token files, Gmail token directories,
     * and the encrypted credential store — files that survive [FirebaseAuthBridge.signOut]
     * and would auto-restore the previous session on the next launch.
     *
     * Android: returns an empty list (the platform Firebase SDK manages its own storage).
     * Desktop: returns the specific files/dirs under the app data directory.
     */
    fun getAuthRelatedFilesToErase(): List<File>
}
