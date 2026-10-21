package com.eventmanager.app.data.remote

import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.PlatformContext

/**
 * macOS and Windows keep the GitLive channel (realtime listeners).
 * Linux uses the HTTP gateway: GitLive's gRPC transport crashes the process on the
 * first pull there. `-Dnoctulist.firestore.transport=gitlive` or `rest` overrides this.
 */
actual fun createFirestoreGateway(
    platformContext: PlatformContext?,
    settingsManager: SettingsManager?,
): FirestoreGateway {
    if (platformContext != null && settingsManager != null) {
        FirebaseBootstrap.ensureInitialized(
            platformContext,
            FirebaseOptionsReader.fromSettings(settingsManager),
        )
    }
    val useRest = desktopFirestorePrefersRest(
        System.getProperty("os.name").orEmpty(),
        System.getProperty("noctulist.firestore.transport"),
    )
    return if (useRest) {
        DesktopFirestoreGateway(platformContext, settingsManager)
    } else {
        GitLiveFirestoreGateway(platformContext, settingsManager)
    }
}
