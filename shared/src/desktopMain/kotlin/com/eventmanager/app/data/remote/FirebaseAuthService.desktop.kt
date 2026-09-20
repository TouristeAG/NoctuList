package com.eventmanager.app.data.remote

import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.platform.appDataDir
import com.eventmanager.app.platform.openExternalBrowser
import com.google.api.client.auth.oauth2.Credential
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets
import com.google.api.client.googleapis.auth.oauth2.GoogleTokenResponse
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport
import com.google.api.client.http.GenericUrl
import com.google.api.client.json.gson.GsonFactory
import com.google.api.client.util.store.FileDataStoreFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.StringReader

/** Result of one interactive authorization round-trip, so scope failures stay distinguishable. */
internal sealed class DesktopAuthorizeOutcome {
    data class Success(
        val credential: Credential,
        val idToken: String?,
        val grantedScopes: Set<String>,
    ) : DesktopAuthorizeOutcome()

    /** Google refused the requested scope set — retryable with fewer scopes. */
    data class ScopeRejected(val detail: String) : DesktopAuthorizeOutcome()

    data class Failure(val message: String) : DesktopAuthorizeOutcome()
}

/** Where the desktop sign-in parks its refreshable Google credential. */
internal object DesktopGoogleCredentialStore {
    const val USER_ID = "firebase-user"
    fun tokenDir(platformContext: PlatformContext): File =
        File(platformContext.appDataDir, "firebase_auth_tokens").also { it.mkdirs() }
}

/**
 * Desktop Google OAuth → GitLive Firebase Auth.
 * Always runs an interactive OAuth code exchange so an OpenID [id_token] is present
 * (refresh-only credentials never include id_token, which Firebase Auth requires).
 */
