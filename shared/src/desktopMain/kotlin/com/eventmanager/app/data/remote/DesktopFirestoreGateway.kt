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
import com.eventmanager.app.data.sync.InstitutionSettingsKeys
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Desktop Firestore: **GitLive first** (real-time listeners like dev mode), **REST fallback**
 * when gRPC hangs in jpackage (probe timeout) or individual calls time out.
 */
internal class DesktopFirestoreGateway(
    private val platformContext: PlatformContext?,
    private val settingsManager: SettingsManager?,
) : FirestoreGateway {
    private enum class Transport { GITLIVE, REST }

    private val gitlive by lazy { GitLiveFirestoreGateway(platformContext, settingsManager) }
    private val rest = when {
        platformContext != null && settingsManager != null ->
            DesktopFirestoreRestClient(platformContext, settingsManager)
        else -> null
    }

    @Volatile
    private var transport: Transport = Transport.GITLIVE

    @Volatile
    private var serverReachable = false

    private val listenerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var listenerWatchdog: CoroutineJob? = null
    private var changePoller: DesktopFirestoreRestChangePoller? = null
    private val gitLiveListenerConfirmed = AtomicBoolean(false)

    private var reachabilityListener: (() -> Unit)? = null

    private val watchedCollections = listOf(
        "guests",
        "volunteers",
        "jobs",
        "jobTypeConfigs",
        "venues",
        "salesItems",
        "transfers",
        "accounts",
        "institutionSettings",
        "metadata",
        "guestForms",
    )

    override fun isAvailable(): Boolean = gitlive.isAvailable() || rest?.isReady() == true

    override fun isServerReachable(): Boolean = serverReachable

    override fun setServerReachabilityListener(listener: (() -> Unit)?) {
        reachabilityListener = listener
    }

    private fun noteServerReachable(fromServer: Boolean) {
        if (serverReachable == fromServer) return
        serverReachable = fromServer
        reachabilityListener?.invoke()
    }

    override suspend fun startOrgListeners(
        orgIds: List<String>,
        onChange: suspend (FirestoreRemoteChange) -> Unit,
    ) {
        val distinct = orgIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return
        stopOrgListeners()

        if (transport == Transport.REST || !gitlive.isAvailable()) {
            logTransport("listeners", Transport.REST)
            startRestPoller(distinct, onChange)
            return
        }

        transport = Transport.GITLIVE
        gitLiveListenerConfirmed.set(false)
        logTransport("listeners", Transport.GITLIVE)

        listenerWatchdog = listenerScope.launch {
            delay(GITLIVE_LISTENER_PROBE_MS)
            if (!gitLiveListenerConfirmed.get()) {
                println(
                    "Desktop Firestore: GitLive listeners did not confirm within " +
                        "${GITLIVE_LISTENER_PROBE_MS / 1000}s — switching to REST poller",
                )
                transport = Transport.REST
                gitlive.stopOrgListeners()
                startRestPoller(distinct, onChange)
            }
        }

        gitlive.startOrgListeners(distinct) { change ->
            gitLiveListenerConfirmed.set(true)
            transport = Transport.GITLIVE
            noteServerReachable(true)
            onChange(change)
        }
    }

    override fun stopOrgListeners() {
        listenerWatchdog?.cancel()
        listenerWatchdog = null
        gitlive.stopOrgListeners()
        changePoller?.stop()
        changePoller = null
        gitLiveListenerConfirmed.set(false)
        noteServerReachable(false)
    }

    private fun startRestPoller(
        orgIds: List<String>,
        onChange: suspend (FirestoreRemoteChange) -> Unit,
    ) {
        val client = rest ?: return
        val interval = FirestoreRealtimeCapability.listenerPollIntervalMs() ?: 20_000L
        changePoller = DesktopFirestoreRestChangePoller(
            client = client,
            orgIds = orgIds,
            collections = watchedCollections,
            pollIntervalMs = interval,
        ).also { poller ->
            poller.start { change ->
                noteServerReachable(true)
                onChange(change)
            }
        }
    }

    override suspend fun flushPendingWrites() {
        gitlive.flushPendingWrites()
    }

    override suspend fun upsertDocument(
        orgId: String,
        collection: String,
        docId: String,
        data: Map<String, Any?>,
    ) = withContext(Dispatchers.IO) {
        if (tryGitLive { gitlive.upsertDocument(orgId, collection, docId, data) }) {
            noteServerReachable(true)
            return@withContext
        }
        val client = rest ?: throw IllegalStateException("Firestore REST client not configured")
        client.upsertDocument(orgId, collection, docId, data)
        noteServerReachable(true)
    }

    override suspend fun deleteDocument(orgId: String, collection: String, docId: String) =
        withContext(Dispatchers.IO) {
            if (tryGitLive { gitlive.deleteDocument(orgId, collection, docId) }) {
                noteServerReachable(true)
                return@withContext
            }
            rest?.deleteDocument(orgId, collection, docId)
            noteServerReachable(true)
        }

    override suspend fun pullAllIntoRepository(
        orgId: String,
        repository: EventManagerRepository,
        collections: Collection<String>?,
    ) = withContext(Dispatchers.IO) {
        if (orgId.isBlank()) return@withContext
        if (tryGitLivePull { gitlive.pullAllIntoRepository(orgId, repository, collections) }) {
            noteServerReachable(true)
            return@withContext
        }
        pullAllViaRest(orgId, repository, collections)
    }

    private suspend fun tryGitLivePull(block: suspend () -> Unit): Boolean {
        if (transport == Transport.REST || !gitlive.isAvailable()) return false
        val completed = withTimeoutOrNull(PULL_TIMEOUT_MS) {
            block()
            true
        }
        if (completed == true) return true
        markRestFallback("pullAll timed out")
        return false
    }

    private suspend fun pullAllViaRest(
        orgId: String,
        repository: EventManagerRepository,
        collections: Collection<String>?,
    ) {
        val client = rest ?: return
        markRestFallback("pullAll")
        val requested = collections
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.toSet()
        val toPull = if (requested.isNullOrEmpty()) {
            watchedCollections
        } else {
            watchedCollections.filter { it in requested }
        }
        for (collection in toPull) {
            val docs = client.listCollection(orgId, collection)
            noteServerReachable(true)
            for ((docId, data) in docs) {
                if (data.isEmpty()) continue
                if (collection == "institutionSettings") {
                    val value = data["value"]?.toString().orEmpty()
                    val lm = when (val raw = data["lastModified"]) {
                        is Number -> raw.toLong()
                        is String -> raw.toLongOrNull() ?: System.currentTimeMillis()
                        else -> System.currentTimeMillis()
                    }
                    settingsManager?.applyInstitutionSettingFromRemote(docId, value, lm)
                    continue
                }
                if (collection == "metadata") continue
                FirestoreChangeApplier.apply(
                    FirestoreRemoteChange(
                        orgId = orgId,
                        collection = collection,
                        documentId = docId,
                        data = data,
                        deleted = false,
                    ),
                    repository,
                )
            }
        }
    }

    override suspend fun applyChangeToRepository(
        change: FirestoreRemoteChange,
        repository: EventManagerRepository,
    ) {
        gitlive.applyChangeToRepository(change, repository)
    }

    override suspend fun readBackendAnnouncement(orgId: String): InstitutionBackendAnnouncement? =
        withContext(Dispatchers.IO) {
            if (orgId.isBlank()) return@withContext null
            val fromGitLive = withTimeoutOrNull(READ_TIMEOUT_MS) {
                gitlive.readBackendAnnouncement(orgId)
            }
            if (fromGitLive != null) {
                noteServerReachable(true)
                return@withContext fromGitLive
            }
            val client = rest ?: return@withContext null
            markRestFallback("readBackendAnnouncement")
            runCatching {
                val data = client.getDocument(orgId, "metadata", "config") ?: return@withContext null
                val typeRaw = data["backendType"] as? String ?: return@withContext null
                noteServerReachable(true)
                InstitutionBackendAnnouncement(
                    backendType = BackendType.fromStorage(typeRaw),
                    migrationId = (data["migrationId"] as? String).orEmpty(),
                    migratedAt = (data["migratedAt"] as? Number)?.toLong()
                        ?: (data["migratedAt"] as? String)?.toLongOrNull()
                        ?: 0L,
                    migratedBy = (data["migratedBy"] as? String).orEmpty(),
                    firebaseOrgId = (data["firebaseOrgId"] as? String)?.takeIf { it.isNotBlank() },
                    sheetsSpreadsheetIdHint = (data["sheetsSpreadsheetIdHint"] as? String)
                        ?.takeIf { it.isNotBlank() },
                    firebaseProjectId = (data["firebaseProjectId"] as? String)?.takeIf { it.isNotBlank() },
                    firebaseApplicationId = (data["firebaseApplicationId"] as? String)?.takeIf { it.isNotBlank() },
                    firebaseWebClientId = (data["firebaseWebClientId"] as? String)?.takeIf { it.isNotBlank() },
                )
            }.getOrNull()
        }

    override suspend fun readMemberRole(orgId: String, uid: String): String? =
        readMemberRoleInternal(orgId, uid, fromServer = false)

    override suspend fun readMemberRoleFromServer(orgId: String, uid: String): String? =
        readMemberRoleInternal(orgId, uid, fromServer = true)

    override suspend fun isOrgAccessibleOnServer(orgId: String, uid: String): Boolean =
        readMemberRoleFromServer(orgId, uid) != null

    override suspend fun probeMembership(orgId: String, uid: String): MembershipProbe =
        withContext(Dispatchers.IO) {
            if (orgId.isBlank() || uid.isBlank() || isFirebaseOrgAllSentinel(orgId)) {
                return@withContext MembershipProbe.Unavailable
            }
            if (transport != Transport.REST && gitlive.isAvailable()) {
                val fromGitLive = withTimeoutOrNull(READ_TIMEOUT_MS) {
                    gitlive.probeMembership(orgId, uid)
                }
                if (fromGitLive != null && fromGitLive != MembershipProbe.Unavailable) {
                    noteServerReachable(true)
                    return@withContext fromGitLive
                }
            }
            val client = rest ?: return@withContext MembershipProbe.Unavailable
            markRestFallback("probeMembership")
            try {
                val data = client.getDocument(orgId, "members", uid)
                noteServerReachable(true)
                when {
                    data == null -> MembershipProbe.Absent
                    else -> MembershipProbe.Member(
                        data["role"]?.toString()?.trim()?.ifBlank { null }
                            ?: MemberRole.MEMBER.storageValue(),
                    )
                }
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (isFirestorePermissionDenied(e)) MembershipProbe.Denied
                else MembershipProbe.Unavailable
            }
        }

    override suspend fun listMembers(orgId: String): List<FirebaseTeamMemberListing> =
        withContext(Dispatchers.IO) {
            if (orgId.isBlank() || isFirebaseOrgAllSentinel(orgId)) return@withContext emptyList()
            val fromGitLive = withTimeoutOrNull(READ_TIMEOUT_MS) {
                gitlive.listMembers(orgId)
            }
            if (fromGitLive != null) {
                noteServerReachable(true)
                return@withContext fromGitLive
            }
            val client = rest ?: error("Firestore REST client not configured")
            markRestFallback("listMembers")
            runCatching {
                client.listCollection(orgId, "members")
                    .map { (_, data) ->
                        FirebaseTeamMemberListing(
                            email = data["email"]?.toString()?.trim()?.ifBlank { null },
                            role = data["role"]?.toString()?.trim()?.ifBlank { null },
                        )
                    }
                    .sortedWith(
                        compareBy<FirebaseTeamMemberListing> { MemberRole.fromStorage(it.role) != MemberRole.ADMIN }
                            .thenBy { it.email.orEmpty().lowercase() },
                    )
                    .also { noteServerReachable(true) }
            }.getOrElse { e ->
                println("Firebase team: listMembers failed: ${e.message}")
                throw e
            }
        }

    override suspend fun writeBackendAnnouncement(
        orgId: String,
        announcement: InstitutionBackendAnnouncement,
    ) {
        if (tryGitLive { gitlive.writeBackendAnnouncement(orgId, announcement) }) return
        markRestFallback("writeBackendAnnouncement")
        upsertDocument(
            orgId,
            "institutionSettings",
            InstitutionSettingsKeys.BACKEND_TYPE,
            mapOf(
                "value" to announcement.backendType.name,
                "lastModified" to announcement.migratedAt,
            ),
        )
        upsertDocument(
            orgId,
            "metadata",
            "config",
            mapOf(
                "backendType" to announcement.backendType.name,
                "migrationId" to announcement.migrationId,
                "migratedAt" to announcement.migratedAt,
                "migratedBy" to announcement.migratedBy,
                "firebaseOrgId" to announcement.firebaseOrgId,
                "sheetsSpreadsheetIdHint" to announcement.sheetsSpreadsheetIdHint,
                "firebaseProjectId" to announcement.firebaseProjectId,
                "firebaseApplicationId" to announcement.firebaseApplicationId,
                "firebaseWebClientId" to announcement.firebaseWebClientId,
            ),
        )
    }

    override suspend fun runPeopleCounterTransaction(
        orgId: String,
        venueName: String,
        count: Int,
        deviceId: String,
        writerAccountEmail: String,
        lastModified: Long,
    ) {
        withTimeoutOrNull(TRANSACTION_TIMEOUT_MS) {
            gitlive.runPeopleCounterTransaction(
                orgId, venueName, count, deviceId, writerAccountEmail, lastModified,
            )
        }
    }

    override suspend fun runLedgerTransaction(
        orgId: String,
        transfer: AccountTransfer,
        holderKey: String,
        newBalance: Double,
        buffer: Double,
    ): LedgerCommitResult =
        withTimeoutOrNull(TRANSACTION_TIMEOUT_MS) {
            gitlive.runLedgerTransaction(orgId, transfer, holderKey, newBalance, buffer)
        } ?: LedgerCommitResult.Unknown

    override fun guestToMap(guest: Guest) = gitlive.guestToMap(guest)
    override fun volunteerToMap(volunteer: Volunteer) = gitlive.volunteerToMap(volunteer)
    override fun jobToMap(job: Job) = gitlive.jobToMap(job)
    override fun jobTypeToMap(config: JobTypeConfig) = gitlive.jobTypeToMap(config)
    override fun venueToMap(venue: VenueEntity) = gitlive.venueToMap(venue)
    override fun salesItemToMap(item: SalesSheetItem) = gitlive.salesItemToMap(item)
    override fun transferToMap(transfer: AccountTransfer) = gitlive.transferToMap(transfer)
    override fun guestFormToMap(form: GuestForm) = gitlive.guestFormToMap(form)

    private suspend fun readMemberRoleInternal(
        orgId: String,
        uid: String,
        fromServer: Boolean,
    ): String? = withContext(Dispatchers.IO) {
        if (orgId.isBlank() || uid.isBlank()) return@withContext null
        val fromGitLive = withTimeoutOrNull(READ_TIMEOUT_MS) {
            if (fromServer) gitlive.readMemberRoleFromServer(orgId, uid)
            else gitlive.readMemberRole(orgId, uid)
        }
        if (fromGitLive != null) {
            noteServerReachable(true)
            return@withContext fromGitLive
        }
        val client = rest ?: return@withContext null
        markRestFallback("readMemberRole")
        runCatching {
            val data = client.getDocument(orgId, "members", uid) ?: return@withContext null
            noteServerReachable(true)
            data["role"]?.toString()?.trim()?.ifBlank { null }
        }.getOrNull()
    }

    private suspend fun tryGitLive(block: suspend () -> Unit): Boolean {
        if (transport == Transport.REST || !gitlive.isAvailable()) return false
        val completed = withTimeoutOrNull(WRITE_TIMEOUT_MS) {
            block()
            true
        }
        if (completed == true) return true
        markRestFallback("gitlive call timed out")
        return false
    }

    private fun markRestFallback(reason: String) {
        if (transport == Transport.GITLIVE) {
            println("Desktop Firestore: GitLive $reason failed — using REST for subsequent calls")
            transport = Transport.REST
        }
    }

    private fun logTransport(feature: String, mode: Transport) {
        println("Desktop Firestore: $feature via ${mode.name}")
    }

    companion object {
        private const val GITLIVE_LISTENER_PROBE_MS = 45_000L
        private const val PULL_TIMEOUT_MS = 120_000L
        private const val READ_TIMEOUT_MS = 30_000L
        private const val WRITE_TIMEOUT_MS = 45_000L
        private const val TRANSACTION_TIMEOUT_MS = 30_000L
    }
}
