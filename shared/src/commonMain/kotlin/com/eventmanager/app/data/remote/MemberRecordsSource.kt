package com.eventmanager.app.data.remote

/**
 * Server-confirmed member rows (with document ids) for the transport behind [gateway], or null
 * when the gateway cannot provide them. Data layer only: the ids never reach the UI.
 */
internal expect suspend fun platformMemberRecords(
    gateway: FirestoreGateway,
    orgId: String,
): List<FirestoreMemberRecord>?
