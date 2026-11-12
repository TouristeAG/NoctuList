package com.eventmanager.app.data.remote

internal actual suspend fun platformMemberRecords(
    gateway: FirestoreGateway,
    orgId: String,
): List<FirestoreMemberRecord>? = (gateway as? GitLiveFirestoreGateway)?.listMemberRecords(orgId)
