package com.eventmanager.app.data.remote

import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.GuestForm
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.SalesSheetItem
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.repository.EventManagerRepository

data class FirestoreRemoteChange(
    val orgId: String,
    val collection: String,
    val documentId: String,
    val data: Map<String, Any?>?,
    val deleted: Boolean,
)

data class FirebaseTeamMemberListing(
    val email: String?,
    val role: String?,
)

/**
 * Internal member row including Firestore document id. Never expose [uid] to UI or local settings.
 */
internal data class FirestoreMemberRecord(
    val uid: String,
    val email: String?,
    val role: String?,
)

/**
 * Server-confirmed membership state for `orgs/{orgId}/members/{uid}`.
 *
 * [Absent] and [Denied] must never be collapsed together: bootstrapping an org admin on top of a
 * [Denied] probe rewrites an existing member doc as `role: admin`, which the rules refuse and
 * which would otherwise wipe the org invitation code.
 */
sealed class MembershipProbe {
    data class Member(val role: String) : MembershipProbe()

    /** The server confirmed the member document does not exist. */
    data object Absent : MembershipProbe()

    /** The server refused the read (rules not published, email domain not allowed, ...). */
    data object Denied : MembershipProbe()

    /** Offline, timed out, or Firestore not configured — membership is unknown, do not write. */
    data object Unavailable : MembershipProbe()
}

/**
 * Abstraction over GitLive Firestore so Desktop spike / missing config can no-op safely.
 */
interface FirestoreGateway {
    fun isAvailable(): Boolean
    /** True when the last Firestore snapshot was confirmed by the server (not cache-only). */
    fun isServerReachable(): Boolean = false
    fun setServerReachabilityListener(listener: (() -> Unit)?) {}
    suspend fun startOrgListeners(orgIds: List<String>, onChange: suspend (FirestoreRemoteChange) -> Unit)
    fun stopOrgListeners()
    suspend fun flushPendingWrites()
    suspend fun upsertDocument(orgId: String, collection: String, docId: String, data: Map<String, Any?>)
    suspend fun deleteDocument(orgId: String, collection: String, docId: String)
    suspend fun pullAllIntoRepository(
        orgId: String,
        repository: EventManagerRepository,
        collections: Collection<String>? = null,
    )
    suspend fun applyChangeToRepository(change: FirestoreRemoteChange, repository: EventManagerRepository)
    suspend fun readBackendAnnouncement(orgId: String): InstitutionBackendAnnouncement?
    suspend fun readMemberRole(orgId: String, uid: String): String?
    /** Member role from the Firestore server (not the local cache). */
    suspend fun readMemberRoleFromServer(orgId: String, uid: String): String? = readMemberRole(orgId, uid)
    /** Confirms membership against the Firestore server (avoids optimistic offline cache). */
    suspend fun isOrgAccessibleOnServer(orgId: String, uid: String): Boolean

    /**
     * Server-confirmed membership, distinguishing "no member doc" from "read refused" and from
     * "cannot reach the server". Callers decide between bootstrap, join, and doing nothing.
     */
    suspend fun probeMembership(orgId: String, uid: String): MembershipProbe {
        if (!isAvailable()) return MembershipProbe.Unavailable
        val role = readMemberRoleFromServer(orgId, uid)?.takeIf { it.isNotBlank() }
            ?: return MembershipProbe.Unavailable
        return MembershipProbe.Member(role)
    }
    suspend fun listMembers(orgId: String): List<FirebaseTeamMemberListing> = emptyList()
    suspend fun writeBackendAnnouncement(orgId: String, announcement: InstitutionBackendAnnouncement)
    suspend fun runPeopleCounterTransaction(
        orgId: String,
        venueName: String,
        count: Int,
        deviceId: String,
        writerAccountEmail: String = "",
        lastModified: Long = 0L,
    )
    suspend fun runLedgerTransaction(
        orgId: String,
        transfer: AccountTransfer,
        holderKey: String,
        newBalance: Double,
        buffer: Double,
    ): Boolean

    fun guestToMap(guest: Guest): Map<String, Any?>
    fun volunteerToMap(volunteer: Volunteer): Map<String, Any?>
    fun jobToMap(job: Job): Map<String, Any?>
    fun jobTypeToMap(config: JobTypeConfig): Map<String, Any?>
    fun venueToMap(venue: VenueEntity): Map<String, Any?>
    fun salesItemToMap(item: SalesSheetItem): Map<String, Any?>
    fun transferToMap(transfer: AccountTransfer): Map<String, Any?>
    fun guestFormToMap(form: GuestForm): Map<String, Any?>
}

/** Safe default when Firebase is not configured — used for Sheets-only installs. */
class NoOpFirestoreGateway : FirestoreGateway {
    override fun isAvailable(): Boolean = false
    override suspend fun startOrgListeners(orgIds: List<String>, onChange: suspend (FirestoreRemoteChange) -> Unit) {}
    override fun stopOrgListeners() {}
    override suspend fun flushPendingWrites() {}
    override suspend fun upsertDocument(orgId: String, collection: String, docId: String, data: Map<String, Any?>) {}
    override suspend fun deleteDocument(orgId: String, collection: String, docId: String) {}
    override suspend fun pullAllIntoRepository(
        orgId: String,
        repository: EventManagerRepository,
        collections: Collection<String>?,
    ) {}
    override suspend fun applyChangeToRepository(change: FirestoreRemoteChange, repository: EventManagerRepository) {}
    override suspend fun readBackendAnnouncement(orgId: String): InstitutionBackendAnnouncement? = null
    override suspend fun readMemberRole(orgId: String, uid: String): String? = null
    override suspend fun isOrgAccessibleOnServer(orgId: String, uid: String): Boolean = false
    override suspend fun writeBackendAnnouncement(orgId: String, announcement: InstitutionBackendAnnouncement) {}
    override suspend fun runPeopleCounterTransaction(
        orgId: String,
        venueName: String,
        count: Int,
        deviceId: String,
        writerAccountEmail: String,
        lastModified: Long,
    ) {}
    override suspend fun runLedgerTransaction(
        orgId: String,
        transfer: AccountTransfer,
        holderKey: String,
        newBalance: Double,
        buffer: Double,
    ): Boolean = false

    override fun guestToMap(guest: Guest) = mapOf("nanoId" to guest.nanoId, "name" to guest.name)
    override fun volunteerToMap(volunteer: Volunteer) = mapOf("id" to volunteer.id, "name" to volunteer.name)
    override fun jobToMap(job: Job) = mapOf(
        "jobNanoId" to job.jobNanoId,
        "volunteerId" to job.volunteerId,
        "jobTypeName" to job.jobTypeName,
    )
    override fun jobTypeToMap(config: JobTypeConfig) = mapOf("name" to config.name)
    override fun venueToMap(venue: VenueEntity) = mapOf("name" to venue.name)
    override fun salesItemToMap(item: SalesSheetItem) = mapOf("name" to item.name)
    override fun transferToMap(transfer: AccountTransfer) = mapOf(
        "transferId" to transfer.transferId,
        "sourceReference" to transfer.sourceReference,
        "amount" to transfer.amount,
    )
    override fun guestFormToMap(form: GuestForm) = mapOf("formId" to form.formId)
}
