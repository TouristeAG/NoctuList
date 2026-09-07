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
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.AppStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The join flow must react to *why* a member doc is unreachable. Collapsing "absent", "denied" and
 * "offline" into one answer is what made a re-join after a factory reset fail forever with
 * PERMISSION_DENIED: the app fell back to an admin bootstrap, which rewrites an existing member
 * doc as `role: admin` — refused by the rules, and destructive if it were not.
 */
class FirebaseOrgBootstrapProbeTest {
    private val orgId = "Collectif-Nocturne"
    private val uid = "GQLq0lD3SdZ9kB4DKSA2Kp2lvTk2"

    @Test
    fun alreadyMember_writesNothing() = runBlocking {
        val gateway = FakeFirestoreGateway(MembershipProbe.Member("member"))
        val settings = settingsWith(invitationCode = "ABCD2345", joinImported = true)

        FirebaseOrgBootstrap.ensureOrgBootstrappedIfNeeded(
            gateway = gateway,
            settings = settings,
            orgId = orgId,
            signedInUid = uid,
            signedInEmail = "cntablettes@gmail.com",
        )

        assertTrue(gateway.writes.isEmpty(), "a confirmed member must not be re-written")
        assertEquals(
            "",
            settings.getFirebaseBootstrapCode(),
            "an imported invitation code is dropped once membership is confirmed",
        )
    }

    @Test
    fun offline_writesNothing() = runBlocking {
        val gateway = FakeFirestoreGateway(MembershipProbe.Unavailable)
        val settings = settingsWith(invitationCode = "ABCD2345")

        FirebaseOrgBootstrap.ensureOrgBootstrappedIfNeeded(
            gateway = gateway,
            settings = settings,
            orgId = orgId,
            signedInUid = uid,
            signedInEmail = "a@b.ch",
        )

        assertTrue(gateway.writes.isEmpty(), "unknown membership must never trigger a guess")
        assertEquals("ABCD2345", settings.getFirebaseBootstrapCode())
    }

    @Test
    fun absentWithInvitationCode_joinsAsMemberNeverAdmin() = runBlocking {
        val gateway = FakeFirestoreGateway(MembershipProbe.Absent)
        val settings = settingsWith(invitationCode = "ABCD2345")

        FirebaseOrgBootstrap.ensureOrgBootstrappedIfNeeded(
            gateway = gateway,
            settings = settings,
            orgId = orgId,
            signedInUid = uid,
            signedInEmail = "a@b.ch",
        )

        val memberWrite = gateway.writes.single { it.collection == "members" }
        assertEquals("member", memberWrite.data["role"])
        assertNotNull(memberWrite.data["bootstrapCode"])
        assertTrue(
            gateway.writes.none { it.collection == "metadata" },
            "joining must not touch metadata/config",
        )
    }

    @Test
    fun deniedWithoutInvitationCode_failsWithActionableMessageAndNoWrite() = runBlocking {
        val gateway = FakeFirestoreGateway(MembershipProbe.Denied)
        val settings = settingsWith(invitationCode = "")

        val error = runCatching {
            FirebaseOrgBootstrap.ensureOrgBootstrappedIfNeeded(
                gateway = gateway,
                settings = settings,
                orgId = orgId,
                signedInUid = uid,
                signedInEmail = "a@b.ch",
            )
        }.exceptionOrNull()

        assertNotNull(error)
        assertTrue(
            error.message.orEmpty().contains("firestore.rules"),
            "a refused read must point at the rules, not leak Firestore's opaque message: " +
                error.message,
        )
        assertTrue(gateway.writes.isEmpty())
    }

    @Test
    fun absentWithoutInvitationCode_bootstrapsOnlyOnExplicitCreateIntent() = runBlocking {
        val ensure = FakeFirestoreGateway(MembershipProbe.Absent)
        val ensureError = runCatching {
            FirebaseOrgBootstrap.ensureOrgBootstrappedIfNeeded(
                gateway = ensure,
                settings = settingsWith(invitationCode = ""),
                orgId = orgId,
                intent = OrgBootstrapIntent.ENSURE_MEMBERSHIP,
                signedInUid = uid,
                signedInEmail = "a@b.ch",
            )
        }.exceptionOrNull()
        assertNotNull(ensureError)
        assertTrue(ensure.writes.isEmpty(), "startup must never create an organization")

        val create = FakeFirestoreGateway(MembershipProbe.Absent)
        val createSettings = settingsWith(invitationCode = "")
        FirebaseOrgBootstrap.ensureOrgBootstrappedIfNeeded(
            gateway = create,
            settings = createSettings,
            orgId = orgId,
            intent = OrgBootstrapIntent.CREATE_IF_MISSING,
            signedInUid = uid,
            signedInEmail = "a@b.ch",
        )

        assertEquals("admin", create.writes.first { it.collection == "members" }.data["role"])
        assertTrue(create.writes.any { it.collection == "metadata" })
        assertTrue(createSettings.getFirebaseBootstrapCode().isNotBlank())
    }

    @Test
    fun bootstrapWithoutLocalDomains_leavesOrgAllowlistAlone() = runBlocking {
        val gateway = FakeFirestoreGateway(MembershipProbe.Absent)

        FirebaseOrgBootstrap.ensureOrgBootstrappedIfNeeded(
            gateway = gateway,
            settings = settingsWith(invitationCode = ""),
            orgId = orgId,
            intent = OrgBootstrapIntent.CREATE_IF_MISSING,
            signedInUid = uid,
            signedInEmail = "a@b.ch",
        )

        val config = gateway.writes.first { it.collection == "metadata" }.data
        assertNotNull(config["bootstrapCodeHash"])
        assertFalse(
            config.containsKey("allowedEmailDomains"),
            "an empty local list must not clear the org's email allowlist",
        )
    }

