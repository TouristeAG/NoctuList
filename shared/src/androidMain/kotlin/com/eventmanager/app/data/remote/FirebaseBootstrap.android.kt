package com.eventmanager.app.data.remote

import com.eventmanager.app.platform.PlatformContext
import com.google.firebase.FirebaseApp
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.FirebaseOptions
import dev.gitlive.firebase.app
import dev.gitlive.firebase.initialize

actual object FirebaseBootstrap {
    actual fun ensureInitialized(
        platformContext: PlatformContext,
        options: FirebaseProjectOptions?,
    ): Boolean {
        val opts = options?.takeIf { it.isComplete() }
        if (isInitialized()) {
            if (opts == null || nativeMatches(opts)) return true
            release()
        }
        if (opts == null) return false
        return runCatching {
            Firebase.initialize(
                context = platformContext.androidContext,
                options = FirebaseOptions(
                    applicationId = opts.applicationId,
                    apiKey = opts.apiKey,
                    projectId = opts.projectId,
                    gcmSenderId = opts.gcmSenderId.ifBlank { null },
                    storageBucket = opts.storageBucket.ifBlank { null },
                ),
            )
            true
        }.getOrDefault(false)
    }

    actual fun isInitialized(): Boolean = runCatching {
        Firebase.app
        true
    }.getOrDefault(false)

    actual fun release() {
        runCatching { FirebaseApp.getInstance().delete() }
    }

    private fun nativeMatches(opts: FirebaseProjectOptions): Boolean {
        val native = runCatching { FirebaseApp.getInstance().options }.getOrNull() ?: return false
        return native.apiKey == opts.apiKey &&
            native.applicationId == opts.applicationId &&
            native.projectId == opts.projectId
    }
}
