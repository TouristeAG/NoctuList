package com.eventmanager.app.data.remote

import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.PlatformContext
import kotlin.coroutines.cancellation.CancellationException

object FirebaseTeamActions {
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
): Result<String> = resolveMemberRecord(records, email, selfUid).map { it.uid }

internal fun resolveMemberRecord(
    records: List<FirestoreMemberRecord>,
    email: String,
    selfUid: String?,
): Result<FirestoreMemberRecord> {
    val wanted = email.trim().lowercase()
    if (wanted.isEmpty()) return Result.failure(IllegalStateException("NOT_FOUND"))
    val matches = records.filter { it.email?.trim()?.lowercase() == wanted }
    return when {
        matches.isEmpty() -> Result.failure(IllegalStateException("NOT_FOUND"))
        matches.size > 1 -> Result.failure(IllegalStateException("AMBIGUOUS"))
        matches[0].uid == selfUid -> Result.failure(IllegalStateException("SELF"))
        else -> Result.success(matches[0])
    }
}

/** Like [resolveMemberRecord] but fails closed (NOT_READY) when the signed-in uid is unknown. */
internal fun resolveMemberRecordForActor(
    records: List<FirestoreMemberRecord>,
    email: String,
    selfUid: String?,
): Result<FirestoreMemberRecord> {
    if (selfUid.isNullOrBlank()) return Result.failure(IllegalStateException("NOT_READY"))
    return resolveMemberRecord(records, email, selfUid)
}

/** Server-only member rows; NOT_READY when this gateway cannot provide them. */
internal suspend fun serverMemberRecords(gateway: FirestoreGateway, org: String): List<FirestoreMemberRecord> =
    platformMemberRecords(gateway, org) ?: error("NOT_READY")

internal suspend fun resolveServerMember(
    gateway: FirestoreGateway,
    org: String,
    email: String,
    selfUid: String?,
): FirestoreMemberRecord {
    if (selfUid.isNullOrBlank()) error("NOT_READY")
    val records = try {
        serverMemberRecords(gateway, org)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (isFirestorePermissionDenied(e)) error("PERMISSION")
        if (e.message == "NOT_READY") throw e
        error("NOT_READY")
    }
    return resolveMemberRecordForActor(records, email, selfUid).getOrThrow()
}

private suspend fun gatewayForMutation(
    platformContext: PlatformContext?,
    settings: SettingsManager,
): Pair<String, FirestoreGateway> {
    val org = settings.resolveWritableFirebaseOrgId()
    if (org.isBlank()) error("NO_ORG")
    val gateway = createFirestoreGateway(platformContext, settings)
    if (!gateway.isAvailable()) error("NOT_READY")
    return org to gateway
}

suspend fun FirebaseTeamActions.setRoleByEmail(
    platformContext: PlatformContext?,
    settings: SettingsManager,
    email: String,
    role: MemberRole,
): Result<Unit> = runCatchingExceptCancellation {
    val (org, gateway) = gatewayForMutation(platformContext, settings)
    val selfUid = createFirebaseAuthService(platformContext).currentUserId()
    val member = resolveServerMember(gateway, org, email, selfUid)
    // Only update: never re-create a member removed in the meantime.
    val stillThere = try {
        gateway.readMemberRoleFromServer(org, member.uid)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        if (isFirestorePermissionDenied(e)) error("PERMISSION")
        error("NOT_READY")
    }
    if (stillThere == null) error("NOT_FOUND")
    MemberRoleAdmin.assignTeamMember(
        gateway = gateway,
        orgId = org,
        uid = member.uid,
        role = role,
        email = member.email ?: email.trim(),
        allowedEmailDomains = settings.getAllowedEmailDomains(),
    )
}

suspend fun FirebaseTeamActions.removeMemberByEmail(
    platformContext: PlatformContext?,
    settings: SettingsManager,
    email: String,
): Result<Unit> = runCatchingExceptCancellation {
    val (org, gateway) = gatewayForMutation(platformContext, settings)
    val selfUid = createFirebaseAuthService(platformContext).currentUserId()
    val member = resolveServerMember(gateway, org, email, selfUid)
    try {
        gateway.deleteDocument(org, "members", member.uid)
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
