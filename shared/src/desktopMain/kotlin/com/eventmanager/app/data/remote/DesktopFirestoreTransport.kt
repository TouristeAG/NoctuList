package com.eventmanager.app.data.remote

/**
 * Linux packaged builds crash or hang inside GitLive's gRPC channel on the first pull
 * (Netty epoll / ALPN). HTTP Firestore is already implemented; use it unless the
 * process explicitly asks for gRPC with `-Dnoctulist.firestore.transport=gitlive`.
 */
internal fun desktopFirestorePrefersRest(osName: String, transportOverride: String?): Boolean {
    when (transportOverride?.trim()?.lowercase()) {
        "rest" -> return true
        "gitlive", "grpc" -> return false
    }
    return osName.contains("linux", ignoreCase = true)
}
