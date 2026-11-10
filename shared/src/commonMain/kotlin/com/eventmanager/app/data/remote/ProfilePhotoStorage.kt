package com.eventmanager.app.data.remote

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.utils.ProfilePhotoDisplayQuality
import com.eventmanager.app.utils.ProfilePhotoImageCache
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

enum class ProfilePhotoKind {
    GUEST,
    VOLUNTEER,
    ;

    val storageFolder: String
        get() = when (this) {
            GUEST -> "guests"
            VOLUNTEER -> "volunteers"
        }
}

data class ProfilePhotoUploadResult(
    val path: String,
    val url: String,
)

data class FirebaseStorageObjectRef(
    val bucket: String,
    val path: String,
)

fun profilePhotoStoragePath(orgId: String, kind: ProfilePhotoKind, entityId: String): String =
    "orgs/${orgId.trim()}/profilePhotos/${kind.storageFolder}/${entityId.trim()}.jpg"

/** Written to Firestore so a delete is not treated as “field omitted”. */
const val PROFILE_PHOTO_CLEARED_SENTINEL = "-"

fun String.isStoredProfilePhotoRef(): Boolean {
    val trimmed = trim()
    return trimmed.isNotBlank() && trimmed != PROFILE_PHOTO_CLEARED_SENTINEL
}

fun isProfilePhotoCleared(path: String, url: String): Boolean =
    path.trim() == PROFILE_PHOTO_CLEARED_SENTINEL || url.trim() == PROFILE_PHOTO_CLEARED_SENTINEL

fun Guest.hasStoredProfilePhoto(): Boolean =
    profilePhotoUrl.isStoredProfilePhotoRef() || profilePhotoPath.isStoredProfilePhotoRef()

fun Volunteer.hasStoredProfilePhoto(): Boolean =
    profilePhotoUrl.isStoredProfilePhotoRef() || profilePhotoPath.isStoredProfilePhotoRef()

fun Guest.resolvedProfilePhotoPath(): String {
    if (isProfilePhotoCleared(profilePhotoPath, profilePhotoUrl)) return ""
    return profilePhotoPath.trim().ifBlank {
        val org = firebaseOrgId.trim()
        if (org.isBlank() || nanoId.isBlank()) "" else profilePhotoStoragePath(org, ProfilePhotoKind.GUEST, nanoId)
    }
}

fun Volunteer.resolvedProfilePhotoPath(): String {
    if (isProfilePhotoCleared(profilePhotoPath, profilePhotoUrl)) return ""
    return profilePhotoPath.trim().ifBlank {
        val org = firebaseOrgId.trim()
        if (org.isBlank() || id.isBlank()) "" else profilePhotoStoragePath(org, ProfilePhotoKind.VOLUNTEER, id)
    }
}

/**
 * Firebase download URLs encode `/` as `%2F`. [java.net.URI] decodes that back to `/`,
 * which breaks HTTP GET. Always prefer Storage SDK / authenticated REST using this path.
 */
fun parseFirebaseStorageDownloadUrl(url: String): FirebaseStorageObjectRef? {
    val trimmed = url.trim()
    if (trimmed.isBlank()) return null
    if (trimmed.startsWith("gs://", ignoreCase = true)) {
        val rest = trimmed.removePrefix("gs://").removePrefix("GS://")
        val slash = rest.indexOf('/')
        if (slash <= 0) return null
        val bucket = rest.substring(0, slash).trim()
        val path = rest.substring(slash + 1).trim().trimStart('/')
        return if (bucket.isBlank() || path.isBlank()) null else FirebaseStorageObjectRef(bucket, path)
    }
    val firebase = FIREBASE_DOWNLOAD_URL_REGEX.find(trimmed) ?: return null
    val bucket = firebase.groupValues[1].trim()
    val encodedPath = firebase.groupValues[2].trim()
    val path = runCatching {
        java.net.URLDecoder.decode(encodedPath, Charsets.UTF_8.name())
    }.getOrDefault(encodedPath).trim().trimStart('/')
    return if (bucket.isBlank() || path.isBlank()) null else FirebaseStorageObjectRef(bucket, path)
}

private val FIREBASE_DOWNLOAD_URL_REGEX =
    Regex("""https://firebasestorage\.googleapis\.com/v0/b/([^/]+)/o/([^?]+)""", RegexOption.IGNORE_CASE)

fun normalizeFirebaseStorageBucket(raw: String): String =
    raw.removePrefix("gs://").trim().trimEnd('/')

/**
 * Storage bucket is optional in the join QR / manual project fields. Fall back to Firebase
 * defaults from the project ID so photo uploads do not silently no-op.
 */
fun firebaseStorageBucketCandidates(storedBucket: String, projectId: String): List<String> {
    val stored = normalizeFirebaseStorageBucket(storedBucket)
    val pid = projectId.trim()
    return buildList {
        if (stored.isNotBlank()) add(stored)
        if (pid.isNotBlank()) {
            add("$pid.firebasestorage.app")
            add("$pid.appspot.com")
        }
    }.distinct()
}

