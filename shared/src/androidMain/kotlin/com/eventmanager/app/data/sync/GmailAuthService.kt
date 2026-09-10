package com.eventmanager.app.data.sync

import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.auth.UserRecoverableAuthException
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.Scope
import com.google.api.client.googleapis.extensions.android.gms.auth.GoogleAccountCredential
import com.google.api.client.googleapis.extensions.android.gms.auth.UserRecoverableAuthIOException
import com.google.api.client.http.HttpRequestInitializer
import com.google.api.client.http.javanet.NetHttpTransport
import com.google.api.client.json.gson.GsonFactory
import com.google.api.services.gmail.Gmail
import com.google.api.services.gmail.GmailScopes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Service for handling Gmail authentication using AccountManager.
 * This approach uses the device's Google accounts directly - like email apps do.
 * 
 * IMPORTANT: Gmail API requires the app to be registered in Google Cloud Console:
 * 1. Create a project at https://console.cloud.google.com/
 * 2. Enable the Gmail API
 * 3. Configure OAuth consent screen
 * 4. Create OAuth 2.0 credentials (Android type) with your app's package name and SHA-1
 */
class GmailAuthService(private val context: Context) {
    private val TAG = "GmailAuthService"
    
    companion object {
        // Gmail send scope
        private val SCOPES = listOf(GmailScopes.GMAIL_SEND)
        
        // OAuth2 scope format for AccountManager
        private val OAUTH_SCOPE = "oauth2:${SCOPES.joinToString(" ")}"
        
        // Request codes for account picker and authorization
        const val REQUEST_ACCOUNT_PICKER = 1000
        const val REQUEST_AUTHORIZATION = 1001
        const val REQUEST_ANDROID_TV_AUTH = 1002
    }
    
    // Cached OAuth token for Android TV fallback
    private var cachedOAuthToken: String? = null
    private var tokenExpirationTime: Long = 0
    
