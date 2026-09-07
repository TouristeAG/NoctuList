package com.eventmanager.app.data.sync

import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.platform.PlatformFileManager
import com.eventmanager.app.platform.appDataDir
import com.eventmanager.app.data.remote.DesktopLoopbackOAuthResult
import com.eventmanager.app.data.remote.DesktopOAuthLoopbackReceiver
import com.eventmanager.app.platform.openExternalBrowser
import com.google.api.client.auth.oauth2.AuthorizationCodeFlow
import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.GenericUrl
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import com.google.api.services.gmail.GmailScopes
import com.google.auth.http.HttpCredentialsAdapter
import com.google.auth.oauth2.GoogleCredentials
import com.google.auth.oauth2.ServiceAccountCredentials
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.StringReader

class DesktopGmailAuth(private val context: PlatformContext) {
    private val fileManager = PlatformFileManager(context)
    private val settingsManager = settingsManagerFor(context)
    private val accountFile = File(context.appDataDir, "gmail_account.properties")
    private val tokenDir = File(context.appDataDir, "gmail_tokens").also { it.mkdirs() }

    var lastSignInError: String? = null
        private set

    private fun useServiceAccountMode(): Boolean =
        settingsManager.getBackendType() != com.eventmanager.app.data.remote.BackendType.FIREBASE &&
            settingsManager.isGmailUseServiceAccount()

    fun hasOAuthClientConfigured(): Boolean =
        if (useServiceAccountMode()) {
            fileManager.getServiceAccountFile() != null
        } else {
            fileManager.getGmailOAuthClientFile() != null
        }

    fun isSignedIn(): Boolean {
        if (useServiceAccountMode()) {
            val sender = settingsManager.getGmailServiceAccountSenderEmail().trim()
            return fileManager.getServiceAccountFile() != null &&
                sender.isNotBlank() &&
                accountFile.exists()
        }
        return accountFile.exists() && loadOAuthCredential() != null
    }

    fun getSignedInAccountEmail(): String? =
        if (!accountFile.exists()) null else accountFile.readLines().firstOrNull { it.startsWith("email=") }?.substringAfter("=")

    fun getSendUserId(): String =
        if (useServiceAccountMode()) {
            settingsManager.getGmailServiceAccountSenderEmail().trim()
        } else {
            "me"
        }

    suspend fun signIn(): Boolean = withContext(Dispatchers.IO) {
        lastSignInError = null
        try {
            if (useServiceAccountMode()) {
                return@withContext signInWithServiceAccount()
            }
            signInWithOAuth()
        } catch (e: Exception) {
            AppLogger.e("DesktopGmailAuth", "Gmail sign-in failed", e)
            lastSignInError = e.message ?: e.javaClass.simpleName
            false
        }
    }

    private fun signInWithServiceAccount(): Boolean {
        if (fileManager.getServiceAccountFile() == null) {
            lastSignInError = "missing_service_account"
            return false
        }
        val sender = settingsManager.getGmailServiceAccountSenderEmail().trim()
        if (sender.isBlank()) {
            lastSignInError = "missing_sender_email"
            return false
        }
        val initializer = buildServiceAccountInitializer(sender) ?: run {
            lastSignInError = "invalid_service_account"
            return false
        }
        // Sender email is configured by the user; gmail.send scope is enough for sending.
        accountFile.writeText("email=$sender")
        AppLogger.i("DesktopGmailAuth", "Service account Gmail configured for $sender")
        return true
    }

    private fun signInWithOAuth(): Boolean {
        if (!hasOAuthClientConfigured()) {
            lastSignInError = "missing_oauth_client"
            return false
        }

        var credential = loadOAuthCredential() ?: authorizeNewCredential() ?: run {
            if (lastSignInError == null) {
                lastSignInError = "authorization_failed"
            }
            return false
        }

        var email = fetchOAuthUserEmail(credential)
        if (email.isNullOrBlank()) {
            clearOAuthTokens()
            credential = authorizeNewCredential() ?: run {
                if (lastSignInError == null) {
                    lastSignInError = "authorization_failed"
                }
                return false
            }
            email = fetchOAuthUserEmail(credential)
        }

        if (email.isNullOrBlank()) {
            lastSignInError = "email_lookup_failed"
            return false
        }

        accountFile.writeText("email=$email")
        AppLogger.i("DesktopGmailAuth", "Signed in as $email")
        return true
    }

    private fun clearOAuthTokens() {
        tokenDir.deleteRecursively()
        tokenDir.mkdirs()
    }

