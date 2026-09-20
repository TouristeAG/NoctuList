package com.eventmanager.app.data.drive

import com.eventmanager.app.data.remote.InstitutionGoogleWebOAuth
import com.eventmanager.app.platform.PlatformContext
import com.google.api.client.http.HttpRequestInitializer

/**
 * Access to Google Drive/Docs/Sheets/People **as the signed-in user**.
 *
 * Distinct from [com.eventmanager.app.data.sync.GoogleSheetsService], which authenticates with a
 * service account and therefore cannot see a user's own documents.
 */
interface GoogleUserApiAuth {
    /** False on platforms that cannot yet hand out a refreshable user credential. */
    val isSupported: Boolean

    /** Scopes Google actually granted at the last sign-in — may be a subset of what was asked. */
    fun grantedScopes(): Set<String>

    /** Auto-refreshing initializer for the Google API clients, or null when not signed in. */
    fun requestInitializer(): HttpRequestInitializer?

    /** Interactive incremental consent, used when the login-time grant was missing scopes. */
    suspend fun requestDriveConsent(): DriveConsentResult
}

sealed class DriveConsentResult {
    data class Granted(val scopes: Set<String>) : DriveConsentResult()
    data class Denied(val message: String) : DriveConsentResult()
    data object Unsupported : DriveConsentResult()
}

/** Which parts of the import a given grant can actually drive. */
data class DriveImportCapabilities(
    val canReadDocs: Boolean,
    val canReadSheets: Boolean,
    val canListRecentFiles: Boolean,
    val canEnrichContacts: Boolean,
) {
    /** Nothing works without at least one document type. */
    val canImport: Boolean get() = canReadDocs || canReadSheets

    companion object {
        val NONE = DriveImportCapabilities(
            canReadDocs = false,
            canReadSheets = false,
            canListRecentFiles = false,
            canEnrichContacts = false,
        )

        fun from(grantedScopes: Set<String>): DriveImportCapabilities = DriveImportCapabilities(
            canReadDocs = InstitutionGoogleWebOAuth.SCOPE_DOCS_READONLY in grantedScopes,
            canReadSheets = InstitutionGoogleWebOAuth.SCOPE_SHEETS_READONLY in grantedScopes,
            canListRecentFiles = InstitutionGoogleWebOAuth.SCOPE_DRIVE_METADATA_READONLY in grantedScopes,
            canEnrichContacts = InstitutionGoogleWebOAuth.SCOPE_CONTACTS_READONLY in grantedScopes,
        )
    }
}

class UnsupportedGoogleUserApiAuth : GoogleUserApiAuth {
    override val isSupported: Boolean = false
    override fun grantedScopes(): Set<String> = emptySet()
    override fun requestInitializer(): HttpRequestInitializer? = null
    override suspend fun requestDriveConsent(): DriveConsentResult = DriveConsentResult.Unsupported
}

expect fun createGoogleUserApiAuth(platformContext: PlatformContext?): GoogleUserApiAuth
