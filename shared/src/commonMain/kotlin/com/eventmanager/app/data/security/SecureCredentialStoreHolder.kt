package com.eventmanager.app.data.security

/**
 * App-wide secure credential store, initialized once at startup.
 */
object SecureCredentialStoreHolder {
    @Volatile
    private var store: SecureCredentialStore? = null

    fun init(store: SecureCredentialStore) {
        this.store = store
    }

    fun get(): SecureCredentialStore? = store

    fun clearAll() {
        store?.clearAll()
    }

    fun migratePlaintextFrom(storage: com.eventmanager.app.platform.AppStorage, keys: List<Pair<String, String>>) {
        val secure = store ?: return
        keys.forEach { (plainKey, secureKey) ->
            if (!secure.containsSecret(secureKey)) {
                val value = storage.getString(plainKey, "").orEmpty()
                if (value.isNotBlank()) {
                    secure.putSecret(secureKey, value)
                    storage.remove(plainKey)
                }
            }
        }
    }
}