class DesktopFirebaseAuthService(
    private val platformContext: PlatformContext,
) : FirebaseAuthService {
    private val settings by lazy { SettingsManager(platformContext) }
    private val accountFile = File(platformContext.appDataDir, "firebase_auth_account.properties")
    private val tokenDir = DesktopGoogleCredentialStore.tokenDir(platformContext)
    private val idTokenFile = File(platformContext.appDataDir, "firebase_auth_id_token.txt")

    override suspend fun signInWithGoogle(): FirebaseAuthResult = withContext(Dispatchers.IO) {
        try {
            val options = FirebaseOptionsReader.fromSettings(settings)
            if (options == null) {
                return@withContext FirebaseAuthResult.Error(
                    "Firebase project options incomplete. Fill Project ID, Application ID and API key " +
                        "(Web app from the same Google Cloud project as Sheets), then try Sign-In again.",
                )
            }
            val initialized = FirebaseBootstrap.ensureInitialized(platformContext, options)
            if (!initialized) {
                val detail = FirebaseBootstrap.lastFailureMessage().orEmpty()
                return@withContext FirebaseAuthResult.Error(
                    if (detail.isNotBlank()) {
                        "Firebase failed to initialize: $detail"
                    } else {
                        "Firebase failed to initialize. Check Project ID, Application ID and API key, then retry."
                    },
                )
            }
            val webClientId = settings.getFirebaseWebClientId().trim()
            val webClientSecret = settings.getFirebaseWebClientSecret().trim()
            if (webClientId.isBlank() || webClientSecret.isBlank()) {
                return@withContext FirebaseAuthResult.Error(
                    "Desktop Firebase Sign-In uses the institution Web client ID + Client secret " +
                        "(not the developer Gmail OAuth JSON). " +
                        "In the institution Firebase/Google Cloud project → APIs & Services → Credentials → " +
                        "your OAuth 2.0 Web client: copy Client ID and Client secret into Firebase settings. " +
                        "Authorized redirect URIs must include the exact localhost callbacks with ports " +
                        "(see ? guide) — e.g. http://localhost:8889/Callback",
                )
            }
            // Reject a bad API key before opening the browser (OAuth success is useless otherwise).
            DesktopFirebaseGoogleRestSignIn.probeApiKey(settings.getFirebaseApiKey())
                ?.let { return@withContext it }
            val secrets = GoogleClientSecrets.load(
                GsonFactory.getDefaultInstance(),
                StringReader(buildInstitutionWebClientSecretsJson(webClientId, webClientSecret)),
            )

            val wantsDriveImport = settings.isDriveImportEnabled()
            var authorized = authorizeInteractively(
                secrets = secrets,
                scopes = InstitutionGoogleWebOAuth.scopesFor(wantsDriveImport),
            )
            // An institution that has not enabled the Drive/Docs/Sheets APIs in its own Cloud
            // project gets the whole request rejected. Sign-in must still work for them.
            var scopeRejection: String? = null
            if (wantsDriveImport && authorized is DesktopAuthorizeOutcome.ScopeRejected) {
                scopeRejection = (authorized as DesktopAuthorizeOutcome.ScopeRejected).detail
                authorized = authorizeInteractively(
                    secrets = secrets,
                    scopes = InstitutionGoogleWebOAuth.OAUTH_SCOPES,
                )
            }

            val success = when (val outcome = authorized) {
                is DesktopAuthorizeOutcome.Success -> outcome
                is DesktopAuthorizeOutcome.ScopeRejected ->
                    return@withContext FirebaseAuthResult.Error("Google Sign-In failed: ${outcome.detail}")
                is DesktopAuthorizeOutcome.Failure ->
                    return@withContext FirebaseAuthResult.Error(outcome.message)
            }

            if (wantsDriveImport) {
                val granted = success.grantedScopes
                val missing = InstitutionGoogleWebOAuth.DRIVE_IMPORT_SCOPES.filterNot { it in granted }
                settings.setDriveImportGrantedScopes(granted)
                settings.setDriveImportScopeError(
                    when {
                        scopeRejection != null -> scopeRejection
                        missing.isEmpty() -> ""
                        else -> "Scopes not granted: ${missing.joinToString(", ")}"
                    },
                )
            } else {
                settings.setDriveImportGrantedScopes(emptySet())
                settings.setDriveImportScopeError("")
            }

            val idToken: String? = success.idToken
            val credential: Credential = success.credential
            val accessToken = credential.accessToken
            val resolvedEmail = fetchOAuthUserEmail(credential)
                ?: return@withContext FirebaseAuthResult.Error("Could not resolve Google account email")

            val resolvedIdToken = idToken?.takeIf { it.isNotBlank() }
                ?: idTokenFile.takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotBlank() }

            if (resolvedIdToken.isNullOrBlank()) {
                return@withContext FirebaseAuthResult.Error(
                    "Google returned no OpenID id_token after browser login. " +
                        "Use a Desktop OAuth client from the same Cloud project as Firebase " +
                        "(APIs & Services → Credentials), with openid scope. " +
                        "Browser success alone is not enough.",
                )
            }

            val firebaseResult = FirebaseAuthBridge.signInWithGoogleTokens(
                idToken = resolvedIdToken,
                accessToken = accessToken,
                platformContext = platformContext,
            )
            when (firebaseResult) {
                is FirebaseAuthResult.Success -> {
                    val gated = FirebaseAuthAccessGate.enforceEmailDomain(
                        result = firebaseResult,
                        settings = settings,
                        signOut = {
                            FirebaseAuthBridge.signOut()
                            accountFile.delete()
                            idTokenFile.delete()
                        },
                    )
                    if (gated is FirebaseAuthResult.Success) {
                        writeOwnerOnlyText(
                            accountFile,
                            "email=${gated.email ?: resolvedEmail}\nuid=${gated.uid}\n",
                        )
                        settings.setFirebaseAuthEmail(gated.email ?: resolvedEmail)
                    }
                    gated
                }
                is FirebaseAuthResult.Error -> FirebaseAuthResult.Error(
                    firebaseResult.message,
                )
            }
        } catch (e: Exception) {
            FirebaseAuthResult.Error(e.message ?: "Desktop Google Sign-In failed")
        }
    }

    /**
     * One interactive browser round-trip for [scopes]. Split out from [signInWithGoogle] so the
     * caller can retry with fewer scopes when Google rejects the optional Drive ones.
     */
    private fun authorizeInteractively(
        secrets: GoogleClientSecrets,
        scopes: List<String>,
    ): DesktopAuthorizeOutcome {
        val flow = GoogleAuthorizationCodeFlow.Builder(
            GoogleNetHttpTransport.newTrustedTransport(),
            GsonFactory.getDefaultInstance(),
            secrets,
            scopes,
        )
            .setDataStoreFactory(FileDataStoreFactory(tokenDir))
            .setAccessType("offline")
            .build()

        // Always interactive for Firebase — stored refresh tokens do not yield id_token.
        runCatching { flow.credentialDataStore?.delete(DesktopGoogleCredentialStore.USER_ID) }

        val receiver = DesktopOAuthLoopbackReceiver()
        val redirectUri = try {
            receiver.start()
        } catch (e: Exception) {
            return DesktopAuthorizeOutcome.Failure(
                e.message ?: "Could not start the local OAuth callback server (ports 8889–9090). " +
                    "Close any other NoctuList window still waiting for Google Sign-In, then retry.",
            )
        }
        try {
            val authUrl = flow.newAuthorizationUrl()
                .setRedirectUri(redirectUri)
                .set("prompt", "consent")
                .build()
            runCatching { openExternalBrowser(authUrl) }
                .getOrElse { browserError ->
                    return DesktopAuthorizeOutcome.Failure(
                        "Could not open the system browser for Google Sign-In (${browserError.message}). " +
                            "Copy this URL into Safari/Chrome, complete sign-in, then return to NoctuList:\n$authUrl",
                    )
                }
            when (val loopback = receiver.waitForResult()) {
                is DesktopLoopbackOAuthResult.Success -> {
                    val tokenResponse: GoogleTokenResponse = flow.newTokenRequest(loopback.code)
                        .setRedirectUri(redirectUri)
                        .execute()
                    val idToken = tokenResponse.idToken
                    val credential = flow.createAndStoreCredential(tokenResponse, DesktopGoogleCredentialStore.USER_ID)
                    if (!idToken.isNullOrBlank()) {
                        writeOwnerOnlyText(idTokenFile, idToken)
                    }
                    return DesktopAuthorizeOutcome.Success(
                        credential = credential,
                        idToken = idToken,
                        // Google may grant a subset; fall back to what we asked for when silent.
                        grantedScopes = InstitutionGoogleWebOAuth.parseGrantedScopes(tokenResponse.scope)
                            .ifEmpty { scopes.toSet() },
                    )
                }
                is DesktopLoopbackOAuthResult.OAuthError -> {
                    val detail = loopback.description?.takeIf { it.isNotBlank() } ?: loopback.error
                    return if (isScopeRejection(loopback.error, loopback.description)) {
                        DesktopAuthorizeOutcome.ScopeRejected(detail)
                    } else {
                        DesktopAuthorizeOutcome.Failure("Google Sign-In failed: $detail")
                    }
                }
                DesktopLoopbackOAuthResult.TimedOut -> return DesktopAuthorizeOutcome.Failure(
                    "Google Sign-In timed out. Complete sign-in in the browser, then try again.",
                )
                DesktopLoopbackOAuthResult.Cancelled ->
                    return DesktopAuthorizeOutcome.Failure("Google Sign-In cancelled.")
            }
        } catch (e: Exception) {
            val msg = e.message.orEmpty()
            if (isScopeRejection(msg, null)) {
                return DesktopAuthorizeOutcome.ScopeRejected(msg)
            }
            if (msg.contains("redirect_uri_mismatch", ignoreCase = true) ||
                msg.contains("redirect_uri", ignoreCase = true)
            ) {
                return DesktopAuthorizeOutcome.Failure(
                    "redirect_uri_mismatch: Google rejected callback $redirectUri. " +
                        "In Cloud Console → APIs & Services → Credentials → your Web OAuth client → " +
                        "Authorized redirect URIs, add exactly: $redirectUri " +
                        "(also add http://localhost:8888/Callback, http://localhost:8765/Callback, " +
                        "http://localhost:9090/Callback). Save, wait ~1 minute, retry Sign-In.",
                )
            }
            return DesktopAuthorizeOutcome.Failure(msg.ifBlank { "Desktop Google Sign-In failed" })
        } finally {
            runCatching { receiver.stop() }
        }
    }

    private fun isScopeRejection(error: String?, description: String?): Boolean {
        val haystack = "${error.orEmpty()} ${description.orEmpty()}"
        return haystack.contains("invalid_scope", ignoreCase = true) ||
            haystack.contains("access_denied", ignoreCase = true) ||
            haystack.contains("admin_policy_enforced", ignoreCase = true) ||
            haystack.contains("has not been used in project", ignoreCase = true)
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

    /**
     * Institution Web OAuth client (not developer Gmail JSON).
     * Desktop and Android code exchange require client_secret.
     */
    private fun buildInstitutionWebClientSecretsJson(clientId: String, clientSecret: String): String =
        """
        {
          "web": {
            "client_id": ${jsonQuote(clientId)},
            "client_secret": ${jsonQuote(clientSecret)},
            "redirect_uris": [
            "http://localhost:8889/Callback",
            "http://localhost:8888/Callback",
            "http://localhost:8765/Callback",
            "http://localhost:9090/Callback"
          ]
          }
        }
        """.trimIndent()

    private fun jsonQuote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    override suspend fun signOut() {
        withContext(Dispatchers.IO) {
            FirebaseAuthBridge.signOut()
            accountFile.delete()
            idTokenFile.delete()
            tokenDir.deleteRecursively()
            tokenDir.mkdirs()
            settings.setFirebaseAuthEmail("")
            settings.setDriveImportGrantedScopes(emptySet())
        }
    }

    override fun currentUserEmail(): String? =
        FirebaseAuthBridge.currentUserEmail()
            ?: if (!accountFile.exists()) null
            else accountFile.readLines().firstOrNull { it.startsWith("email=") }?.substringAfter("=")

    override fun currentUserId(): String? =
        FirebaseAuthBridge.currentUserId()
            ?: if (!accountFile.exists()) null
            else accountFile.readLines().firstOrNull { it.startsWith("uid=") }?.substringAfter("=")

    override suspend fun restoreSession(): FirebaseAuthResult? = withContext(Dispatchers.IO) {
        FirebaseBootstrap.ensureInitialized(
            platformContext,
            FirebaseOptionsReader.fromSettings(settings),
        )
        DesktopFirebaseGoogleRestSignIn.hydrateSessionFromStore()
        if (FirebaseAuthBridge.isSignedIn()) {
            val candidate = FirebaseAuthResult.Success(
                uid = FirebaseAuthBridge.currentUserId().orEmpty(),
                email = FirebaseAuthBridge.currentUserEmail(),
            )
            return@withContext when (
                val gated = FirebaseAuthAccessGate.enforceEmailDomain(
                    result = candidate,
                    settings = settings,
                    signOut = {
                        FirebaseAuthBridge.signOut()
                        accountFile.delete()
                        idTokenFile.delete()
                    },
                )
            ) {
                is FirebaseAuthResult.Success -> gated
                is FirebaseAuthResult.Error -> null
            }
        }
        try {
            val stored = idTokenFile.takeIf { it.exists() }?.readText()?.trim().orEmpty()
            if (stored.isBlank()) return@withContext null
            when (val result = FirebaseAuthBridge.signInWithGoogleTokens(stored, null, platformContext)) {
                is FirebaseAuthResult.Success -> {
                    val gated = FirebaseAuthAccessGate.enforceEmailDomain(
                        result = result,
                        settings = settings,
                        signOut = {
                            FirebaseAuthBridge.signOut()
                            accountFile.delete()
                            idTokenFile.delete()
                        },
                    )
                    if (gated is FirebaseAuthResult.Success) {
                        writeOwnerOnlyText(
                            accountFile,
                            "email=${gated.email.orEmpty()}\nuid=${gated.uid}\n",
                        )
                        settings.setFirebaseAuthEmail(gated.email.orEmpty())
                        gated
                    } else {
                        null
                    }
                }
                is FirebaseAuthResult.Error -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    override fun isSignedIn(): Boolean = FirebaseAuthBridge.isSignedIn()

    /** Persist auth material with owner-only permissions when the OS supports it. */
    private fun writeOwnerOnlyText(file: File, text: String) {
        file.writeText(text)
        runCatching {
            file.setReadable(false, false)
            file.setWritable(false, false)
            file.setExecutable(false, false)
            file.setReadable(true, true)
            file.setWritable(true, true)
        }
    }
}

actual fun createFirebaseAuthService(platformContext: PlatformContext?): FirebaseAuthService {
    val ctx = platformContext ?: return NoOpFirebaseAuthService()
    return DesktopFirebaseAuthService(ctx)
}
