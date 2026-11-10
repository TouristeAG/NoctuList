package com.eventmanager.app.data.remote

import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.PlatformContext

/**
 * Firebase Storage uploads. Android uses the native Storage SDK with an explicit bucket;
 * Desktop uses the Storage REST API because GitLive JVM Storage is a stub.
 * Never throws to the caller.
 */
class GitLiveProfilePhotoStorageGateway(
    private val platformContext: PlatformContext? = null,
    private val settingsManager: SettingsManager? = null,
) : ProfilePhotoStorageGateway {

    private fun ensureFirebase() {
        val settings = settingsManager ?: return
        val ctx = platformContext ?: return
        FirebaseBootstrap.ensureInitialized(ctx, FirebaseOptionsReader.fromSettings(settings))
    }

    private fun bucketCandidates(): List<String> {
        ensureFirebase()
        val settings = settingsManager ?: return emptyList()
        return firebaseStorageBucketCandidates(
            storedBucket = settings.getFirebaseStorageBucket(),
            projectId = settings.getFirebaseProjectId(),
        )
    }

    override suspend fun uploadJpeg(
        orgId: String,
        kind: ProfilePhotoKind,
        entityId: String,
        jpegBytes: ByteArray,
    ): ProfilePhotoUploadResult? {
        if (orgId.isBlank() || entityId.isBlank() || jpegBytes.isEmpty()) {
            println("Profile photo upload skipped: missing org, id, or image bytes")
            return null
        }
        val buckets = bucketCandidates()
        if (buckets.isEmpty()) {
            println("Profile photo upload skipped: no Storage bucket (project ID missing)")
            return null
        }
        val path = profilePhotoStoragePath(orgId, kind, entityId)
        var lastError: String? = null
        for (bucket in buckets) {
            val url = runCatching {
                firebaseStoragePutJpeg(bucket, path, jpegBytes, platformContext)
            }.onFailure { e ->
                lastError = e.message
                println("Profile photo upload failed for bucket $bucket: ${e.message}")
            }.getOrNull()
            if (!url.isNullOrBlank()) {
                val stored = settingsManager?.getFirebaseStorageBucket().orEmpty()
                if (normalizeFirebaseStorageBucket(stored).isBlank()) {
                    settingsManager?.setFirebaseStorageBucket(bucket)
                }
                return ProfilePhotoUploadResult(path = path, url = url)
            }
        }
        println("Profile photo upload failed: ${lastError ?: "empty download URL"}")
        return null
    }

    override suspend fun uploadJpegToPath(path: String, jpegBytes: ByteArray): Boolean {
        if (path.isBlank() || jpegBytes.isEmpty()) return false
        for (bucket in bucketCandidates()) {
            val url = runCatching { firebaseStoragePutJpeg(bucket, path, jpegBytes, platformContext) }
                .onFailure { e -> println("Storage upload failed for bucket $bucket: ${e.message}") }
                .getOrNull()
            if (!url.isNullOrBlank()) return true
        }
        return false
    }

    override suspend fun delete(path: String): Boolean {
        if (path.isBlank()) return false
        val buckets = bucketCandidates()
        if (buckets.isEmpty()) return false
        for (bucket in buckets) {
            val ok = runCatching {
                firebaseStorageDeleteObject(bucket, path, platformContext)
            }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }
}