    @Test
    fun permissionDeniedDetectionWalksTheCauseChain() {
        assertTrue(
            isFirestorePermissionDenied(
                IllegalStateException(
                    "write failed",
                    RuntimeException("Status{code=PERMISSION_DENIED, description=Missing or insufficient permissions.}"),
                ),
            ),
        )
        assertFalse(isFirestorePermissionDenied(RuntimeException("DEADLINE_EXCEEDED")))
        assertFalse(isFirestorePermissionDenied(null))
    }

    @Test
    fun joinPayloadImportMarksConfigAsImported() {
        val settings = settingsWith(invitationCode = "")
        assertFalse(settings.isFirebaseJoinImported())

        settings.applyFirebaseJoinPayload(
            FirebaseJoinPayload(
                orgId = orgId,
                projectId = "proj",
                applicationId = "1:2:android:3",
                apiKey = "key",
                webClientId = "client",
                webClientSecret = "secret",
                bootstrapCode = "ABCD2345",
            ),
        )

        assertTrue(
            settings.isFirebaseJoinImported(),
            "join UIs rely on this flag to stop echoing the invitation code back",
        )
        assertEquals("ABCD2345", settings.getFirebaseBootstrapCode())
    }

    private fun settingsWith(invitationCode: String, joinImported: Boolean = false): SettingsManager {
        val settings = SettingsManager(InMemoryAppStorage())
        if (invitationCode.isNotBlank()) settings.setFirebaseBootstrapCode(invitationCode)
        if (joinImported) settings.setFirebaseJoinImported(true)
        settings.setFirebaseOrgId(orgId)
        return settings
    }
}

private class InMemoryAppStorage : AppStorage {
    private val values = mutableMapOf<String, Any>()

    override fun getString(key: String, default: String): String =
        values[key] as? String ?: default

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        values[key] as? Boolean ?: default

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default

    override fun putInt(key: String, value: Int) {
        values[key] = value
    }

    override fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default

    override fun putLong(key: String, value: Long) {
        values[key] = value
    }

    override fun getFloat(key: String, default: Float): Float = values[key] as? Float ?: default

    override fun putFloat(key: String, value: Float) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun contains(key: String): Boolean = values.containsKey(key)

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, default: Set<String>): Set<String> =
        values[key] as? Set<String> ?: default

    override fun putStringSet(key: String, value: Set<String>) {
        values[key] = value
    }

    override fun clear() {
        values.clear()
    }
}

private data class RecordedWrite(
    val collection: String,
    val docId: String,
    val data: Map<String, Any?>,
)

private class FakeFirestoreGateway(
    private val probe: MembershipProbe,
) : FirestoreGateway {
    val writes = mutableListOf<RecordedWrite>()

    override fun isAvailable(): Boolean = true

    override suspend fun probeMembership(orgId: String, uid: String): MembershipProbe = probe

    override suspend fun upsertDocument(
        orgId: String,
        collection: String,
        docId: String,
        data: Map<String, Any?>,
    ) {
        writes += RecordedWrite(collection, docId, data)
    }

    override suspend fun startOrgListeners(
        orgIds: List<String>,
        onChange: suspend (FirestoreRemoteChange) -> Unit,
    ) = Unit

    override fun stopOrgListeners() = Unit
    override suspend fun flushPendingWrites() = Unit
    override suspend fun deleteDocument(orgId: String, collection: String, docId: String) = Unit

    override suspend fun pullAllIntoRepository(
        orgId: String,
        repository: EventManagerRepository,
        collections: Collection<String>?,
    ) = Unit

    override suspend fun applyChangeToRepository(
        change: FirestoreRemoteChange,
        repository: EventManagerRepository,
    ) = Unit

    override suspend fun readBackendAnnouncement(orgId: String): InstitutionBackendAnnouncement? = null
    override suspend fun readMemberRole(orgId: String, uid: String): String? =
        (probe as? MembershipProbe.Member)?.role

    override suspend fun isOrgAccessibleOnServer(orgId: String, uid: String): Boolean =
        probe is MembershipProbe.Member

    override suspend fun writeBackendAnnouncement(
        orgId: String,
        announcement: InstitutionBackendAnnouncement,
    ) = Unit

    override suspend fun runPeopleCounterTransaction(
        orgId: String,
        venueName: String,
        count: Int,
        deviceId: String,
        writerAccountEmail: String,
        lastModified: Long,
    ) = Unit

    override suspend fun runLedgerTransaction(
        orgId: String,
        transfer: AccountTransfer,
        holderKey: String,
        newBalance: Double,
        buffer: Double,
    ): Boolean = false

    override fun guestToMap(guest: Guest): Map<String, Any?> = emptyMap()
    override fun volunteerToMap(volunteer: Volunteer): Map<String, Any?> = emptyMap()
    override fun jobToMap(job: Job): Map<String, Any?> = emptyMap()
    override fun jobTypeToMap(config: JobTypeConfig): Map<String, Any?> = emptyMap()
    override fun venueToMap(venue: VenueEntity): Map<String, Any?> = emptyMap()
    override fun salesItemToMap(item: SalesSheetItem): Map<String, Any?> = emptyMap()
    override fun transferToMap(transfer: AccountTransfer): Map<String, Any?> = emptyMap()
    override fun guestFormToMap(form: GuestForm): Map<String, Any?> = emptyMap()
}
