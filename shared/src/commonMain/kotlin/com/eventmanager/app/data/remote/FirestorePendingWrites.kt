package com.eventmanager.app.data.remote

/**
 * Blocks until the native Firestore SDK has pushed local [set]s to the server.
 * GitLive [set] completes as soon as the document is in the local cache, which is why a
 * guest-form create can show a public link while the public page still reads "closed".
 */
internal expect suspend fun firestoreWaitForPendingWrites()