    init {
        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.N || Build.VERSION.SDK_INT == Build.VERSION_CODES.N_MR1) {
            Log.w(TAG, "Android 7.x detected - using compatibility mode")
        }
        restoreCachedToken()
    }
    
    private val credential: GoogleAccountCredential by lazy {
        GoogleAccountCredential.usingOAuth2(context, SCOPES).apply {
            val savedEmail = getSavedAccountEmail()
            if (savedEmail != null) {
                val accounts = getGoogleAccounts()
                val accountExists = accounts.any { it.name == savedEmail }
                if (accountExists) {
                    selectedAccountName = savedEmail
                    Log.d(TAG, "Account restored: $savedEmail")
                } else {
                    selectedAccountName = savedEmail
                    if (selectedAccountName == null) {
                        Log.w(TAG, "Account not accessible via AccountManager (Android TV?)")
                    }
                }
            }
        }
    }
    
    /**
     * Gets all Google accounts on the device
     */
    fun getGoogleAccounts(): List<Account> {
        val accountManager = AccountManager.get(context)
        return accountManager.getAccountsByType("com.google").toList()
    }
    
    /**
     * Diagnostic method to log ALL account types available on this device.
     * This helps debug Android TV issues where "com.google" accounts may not be accessible.
     */
    fun logAllAccountTypes() {
        val accountManager = AccountManager.get(context)
        
        // Get all accounts regardless of type
        val allAccounts = try {
            accountManager.accounts
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot get all accounts - permission denied: ${e.message}")
            emptyArray()
        }
        
        Log.d(TAG, "=== ACCOUNT DIAGNOSTICS ===")
        Log.d(TAG, "Total accounts on device: ${allAccounts.size}")
        
        // Group by account type
        val accountsByType = allAccounts.groupBy { it.type }
        accountsByType.forEach { (type, accounts) ->
            Log.d(TAG, "Account type '$type': ${accounts.map { it.name }}")
        }
        
        // Specifically check common Google account types
        val googleTypes = listOf(
            "com.google",
            "com.google.android.gms.auth.accounts.signin",
            "com.google.android.tv",
            "com.google.leanback",
            "com.google.android.gms.leanback"
        )
        
        Log.d(TAG, "--- Checking Google account types ---")
        googleTypes.forEach { type ->
            val accounts = accountManager.getAccountsByType(type)
            Log.d(TAG, "Type '$type': ${accounts.size} accounts - ${accounts.map { it.name }}")
        }
        
        // Check authenticator types available
        try {
            val authenticators = accountManager.authenticatorTypes
            Log.d(TAG, "--- Available authenticator types ---")
            authenticators.forEach { auth ->
                Log.d(TAG, "Authenticator: type='${auth.type}', package='${auth.packageName}'")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Cannot get authenticator types: ${e.message}")
        }
        
        Log.d(TAG, "=== END ACCOUNT DIAGNOSTICS ===")
    }
    
    /**
     * Creates an intent to show the account picker dialog.
     * Launch this intent and handle the result to select an account.
     */
    fun getAccountPickerIntent(): Intent {
        return credential.newChooseAccountIntent()
    }
    
    fun setSelectedAccount(accountName: String) {
        logAllAccountTypes()
        val previous = getSavedAccountEmail()
        if (previous != null && previous != accountName) {
            clearCachedToken()
        }
        saveAccountEmail(accountName)
        credential.selectedAccountName = accountName
        
        if (credential.selectedAccountName != accountName) {
            Log.w(TAG, "Credential rejected account - will use Android TV fallback if needed")
        }
    }
    
    fun getSelectedAccountEmail(): String? {
        return credential.selectedAccountName ?: getSavedAccountEmail()
    }
    
    fun isAccountSelected(): Boolean {
        return credential.selectedAccountName != null || getSavedAccountEmail() != null
    }
    
    fun isCredentialReady(): Boolean {
        return credential.selectedAccountName != null || (hasValidCachedToken() && getSavedAccountEmail() != null)
    }
    
    fun needsAndroidTVAuth(): Boolean {
        val savedEmail = getSavedAccountEmail()
        return savedEmail != null && credential.selectedAccountName == null && !hasValidCachedToken()
    }
    
    fun clearSelectedAccount() {
        credential.selectedAccountName = null
        clearSavedAccountEmail()
        clearCachedToken()
    }
    
    suspend fun createGmailService(): Gmail? = withContext(Dispatchers.IO) {
        try {
            val selectedEmail = credential.selectedAccountName ?: getSavedAccountEmail()
            if (selectedEmail != null) {
                when (val acquisition = acquireToken(selectedEmail)) {
                    is TokenAcquisition.Token -> {
                        // Bearer token is more robust on microG/LineageOS once consent is already granted.
                        return@withContext buildGmailWithBearerToken(acquisition.value)
                    }
                    is TokenAcquisition.ConsentRequired -> {
                        throw GmailAuthorizationRequiredException(
                            authIntent = acquisition.intent,
                            message = "Please authorize the app to send emails via Gmail"
                        )
                    }
                    TokenAcquisition.Unavailable -> {
                        if (credential.selectedAccountName != null) {
                            // Let GmailSendService surface UserRecoverableAuthIOException on the first send.
                            Log.w(TAG, "No token yet; using GoogleAccountCredential so Gmail send can request consent")
                            val transport = NetHttpTransport()
                            val jsonFactory = GsonFactory.getDefaultInstance()
                            return@withContext Gmail.Builder(transport, jsonFactory, credential)
                                .setApplicationName("Event Manager App")
                                .build()
                        }
                        if (isGooglePlayServicesAvailable()) {
                            throw GmailAuthorizationRequiredException(
                                authIntent = getGmailConsentSignInIntent(selectedEmail),
                                message = "Please authorize the app to send emails via Gmail"
                            )
                        }
                    }
                }
            }

            if (hasValidCachedToken() && cachedOAuthToken != null) {
                return@withContext buildGmailWithBearerToken(cachedOAuthToken!!)
            }

            Log.w(TAG, "Cannot create Gmail service - no valid credential or token")
            null
        } catch (e: GmailAuthorizationRequiredException) {
            throw e
        } catch (e: Exception) {
            extractConsentIntent(e)?.let { intent ->
                throw GmailAuthorizationRequiredException(
                    authIntent = intent,
                    message = "Please authorize the app to send emails via Gmail"
                )
            }
            Log.e(TAG, "Error creating Gmail service", e)
            null
        }
    }

    private fun buildGmailWithBearerToken(token: String): Gmail {
        val transport = NetHttpTransport()
        val jsonFactory = GsonFactory.getDefaultInstance()
        val tokenInitializer = HttpRequestInitializer { request ->
            request.headers.authorization = "Bearer $token"
        }
        return Gmail.Builder(transport, jsonFactory, tokenInitializer)
            .setApplicationName("Event Manager App")
            .build()
    }

    private sealed class TokenAcquisition {
        data class Token(val value: String) : TokenAcquisition()
        data class ConsentRequired(val intent: Intent) : TokenAcquisition()
        data object Unavailable : TokenAcquisition()
    }

    private fun acquireToken(selectedEmail: String): TokenAcquisition {
        if (hasValidCachedToken() && cachedOAuthToken != null) {
            return TokenAcquisition.Token(cachedOAuthToken!!)
        }

        try {
            credential.selectedAccountName = selectedEmail
            val credentialToken = credential.token
            if (!credentialToken.isNullOrBlank()) {
                saveCachedToken(credentialToken, System.currentTimeMillis() + 3600000)
                return TokenAcquisition.Token(credentialToken)
            }
        } catch (e: Exception) {
            extractConsentIntent(e)?.let { return TokenAcquisition.ConsentRequired(it) }
            Log.w(TAG, "GoogleAccountCredential token retrieval failed, trying AccountManager fallback: ${e.javaClass.simpleName}")
        }

        return tryGetAccountManagerToken(selectedEmail)
    }

    private fun tryGetAccountManagerToken(selectedEmail: String): TokenAcquisition {
        return try {
            val accountManager = AccountManager.get(context)
            val account = Account(selectedEmail, "com.google")
            val bundle = accountManager.getAuthToken(account, OAUTH_SCOPE, false, null, null).result
            val authToken = bundle?.getString(AccountManager.KEY_AUTHTOKEN)
            if (!authToken.isNullOrBlank()) {
                saveCachedToken(authToken, System.currentTimeMillis() + 3600000)
                TokenAcquisition.Token(authToken)
            } else {
                val consentIntent = parcelableIntent(bundle)
                if (consentIntent != null) {
                    TokenAcquisition.ConsentRequired(consentIntent)
                } else {
                    Log.w(TAG, "AccountManager fallback returned no token for $selectedEmail")
                    TokenAcquisition.Unavailable
                }
            }
        } catch (e: Exception) {
            extractConsentIntent(e)?.let { return TokenAcquisition.ConsentRequired(it) }
            Log.e(TAG, "AccountManager fallback token retrieval failed: ${e.javaClass.simpleName} - ${e.message}")
            TokenAcquisition.Unavailable
        }
    }

    fun extractConsentIntent(error: Throwable): Intent? {
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 10) {
            when (current) {
                is UserRecoverableAuthIOException -> current.intent?.let { return it }
                is UserRecoverableAuthException -> current.intent?.let { return it }
            }
            current = current.cause
            depth++
        }
        return null
    }

    fun getGmailConsentSignInIntent(email: String): Intent {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .setAccountName(email)
            .requestEmail()
            .requestScopes(Scope(GmailScopes.GMAIL_SEND))
            .build()
        return GoogleSignIn.getClient(context, gso).signInIntent
    }

    private fun isGooglePlayServicesAvailable(): Boolean {
        return try {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS
        } catch (_: Exception) {
            false
        }
    }

    @Suppress("DEPRECATION")
    private fun parcelableIntent(bundle: Bundle?): Intent? {
        return bundle?.getParcelable(AccountManager.KEY_INTENT)
    }
    
    suspend fun tryGetTokenForAndroidTV(activity: Activity): Intent? = withContext(Dispatchers.IO) {
        val savedEmail = getSavedAccountEmail() ?: return@withContext null
        
        val accountManager = AccountManager.get(context)
        val account = Account(savedEmail, "com.google")
        
        try {
            val result = suspendCancellableCoroutine<Bundle> { continuation ->
                accountManager.getAuthToken(
                    account, OAUTH_SCOPE, null, activity,
                    { future ->
                        try {
                            continuation.resume(future.result)
                        } catch (e: Exception) {
                            continuation.resumeWithException(e)
                        }
                    },
                    Handler(Looper.getMainLooper())
                )
            }
            
            val authToken = result.getString(AccountManager.KEY_AUTHTOKEN)
            val authIntent = parcelableIntent(result)
            
            if (authToken != null) {
                saveCachedToken(authToken, System.currentTimeMillis() + 3600000)
                return@withContext null
            }
            return@withContext authIntent
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get OAuth token: ${e.message}")
            try {
                val bundle = accountManager.getAuthToken(account, OAUTH_SCOPE, false, null, null).result
                bundle?.getString(AccountManager.KEY_AUTHTOKEN)?.let { token ->
                    saveCachedToken(token, System.currentTimeMillis() + 3600000)
                    return@withContext null
                }
                return@withContext parcelableIntent(bundle)
            } catch (e2: Exception) {
                Log.e(TAG, "Alternative approach failed: ${e2.message}")
                null
            }
        }
    }
    
    fun hasValidCachedToken(): Boolean {
        if (cachedOAuthToken == null) {
            restoreCachedToken()
        }
        return cachedOAuthToken != null && System.currentTimeMillis() < tokenExpirationTime - 300000
    }
    
    private fun saveCachedToken(token: String, expirationTime: Long) {
        cachedOAuthToken = token
        tokenExpirationTime = expirationTime
        
        val prefs = context.getSharedPreferences("gmail_auth", Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putString("oauth_token", token)
            .putLong("oauth_token_expiration", expirationTime)
        
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
            editor.commit()
        } else {
            editor.apply()
        }
    }
    
    private fun restoreCachedToken() {
        val prefs = context.getSharedPreferences("gmail_auth", Context.MODE_PRIVATE)
        val savedToken = prefs.getString("oauth_token", null)
        val savedExpiration = prefs.getLong("oauth_token_expiration", 0)
        
        if (savedToken != null && savedExpiration > 0) {
            if (System.currentTimeMillis() < savedExpiration - 300000) {
                cachedOAuthToken = savedToken
                tokenExpirationTime = savedExpiration
            } else {
                clearCachedToken()
            }
        }
    }
    
    fun clearCachedToken() {
        cachedOAuthToken = null
        tokenExpirationTime = 0
        
        val prefs = context.getSharedPreferences("gmail_auth", Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .remove("oauth_token")
            .remove("oauth_token_expiration")
        
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
            editor.commit()
        } else {
            editor.apply()
        }
    }
    
    suspend fun testPermissionAndGetAuthIntent(): Intent? = withContext(Dispatchers.IO) {
        val email = credential.selectedAccountName ?: getSavedAccountEmail() ?: return@withContext null
        if (credential.selectedAccountName == null) {
            credential.selectedAccountName = email
        }
        when (val acquisition = acquireToken(email)) {
            is TokenAcquisition.Token -> null
            is TokenAcquisition.ConsentRequired -> acquisition.intent
            TokenAcquisition.Unavailable -> {
                if (isGooglePlayServicesAvailable()) getGmailConsentSignInIntent(email) else null
            }
        }
    }
    
    fun getGoogleAccountCredential(): GoogleAccountCredential = credential
    
    fun refreshCredential(): Boolean {
        val savedEmail = getSavedAccountEmail() ?: return false
        if (credential.selectedAccountName == savedEmail) return true
        
        val maxAttempts = if (Build.VERSION.SDK_INT == Build.VERSION_CODES.N || Build.VERSION.SDK_INT == Build.VERSION_CODES.N_MR1) 3 else 1
        
        for (attempt in 1..maxAttempts) {
            if (attempt > 1) {
                try {
                    Thread.sleep(300)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            
            val accounts = getGoogleAccounts()
            if (accounts.any { it.name == savedEmail }) {
                credential.selectedAccountName = savedEmail
                if (credential.selectedAccountName == savedEmail) {
                    return true
                }
            }
        }
        
        return false
    }
    
    // --- Persistence helpers ---
    
    /**
     * Gets the saved account email directly from SharedPreferences.
     * This is useful for UI display when credential restoration fails.
     */
    fun getSavedAccountEmail(): String? {
        val prefs = context.getSharedPreferences("gmail_auth", Context.MODE_PRIVATE)
        return prefs.getString("selected_account", null)
    }
    
    private fun saveAccountEmail(email: String) {
        val editor = context.getSharedPreferences("gmail_auth", Context.MODE_PRIVATE)
            .edit()
            .putString("selected_account", email)
        
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
            editor.commit()
        } else {
            editor.apply()
        }
    }
    
    private fun clearSavedAccountEmail() {
        val editor = context.getSharedPreferences("gmail_auth", Context.MODE_PRIVATE)
            .edit()
            .remove("selected_account")
        
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.N_MR1) {
            editor.commit()
        } else {
            editor.apply()
        }
    }
}
