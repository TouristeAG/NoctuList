package com.eventmanager.app.data.security

import com.eventmanager.app.platform.PlatformContext

/**
 * Platform secure storage for secrets that must never be synced to cloud backends.
 * Keys are opaque strings; values are UTF-8 text (JSON, tokens, passwords).
 */
interface SecureCredentialStore {
    fun getSecret(key: String): String?
    fun putSecret(key: String, value: String)
    fun removeSecret(key: String)
    fun containsSecret(key: String): Boolean
    fun clearAll()
}

object SecureCredentialKeys {
    const val FIREBASE_API_KEY = "firebase_api_key"
    const val FIREBASE_WEB_CLIENT_SECRET = "firebase_web_client_secret"
    const val WALLET_PASS_CERT_PASSWORD = "wallet_pass_cert_password"
    const val GMAIL_AUTH_TOKEN = "email_gmail_auth_token"
    const val SERVICE_ACCOUNT_JSON = "service_account_json"
    const val ORG_CRYPTO_PASSPHRASE_PREFIX = "org_crypto_passphrase_"
}

expect fun createSecureCredentialStore(context: PlatformContext): SecureCredentialStore
