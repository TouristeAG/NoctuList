package com.eventmanager.app.data.drive

import com.eventmanager.app.data.remote.DesktopGoogleCredentialStore
import com.eventmanager.app.data.remote.DesktopLoopbackOAuthResult
import com.eventmanager.app.data.remote.DesktopOAuthLoopbackReceiver
import com.eventmanager.app.data.remote.InstitutionGoogleWebOAuth
import com.eventmanager.app.data.sync.AppLogger
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.platform.openExternalBrowser
import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.StringReader

private const val TAG = "DriveUserApiAuth"

/**
 * Reuses the credential the desktop Firebase sign-in already stored in
 * [DesktopGoogleCredentialStore], so an institution that enabled Drive import gets API access
 * from the normal login with no extra step. [requestDriveConsent] is the recovery path when the
 * login-time grant came back without the Drive scopes.
 */
class DesktopGoogleUserApiAuth(
    private val platformContext: PlatformContext,
) : GoogleUserApiAuth {
    private val settings by lazy { SettingsManager(platformContext) }
    private val tokenDir = DesktopGoogleCredentialStore.tokenDir(platformContext)

    override val isSupported: Boolean = true

    override fun grantedScopes(): Set<String> = settings.getDriveImportGrantedScopes()

    override fun requestInitializer(): HttpRequestInitializer? = loadCredential()

    private fun buildFlow(scopes: List<String>): GoogleAuthorizationCodeFlow? {
        val clientId = settings.getFirebaseWebClientId().trim()
        val clientSecret = settings.getFirebaseWebClientSecret().trim()
        if (clientId.isBlank() || clientSecret.isBlank()) return null
        val secrets = GoogleClientSecrets.load(
            GsonFactory.getDefaultInstance(),
            StringReader(clientSecretsJson(clientId, clientSecret)),
        )
        return GoogleAuthorizationCodeFlow.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            secrets,
            scopes,
        )
            .setDataStoreFactory(FileDataStoreFactory(tokenDir))
            .setAccessType("offline")
            .build()
    }

    private fun loadCredential(): Credential? = runCatching {
        val flow = buildFlow(InstitutionGoogleWebOAuth.scopesFor(driveImport = true)) ?: return null
        val credential = flow.loadCredential(DesktopGoogleCredentialStore.USER_ID) ?: return null
        if (credential.refreshToken == null && credential.accessToken == null) return null
        // Refresh eagerly: a stale token would otherwise surface as a confusing 401 mid-import.
        val expiresIn = credential.expiresInSeconds
        if (expiresIn != null && expiresIn < 60 && credential.refreshToken != null) {
            runCatching { credential.refreshToken() }
                .onFailure { AppLogger.w(TAG, "Token refresh failed: ${it.message}") }
        }
        credential
    }.onFailure { AppLogger.w(TAG, "Could not load Google credential: ${it.message}") }.getOrNull()

    override suspend fun requestDriveConsent(): DriveConsentResult = withContext(Dispatchers.IO) {
        val scopes = InstitutionGoogleWebOAuth.scopesFor(driveImport = true)
        val flow = buildFlow(scopes) ?: return@withContext DriveConsentResult.Denied(
            "Institution Web client ID and secret are required before granting Drive access.",
        )
        val receiver = DesktopOAuthLoopbackReceiver()
        val redirectUri = try {
            receiver.start()
        } catch (e: Exception) {
            return@withContext DriveConsentResult.Denied(
                e.message ?: "Could not start the local OAuth callback server.",
            )
        }
        try {
            val authUrl = flow.newAuthorizationUrl()
                .setRedirectUri(redirectUri)
                .set("prompt", "consent")
                // Keep the identity scopes the Firebase session still depends on.
                .set("include_granted_scopes", "true")
                .build()
            runCatching { openExternalBrowser(authUrl) }.getOrElse {
                return@withContext DriveConsentResult.Denied(
                    "Could not open the browser. Open this URL manually:\n$authUrl",
                )
            }
            when (val loopback = receiver.awaitResult()) {
                is DesktopLoopbackOAuthResult.Success -> {
                    val tokenResponse = flow.newTokenRequest(loopback.code)
                        .setRedirectUri(redirectUri)
                        .execute()
                    flow.createAndStoreCredential(tokenResponse, DesktopGoogleCredentialStore.USER_ID)
                    val granted = InstitutionGoogleWebOAuth.parseGrantedScopes(tokenResponse.scope)
                        .ifEmpty { scopes.toSet() }
                    settings.setDriveImportGrantedScopes(granted)
                    val missing = InstitutionGoogleWebOAuth.DRIVE_IMPORT_SCOPES.filterNot { it in granted }
                    settings.setDriveImportScopeError(
                        if (missing.isEmpty()) "" else "Scopes not granted: ${missing.joinToString(", ")}",
                    )
                    DriveConsentResult.Granted(granted)
                }
                is DesktopLoopbackOAuthResult.OAuthError -> {
                    val detail = loopback.description?.takeIf { it.isNotBlank() } ?: loopback.error
                    settings.setDriveImportScopeError(detail)
                    DriveConsentResult.Denied(detail)
                }
                DesktopLoopbackOAuthResult.TimedOut ->
                    DriveConsentResult.Denied("Authorization timed out. Try again.")
                DesktopLoopbackOAuthResult.Cancelled ->
                    DriveConsentResult.Denied("Authorization cancelled.")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Drive consent failed", e)
            DriveConsentResult.Denied(e.message ?: "Drive authorization failed")
        } finally {
            runCatching { receiver.stop() }
        }
    }

    private fun clientSecretsJson(clientId: String, clientSecret: String): String {
        val uris = InstitutionGoogleWebOAuth.LOOPBACK_REDIRECT_URIS.joinToString(",") { jsonQuote(it) }
        return """{"web":{"client_id":${jsonQuote(clientId)},""" +
            """"client_secret":${jsonQuote(clientSecret)},"redirect_uris":[$uris]}}"""
    }

    private fun jsonQuote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

actual fun createGoogleUserApiAuth(platformContext: PlatformContext?): GoogleUserApiAuth {
    val ctx = platformContext ?: return UnsupportedGoogleUserApiAuth()
    return DesktopGoogleUserApiAuth(ctx)
}