/**
 * Download a profile JPEG for display. Uses signed-in Storage access (required by
 * [firebase/storage.rules]) instead of an anonymous HTTP GET of the download URL.
 */
suspend fun downloadProfilePhotoBytes(
    platformContext: PlatformContext?,
    url: String,
    storagePath: String = "",
): ByteArray? {
    val trimmedUrl = url.trim()
    val parsed = parseFirebaseStorageDownloadUrl(trimmedUrl)
    val path = parsed?.path?.takeIf { it.isNotBlank() } ?: storagePath.trim().takeIf { it.isNotBlank() }
    val settings = platformContext?.let { SettingsManager(it) }
    if (path != null && settings != null && platformContext != null) {
        FirebaseBootstrap.ensureInitialized(
            platformContext,
            FirebaseOptionsReader.fromSettings(settings),
        )
        val buckets = buildList {
            parsed?.bucket?.takeIf { it.isNotBlank() }?.let { add(normalizeFirebaseStorageBucket(it)) }
            addAll(
                firebaseStorageBucketCandidates(
                    storedBucket = settings.getFirebaseStorageBucket(),
                    projectId = settings.getFirebaseProjectId(),
                ),
            )
        }.distinct().filter { it.isNotBlank() }
        for (bucket in buckets) {
            val bytes = runCatching {
                firebaseStorageGetBytes(bucket, path, platformContext)
            }.onFailure { e ->
                println("Profile photo Storage download failed ($bucket/$path): ${e.message}")
            }.getOrNull()
            if (bytes != null && bytes.isNotEmpty()) return bytes
        }
    }
    if (trimmedUrl.isBlank()) return null
    return httpDownloadProfilePhoto(trimmedUrl)
}

internal fun firebaseStorageDownloadJpegRest(bucket: String, path: String, idToken: String): ByteArray? {
    if (bucket.isBlank() || path.isBlank() || idToken.isBlank()) return null
    val encodedName = URLEncoder.encode(path, Charsets.UTF_8).replace("+", "%20")
    val url = URL("https://firebasestorage.googleapis.com/v0/b/$bucket/o/$encodedName?alt=media")
    val conn = (url.openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        setRequestProperty("Authorization", "Bearer $idToken")
        connectTimeout = 20_000
        readTimeout = 30_000
    }
    return try {
        val code = conn.responseCode
        if (code !in 200..299) {
            println("Profile photo Storage REST download HTTP $code for $path")
            return null
        }
        conn.inputStream.use { it.readBytes() }.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        println("Profile photo Storage REST download failed: ${e.message}")
        null
    } finally {
        conn.disconnect()
    }
}

suspend fun loadProfilePhotoBytesForExport(
    platformContext: PlatformContext,
    url: String,
    storagePath: String,
): ByteArray? {
    val displayUrl = url.takeIf { it.isStoredProfilePhotoRef() }.orEmpty()
    val displayPath = storagePath.takeIf { it.isStoredProfilePhotoRef() }.orEmpty()
    if (displayUrl.isBlank() && displayPath.isBlank()) return null
    ProfilePhotoImageCache.load(
        platformContext,
        displayUrl,
        ProfilePhotoDisplayQuality.Full,
        displayPath,
    )?.takeIf { it.isNotEmpty() }?.let { return it }
    return downloadProfilePhotoBytes(platformContext, displayUrl, displayPath)
}

private fun httpDownloadProfilePhoto(url: String): ByteArray? {
    val conn = runCatching {
        (URL(url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 20_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "NoctuList")
        }
    }.getOrNull() ?: return null
    return try {
        val code = conn.responseCode
        if (code !in 200..299) {
            println("Profile photo HTTP download failed: HTTP $code")
            return null
        }
        conn.inputStream.use { it.readBytes() }.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        println("Profile photo HTTP download failed: ${e.message}")
        null
    } finally {
        conn.disconnect()
    }
}

/**
 * Optional Firebase Storage uploads for profile photos.
 * Must never throw into guest/volunteer save paths — callers treat null as "saved without photo".
 */
interface ProfilePhotoStorageGateway {
    suspend fun uploadJpeg(
        orgId: String,
        kind: ProfilePhotoKind,
        entityId: String,
        jpegBytes: ByteArray,
    ): ProfilePhotoUploadResult?

    suspend fun delete(path: String): Boolean

    /** Uploads a JPEG to an exact Storage path (portal photo copies). False when it failed. */
    suspend fun uploadJpegToPath(path: String, jpegBytes: ByteArray): Boolean = false
}

class NoOpProfilePhotoStorageGateway : ProfilePhotoStorageGateway {
    override suspend fun uploadJpeg(
        orgId: String,
        kind: ProfilePhotoKind,
        entityId: String,
        jpegBytes: ByteArray,
    ): ProfilePhotoUploadResult? = null

    override suspend fun delete(path: String): Boolean = false
}
