package com.eventmanager.app.data.remote

import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.PlatformContext
import kotlin.coroutines.cancellation.CancellationException

object FirebaseTeamActions {
    suspend fun assignRole(
        platformContext: PlatformContext?,
        settings: SettingsManager,
        uid: String,
        email: String?,
        role: MemberRole,
    ): Result<Unit> = runCatchingExceptCancellation {
        val org = settings.resolveWritableFirebaseOrgId()
        val gateway = createFirestoreGateway(platformContext, settings)
        if (!gateway.isAvailable()) {
            error("NOT_READY")
        }
        MemberRoleAdmin.assignTeamMember(
            gateway = gateway,
            orgId = org,
            uid = uid,
            role = role,
            email = email,
            allowedEmailDomains = settings.getAllowedEmailDomains(),
        )
    }

    suspend fun loadMembers(
        platformContext: PlatformContext?,
        settings: SettingsManager,
    ): Result<List<FirebaseTeamMemberListing>> = runCatchingExceptCancellation {
        val org = settings.resolveWritableFirebaseOrgId()
        if (org.isBlank()) error("NO_ORG")
        val gateway = createFirestoreGateway(platformContext, settings)
        if (!gateway.isAvailable()) {
            error("NOT_READY")
        }
        gateway.listMembers(org)
    }
}

internal fun resolveMemberUid(
    records: List<FirestoreMemberRecord>,
    email: String,
    selfUid: String?,
): Result<String> {
    val wanted = email.trim().lowercase()
    if (wanted.isEmpty()) return Result.failure(IllegalStateException("NOT_FOUND"))
    val matches = records.filter { it.email?.trim()?.lowercase() == wanted }
    return when {
        matches.isEmpty() -> Result.failure(IllegalStateException("NOT_FOUND"))
        matches.size > 1 -> Result.failure(IllegalStateException("AMBIGUOUS"))
        matches[0].uid == selfUid -> Result.failure(IllegalStateException("SELF"))
        else -> Result.success(matches[0].uid)
    }
}

private suspend fun memberRecordsOf(gateway: FirestoreGateway, org: String): List<FirestoreMemberRecord> =
    (gateway as? GitLiveFirestoreGateway)?.listMemberRecords(org) ?: emptyList()

private suspend fun resolveUidForEmail(
    platformContext: PlatformContext?,
    gateway: FirestoreGateway,
    org: String,
    email: String,
): String {
    val selfUid = createFirebaseAuthService(platformContext).currentUserId()
    return resolveMemberUid(memberRecordsOf(gateway, org), email, selfUid).getOrThrow()
}

suspend fun FirebaseTeamActions.setRoleByEmail(
    platformContext: PlatformContext?,
    settings: SettingsManager,
    email: String,
    role: MemberRole,
): Result<Unit> = runCatchingExceptCancellation {
    val org = settings.resolveWritableFirebaseOrgId()
    if (org.isBlank()) error("NO_ORG")
    val gateway = createFirestoreGateway(platformContext, settings)
    if (!gateway.isAvailable()) error("NOT_READY")
    val uid = try {
        resolveUidForEmail(platformContext, gateway, org, email)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (isFirestorePermissionDenied(e)) error("PERMISSION")
        throw e
    }
    MemberRoleAdmin.assignTeamMember(
        gateway = gateway,
        orgId = org,
        uid = uid,
        role = role,
        email = email.trim(),
        allowedEmailDomains = settings.getAllowedEmailDomains(),
    )
}

suspend fun FirebaseTeamActions.removeMemberByEmail(
    platformContext: PlatformContext?,
    settings: SettingsManager,
    email: String,
): Result<Unit> = runCatchingExceptCancellation {
    val org = settings.resolveWritableFirebaseOrgId()
    if (org.isBlank()) error("NO_ORG")
    val gateway = createFirestoreGateway(platformContext, settings)
    if (!gateway.isAvailable()) error("NOT_READY")
    try {
        val uid = resolveUidForEmail(platformContext, gateway, org, email)
        gateway.deleteDocument(org, "members", uid)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (isFirestorePermissionDenied(e)) error("PERMISSION")
        throw e
    }
}

private suspend inline fun <T> runCatchingExceptCancellation(block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }
