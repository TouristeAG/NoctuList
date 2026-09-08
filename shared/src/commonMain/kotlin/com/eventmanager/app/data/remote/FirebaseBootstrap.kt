package com.eventmanager.app.data.remote

import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.PlatformContext

/**
 * Firebase project options used for programmatic [Firebase.initialize].
 * Prefer Settings / firebase-options.json over committing secrets in source.
 */
data class FirebaseProjectOptions(
    val apiKey: String,
    val applicationId: String,
    val projectId: String,
    val gcmSenderId: String = "",
    val storageBucket: String = "",
) {
    fun isComplete(): Boolean =
        apiKey.isNotBlank() && applicationId.isNotBlank() && projectId.isNotBlank()

    /** Project identity used to decide whether a live Firebase.app must be replaced. */
    fun sameProjectAs(other: FirebaseProjectOptions): Boolean =
        apiKey == other.apiKey &&
            applicationId == other.applicationId &&
            projectId == other.projectId
}

object FirebaseOptionsReader {
    fun fromSettings(settings: SettingsManager): FirebaseProjectOptions? {
        val opts = FirebaseProjectOptions(
            apiKey = settings.getFirebaseApiKey(),
            applicationId = settings.getFirebaseApplicationId(),
            projectId = settings.getFirebaseProjectId(),
            gcmSenderId = settings.getFirebaseGcmSenderId(),
            storageBucket = settings.getFirebaseStorageBucket().ifBlank {
                firebaseStorageBucketCandidates(
                    storedBucket = "",
                    projectId = settings.getFirebaseProjectId(),
                ).firstOrNull().orEmpty()
            },
        )
        return opts.takeIf { it.isComplete() }
    }
}

/**
 * Platform bootstrap: Desktop needs [FirebasePlatform]; Android may use google-services
 * auto-init or programmatic options from Settings.
 */
expect object FirebaseBootstrap {
    /** Returns true if Firebase.app is usable after this call. */
    fun ensureInitialized(platformContext: PlatformContext, options: FirebaseProjectOptions?): Boolean
    fun isInitialized(): Boolean
    /** Drops the in-process Firebase app so the next [ensureInitialized] can use new options. */
    fun release()
}
