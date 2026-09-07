package com.eventmanager.app.data.models

import com.eventmanager.app.data.remote.BackendType

/**
 * Venue accesses, contact mail, QR codes, bar discount, credits and entry validation for temporary
 * guests all live on Firebase-only fields. On the Sheets backend the temporary guest list keeps its
 * historical seven-column contract and none of these surfaces are shown.
 */
fun temporaryGuestFeaturesEnabled(backend: BackendType): Boolean =
    backend == BackendType.FIREBASE
