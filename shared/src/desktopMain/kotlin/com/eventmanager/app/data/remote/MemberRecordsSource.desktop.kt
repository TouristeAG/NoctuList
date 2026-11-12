package com.eventmanager.app.data.remote

internal actual suspend fun platformMemberRecords(
    gateway: FirestoreGateway,
    orgId: String,
): List<FirestoreMemberRecord>? = when (gateway) {
    is DesktopFirestoreGateway -> gateway.listMemberRecords(orgId)
    is GitLiveFirestoreGateway -> gateway.listMemberRecords(orgId)
    else -> null
}