    private fun fetchOAuthUserEmail(credential: Credential): String? = runCatching {
        val transport = GoogleNetHttpTransport.newTrustedTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()
        val response = transport.createRequestFactory(credential)
            .buildGetRequest(GenericUrl("https://www.googleapis.com/oauth2/v2/userinfo"))
            .execute()
        @Suppress("UNCHECKED_CAST")
        val payload = jsonFactory.fromString(response.parseAsString(), Map::class.java) as Map<String, Any>
        payload["email"] as? String
    }.getOrNull()

    fun signOut() {
        accountFile.delete()
        if (!useServiceAccountMode()) {
            clearOAuthTokens()
        }
        lastSignInError = null
    }

    internal fun loadRequestInitializer(): HttpRequestInitializer? {
        if (useServiceAccountMode()) {
            val sender = settingsManager.getGmailServiceAccountSenderEmail().trim()
            return if (sender.isNotBlank()) buildServiceAccountInitializer(sender) else null
        }
        return loadOAuthCredential()
    }

    internal fun loadOAuthCredential(): Credential? = runCatching {
        val flow = buildOAuthFlow() ?: return null
        flow.loadCredential("user")?.takeIf { it.refreshToken != null || it.accessToken != null }
    }.getOrNull()

    private fun authorizeNewCredential(): Credential? {
        val flow = buildOAuthFlow() ?: run {
            lastSignInError = "invalid_oauth_client"
            return null
        }

        val receiver = DesktopOAuthLoopbackReceiver(
            ports = listOf(8888, 8765, 9090, 8889),
        )
        return try {
            val redirectUri = receiver.start()
            val authUrl = flow.newAuthorizationUrl()
                .setRedirectUri(redirectUri)
                .build()
            openExternalBrowser(authUrl)
            when (val loopback = receiver.waitForResult()) {
                is DesktopLoopbackOAuthResult.Success -> {
                    val tokenResponse = flow.newTokenRequest(loopback.code)
                        .setRedirectUri(redirectUri)
                        .execute()
                    flow.createAndStoreCredential(tokenResponse, "user")
                }
                is DesktopLoopbackOAuthResult.OAuthError -> {
                    lastSignInError = loopback.description ?: loopback.error
                    null
                }
                DesktopLoopbackOAuthResult.TimedOut -> {
                    lastSignInError = "authorization_timed_out"
                    null
                }
                DesktopLoopbackOAuthResult.Cancelled -> {
                    lastSignInError = "authorization_cancelled"
                    null
                }
            }
        } catch (e: Exception) {
            lastSignInError = e.message ?: "authorization_failed"
            AppLogger.w("DesktopGmailAuth", "OAuth local receiver failed: ${e.message}")
            null
        } finally {
            receiver.stop()
        }
    }

    private fun buildServiceAccountInitializer(userEmail: String): HttpRequestInitializer? = runCatching {
        val keyFile = fileManager.getServiceAccountFile() ?: return null
        val credentials = GoogleCredentials.fromStream(FileInputStream(keyFile))
            .createScoped(listOf(GmailScopes.GMAIL_SEND))
        val delegated = when (credentials) {
            is ServiceAccountCredentials -> credentials.createDelegated(userEmail)
            else -> credentials
        }
        HttpCredentialsAdapter(delegated)
    }.getOrNull()

    private fun buildOAuthFlow(): AuthorizationCodeFlow? = runCatching {
        val json = fileManager.readGmailOAuthClientJson() ?: return null
        if (!isValidGmailOAuthClientJson(json)) {
            lastSignInError = "invalid_oauth_client"
            return null
        }
        val secrets = GoogleClientSecrets.load(GsonFactory.getDefaultInstance(), StringReader(json))
        GoogleAuthorizationCodeFlow.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            secrets,
            OAUTH_SCOPES
        )
            .setDataStoreFactory(FileDataStoreFactory(tokenDir))
            .setAccessType("offline")
            .build()
    }.getOrNull()

    companion object {
        private const val USERINFO_EMAIL_SCOPE = "https://www.googleapis.com/auth/userinfo.email"
        private val OAUTH_SCOPES = listOf(GmailScopes.GMAIL_SEND, USERINFO_EMAIL_SCOPE)

        fun isValidGmailOAuthClientJson(json: String): Boolean {
            val normalized = json.trim()
            if (normalized.isEmpty()) return false
            val hasClientShape = normalized.contains("\"installed\"") || normalized.contains("\"web\"")
            val hasClientId = normalized.contains("\"client_id\"")
            return hasClientShape && hasClientId
        }
    }
}
