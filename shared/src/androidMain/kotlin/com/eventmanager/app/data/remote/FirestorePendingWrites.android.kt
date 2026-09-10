package com.eventmanager.app.data.remote

import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.firestore.android
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.tasks.await

internal actual suspend fun firestoreWaitForPendingWrites() {
    val db = runCatching { Firebase.firestore.android }.getOrNull() ?: return
    runCatching { db.waitForPendingWrites().await() }
}
