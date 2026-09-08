package com.eventmanager.app.data.security

import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.eventmanager.app.platform.PlatformContext

actual fun createSecureCredentialStore(context: PlatformContext): SecureCredentialStore {
    val androidContext = context.androidContext
    return runCatching { openStore(androidContext) }.getOrElse {
        // Auto Backup / leftover XML with a lost Keystore key cannot be decrypted.
        runCatching { androidContext.deleteSharedPreferences(PREFS_NAME) }
        openStore(androidContext)
    }
}

private const val PREFS_NAME = "noctulist_secure_credentials"

private fun openStore(androidContext: android.content.Context): SecureCredentialStore {
    val masterKey = MasterKey.Builder(androidContext)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()
    val prefs = EncryptedSharedPreferences.create(
        androidContext,
        PREFS_NAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
    return AndroidSecureCredentialStore(prefs)
}

private class AndroidSecureCredentialStore(
    private val prefs: android.content.SharedPreferences,
) : SecureCredentialStore {
    override fun getSecret(key: String): String? =
        prefs.getString(key, null)?.takeIf { it.isNotEmpty() }

    override fun putSecret(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override fun removeSecret(key: String) {
        prefs.edit().remove(key).apply()
    }

    override fun containsSecret(key: String): Boolean = prefs.contains(key)

    override fun clearAll() {
        prefs.edit().clear().commit()
    }
}
