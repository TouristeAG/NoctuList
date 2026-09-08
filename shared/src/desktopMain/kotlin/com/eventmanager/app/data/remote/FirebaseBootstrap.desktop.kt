package com.eventmanager.app.data.remote

import android.app.Application
import com.eventmanager.app.platform.PlatformContext
import com.eventmanager.app.platform.appDataDir
import com.google.firebase.FirebasePlatform
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.FirebaseOptions
import dev.gitlive.firebase.app
import dev.gitlive.firebase.initialize
import java.io.File

actual object FirebaseBootstrap {
    @Volatile
    private var platformReady = false

    @Volatile
    private var platformInstance: FirebasePlatform? = null

    @Volatile
    private var lastFailure: String? = null

    /** Exposed so Desktop Google Sign-In can inject the Auth session into platform KV. */
    fun platformOrNull(): FirebasePlatform? = platformInstance

    /** Last bootstrap failure (platform or [Firebase.initialize]); cleared on success. */
    fun lastFailureMessage(): String? = lastFailure

    actual fun ensureInitialized(
        platformContext: PlatformContext,
        options: FirebaseProjectOptions?,
    ): Boolean {
        if (!ensurePlatform(platformContext)) return false
        val opts = options
        if (opts == null || !opts.isComplete()) {
            if (isInitialized()) {
                lastFailure = null
                return true
            }
            lastFailure =
                "Missing Firebase project options (Project ID, Application ID, API key)."
            return false
        }
        if (isInitialized() && appliedIdentity == identityOf(opts)) {
            lastFailure = null
            return true
        }
        if (isInitialized()) {
            deleteDefaultApp()
            appliedIdentity = null
        }
        return runCatching {
            // GitLive JVM requires a non-null android.content.Context; firebase-java-sdk
            // provides a stub Application for Desktop (null throws and was swallowed before).
            Firebase.initialize(
                context = Application(),
                options = FirebaseOptions(
                    applicationId = opts.applicationId,
                    apiKey = opts.apiKey,
                    projectId = opts.projectId,
                    gcmSenderId = opts.gcmSenderId.ifBlank { null },
                    storageBucket = opts.storageBucket.ifBlank { null },
                ),
            )
            appliedIdentity = identityOf(opts)
            lastFailure = null
            true
        }.getOrElse { e ->
            lastFailure = formatFirebaseInitFailure(e)
            false
        }
    }

    private fun formatFirebaseInitFailure(error: Throwable): String {
        val root = generateSequence(error) { it.cause }.last()
        val chain = generateSequence(error) { it.cause }.mapNotNull { it.message }.joinToString(" → ")
        if (root is ClassNotFoundException && root.message?.contains("ManagementFactory") == true ||
            chain.contains("ManagementFactory", ignoreCase = true)
        ) {
            return "Firebase desktop runtime is missing the java.management module (FirebaseInitProvider). " +
                "Rebuild and reinstall the latest NoctuList desktop installer (DMG / MSI / EXE / Deb / AppImage)."
        }
        if (root is ExceptionInInitializerError || error is ExceptionInInitializerError) {
            val detail = root.message?.takeIf { it.isNotBlank() } ?: root::class.simpleName.orEmpty()
            return "Could not initialize Firebase for desktop ($detail). Reinstall the latest app build."
        }
        return error.message?.takeIf { it.isNotBlank() }
            ?: error::class.simpleName
            ?: "Firebase.initialize failed"
    }

    actual fun isInitialized(): Boolean = runCatching {
        Firebase.app
        true
    }.getOrDefault(false)

    actual fun release() {
        deleteDefaultApp()
        appliedIdentity = null
        lastFailure = null
    }

    @Volatile
    private var appliedIdentity: Triple<String, String, String>? = null

    private fun identityOf(opts: FirebaseProjectOptions) =
        Triple(opts.apiKey, opts.applicationId, opts.projectId)

    private fun deleteDefaultApp() {
        runCatching {
            val clazz = Class.forName("com.google.firebase.FirebaseApp")
            val instance = clazz.getMethod("getInstance").invoke(null)
            clazz.getMethod("delete").invoke(instance)
        }
    }

    private fun ensurePlatform(platformContext: PlatformContext): Boolean {
        if (platformReady && platformInstance != null) return true
        synchronized(this) {
            if (platformReady && platformInstance != null) return true
            val storageDir = File(platformContext.appDataDir, "firebase_platform_kv").also { it.mkdirs() }
            return try {
                val platform = object : FirebasePlatform() {
                    override fun store(key: String, value: String) {
                        File(storageDir, sanitize(key)).writeText(value)
                    }

                    override fun retrieve(key: String): String? {
                        val f = File(storageDir, sanitize(key))
                        return if (f.exists()) f.readText() else null
                    }

                    override fun clear(key: String) {
                        File(storageDir, sanitize(key)).delete()
                    }

                    override fun log(msg: String) {
                        val safe = msg
                            .replace(
                                Regex("""eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+"""),
                                "[jwt-redacted]",
                            )
                            .replace(
                                Regex("""(?i)(id[_-]?token|access[_-]?token)\s*[:=]\s*\S+"""),
                                "$1=[redacted]",
                            )
                        println("Firebase: $safe")
                    }

                    private fun sanitize(key: String): String =
                        key.replace(Regex("""[^A-Za-z0-9._-]"""), "_")
                }
                FirebasePlatform.initializeFirebasePlatform(platform)
                platformInstance = platform
                platformReady = true
                lastFailure = null
                true
            } catch (e: Exception) {
                val msg = e.message.orEmpty()
                // Second call in the same process is OK — platform is already usable.
                if (msg.contains("already", ignoreCase = true)) {
                    platformReady = true
                    lastFailure = null
                    true
                } else {
                    lastFailure = msg.ifBlank { "FirebasePlatform.initializeFirebasePlatform failed" }
                    false
                }
            }
        }
    }
}
