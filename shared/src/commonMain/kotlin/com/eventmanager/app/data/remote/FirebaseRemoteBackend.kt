package com.eventmanager.app.data.remote

import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.GuestForm
import com.eventmanager.app.data.models.GuestFormStatus
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.ManualTemporaryGuestBatch
import com.eventmanager.app.data.models.encodeTemporaryAccessIds
import com.eventmanager.app.data.models.SalesSheetItem
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.repository.EventManagerRepository
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.data.sync.SyncResult
import com.eventmanager.app.data.security.crypto.SensitiveFieldCodec
import com.eventmanager.app.platform.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Firebase-mode remote backend. Uses Firestore when available; queues writes otherwise.
 */
class FirebaseRemoteBackend(
    private val platformContext: PlatformContext,
    private val repository: EventManagerRepository,
    private val settingsManager: SettingsManager,
    private val firestoreGateway: FirestoreGateway,
    private val ledgerService: FirebaseLedgerService,
    private val pendingWrites: PendingRemoteWriteQueue = PendingRemoteWriteQueue(),
) : RemoteBackend {

    var onInstitutionBackendAnnouncementChanged: (suspend () -> Unit)? = null
    var onRemoteRepositoryChanged: (() -> Unit)? = null
    var onSyncStatusChanged: (() -> Unit)? = null

    private var lastActivityAt = 0L
    private var listenersActive = false

    private var listenerJob: CoroutineJob? = null

    /** Listeners are up and the last snapshot was confirmed by the Firestore server. */
    fun isLiveListenerSyncActive(): Boolean {
        if (!listenersActive) return false
        if (configuredOrgIdsForSync().isEmpty()) return false
        if (!firestoreGateway.isAvailable()) return false
        return firestoreGateway.isServerReachable()
    }

    /**
     * True only once this session has received at least one Firestore response the server itself
     * confirmed — never a cache-only read, and never true while the SDK failed to initialize.
     *
     * [pullAll] returns [SyncResult.Success] even when the SDK never initialized ("local Room
     * remains source of truth") so normal sync callers don't treat a misconfigured device as a
     * hard error. Security gates cannot share that leniency: `evaluateAdminSetupGate` must not
     * read an empty/stale local Room table as "the remote org has no admin" when we never actually
     * asked the server. Doing so let anyone standing at an offline device open the passwordless
     * first-admin wizard and grant themselves — or any guest — permanent admin rights.
     */
    fun hasServerConfirmedReachability(): Boolean =
        firestoreGateway.isAvailable() && firestoreGateway.isServerReachable()

    private val posBootstrapCollections = listOf("salesItems", "transfers")

    override val backendType: BackendType = BackendType.FIREBASE

    suspend fun repairActiveOrgIfNeeded(): FirebaseOrgRepairResult =
        FirebaseOrgBootstrap.repairActiveOrgIfNeeded(firestoreGateway, settingsManager)

    suspend fun ensureOrgBootstrappedIfNeeded(orgId: String) {
        FirebaseOrgBootstrap.ensureOrgBootstrappedIfNeeded(
            firestoreGateway,
            settingsManager,
            orgId,
        )
    }

    suspend fun provisionFirebaseOrg(orgId: String) {
        FirebaseOrgBootstrap.provisionOrgInFirestore(firestoreGateway, settingsManager, orgId)
    }

    private var scopedWriteOrgId: String? = null

    private fun configuredOrgIdsForSync(): List<String> {
        val configured = settingsManager.getFirebaseConfiguredOrgs()
            .map { it.orgId.trim() }
            .filter { it.isNotBlank() }
        return when (settingsManager.getFirebaseOrgViewMode()) {
            FirebaseOrgViewMode.ALL -> configured
            else -> listOf(settingsManager.getFirebaseOrgId().trim())
                .filter { it.isNotBlank() && !isFirebaseOrgAllSentinel(it) }
        }
    }

    private fun resolveWriteOrgId(entityOrgId: String? = null): String {
        entityOrgId?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        scopedWriteOrgId?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val active = settingsManager.getFirebaseOrgId().trim()
        if (active.isNotBlank() && !isFirebaseOrgAllSentinel(active)) return active
        return settingsManager.getFirebaseLastSingleOrgId().trim()
    }

    suspend fun <T> withOrg(orgId: String, block: suspend () -> T): T {
        val previous = scopedWriteOrgId
        scopedWriteOrgId = orgId.trim().takeIf { it.isNotBlank() }
        return try {
            block()
        } finally {
            scopedWriteOrgId = previous
        }
    }

    override fun startBackgroundRemoteSync(scope: CoroutineScope) {
        stopBackgroundRemoteSync()
        val orgIds = configuredOrgIdsForSync()
        if (orgIds.isEmpty()) return
        listenerJob = scope.launch {
            firestoreGateway.setServerReachabilityListener { notifySyncStatusChanged() }
            withContext(Dispatchers.IO) { flushQueue() }
            val available = firestoreGateway.isAvailable()
            if (!available) {
                listenersActive = false
                notifySyncStatusChanged()
                return@launch
            }

            if (FirestoreRealtimeCapability.preferSnapshotListeners()) {
                listenersActive = true
                notifySyncStatusChanged()
                firestoreGateway.startOrgListeners(orgIds) { change ->
                    applyRemoteChange(change)
                }
                val degradedFallbackMs = FirestoreRealtimeCapability.pullFallbackIntervalMs() ?: 30_000L
                while (isActive) {
                    val intervalMs = when {
                        isLiveListenerSyncActive() ->
                            settingsManager.getSyncInterval().coerceAtLeast(1) * 60_000L
                        FirestoreRealtimeCapability.alsoRunPullFallback() -> degradedFallbackMs
                        else -> null
                    }
                    if (intervalMs == null) {
                        kotlinx.coroutines.delay(5_000L)
                        continue
                    }
                    kotlinx.coroutines.delay(intervalMs)
                    if (!isActive) break
                    withContext(Dispatchers.IO) {
                        flushQueue()
                        pullAll()
                    }
                }
            } else {
                listenersActive = false
                notifySyncStatusChanged()
                val minInterval = FirestoreRealtimeCapability.periodicPullIntervalMs()
                if (minInterval != null && minInterval > 0) {
                    val settingsInterval = settingsManager.getSyncInterval().coerceAtLeast(1) * 60_000L
                    val interval = maxOf(minInterval, settingsInterval)
                    // Startup / wizard already run performFullSyncAwait — don't hammer REST immediately.
                    kotlinx.coroutines.delay(interval)
                    while (isActive) {
                        withContext(Dispatchers.IO) {
                            flushQueue()
                            pullAll()
                        }
                        kotlinx.coroutines.delay(interval)
                    }
                }
            }
        }
    }

    override fun stopBackgroundRemoteSync() {
        listenerJob?.cancel()
        listenerJob = null
        listenersActive = false
        firestoreGateway.setServerReachabilityListener(null)
        firestoreGateway.stopOrgListeners()
        notifySyncStatusChanged()
    }

    suspend fun buildSyncStatus(): FirebaseSyncStatus {
        val orgIds = configuredOrgIdsForSync()
        val available = firestoreGateway.isAvailable()
        val orgConfigured = orgIds.isNotEmpty()
        val pending = pendingWrites.count()
        val failedPending = pendingWrites.countWithFailedAttempts()
        val mode = firebaseSyncTransport(
            orgConfigured = orgConfigured,
            sdkAvailable = available,
            listenersActive = listenersActive,
            serverReachable = firestoreGateway.isServerReachable(),
            pullJobActive = listenerJob?.isActive == true,
        )
        return FirebaseSyncStatus(
            mode = mode,
            lastActivityAt = lastActivityAt,
            pendingWriteCount = pending,
            failedPendingWriteCount = failedPending,
            firestoreAvailable = available,
            orgConfigured = orgConfigured,
        )
    }

    override suspend fun performStartupSync(): SyncResult {
        flushQueue()
        return pullAll()
    }

    override suspend fun performManualSync(): SyncResult {
        flushQueue()
        // Firebase is the remote source of truth after migration/join. Never bulk-push the full
        // local Room snapshot here — that resurrects stale rows (e.g. Sheets-era data on a
        // device that just followed migration). Bulk push is reserved for migrateSheetsToFirebase().
        return pullAll()
    }

    suspend fun clearPendingWrites() {
        pendingWrites.clearAll()
    }

    override suspend fun performPageChangeSync(from: String, to: String): SyncResult {
        flushQueue()
        return if (isLiveListenerSyncActive()) {
            SyncResult.Success("Firebase live listeners")
        } else if (FirestoreRealtimeCapability.preferSnapshotListeners()) {
            SyncResult.Success("Firebase listeners active")
        } else {
            pullAll()
        }
    }

    override suspend fun prepareForAdminGate(): SyncResult = performStartupSync()

    override suspend fun bootstrapPosSessionSync(): SyncResult {
        flushQueue()
        return pullAll(posBootstrapCollections)
    }

    override suspend fun repairRemoteStructureThenFullDownload(): SyncResult = performStartupSync()

    override suspend fun readInstitutionBackendAnnouncement(): InstitutionBackendAnnouncement? {
        val orgId = settingsManager.getFirebaseOrgId()
        if (orgId.isBlank() || !firestoreGateway.isAvailable()) {
            return settingsManager.getLocalInstitutionBackendAnnouncement()
        }
        return firestoreGateway.readBackendAnnouncement(orgId)
            ?: settingsManager.getLocalInstitutionBackendAnnouncement()
    }

    override suspend fun announceInstitutionBackendMigration(announcement: InstitutionBackendAnnouncement) {
        settingsManager.applyLocalInstitutionBackendAnnouncement(announcement)
        val orgId = settingsManager.getFirebaseOrgId().ifBlank {
            announcement.firebaseOrgId.orEmpty()
        }
        if (orgId.isNotBlank()) {
            if (firestoreGateway.isAvailable()) {
                firestoreGateway.writeBackendAnnouncement(orgId, announcement)
            } else {
                pendingWrites.enqueueUpsert(
                    "metadata",
                    "config",
                    encodeMap(
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
                        )
                    ),
                )
            }
        }
    }

    override suspend fun afterGuestSaved(guest: Guest) {
        upsert("guests", guest.nanoId, firestoreGateway.guestToMap(guest), guest.firebaseOrgId)
    }

    override suspend fun afterGuestDeleted(guest: Guest) {
        delete("guests", guest.nanoId, guest.firebaseOrgId)
    }

    override suspend fun afterTemporaryGuestBatch(batch: ManualTemporaryGuestBatch) {
        for (entry in batch.guests) {
            val name = entry.name.trim()
            if (name.isEmpty()) continue
            val guest = Guest(
                name = name,
                invitations = 1,
                venueName = "BOTH",
                notes = batch.comments,
                isTemporaryGuest = true,
                temporaryArtistName = batch.artistName,
                temporaryEventDate = batch.eventDateMillis,
                temporaryContactPhone = batch.emergencyContactPhone,
                temporaryVenueName = batch.venueName,
                temporaryContactEmail = batch.contactEmail,
                temporaryAccessIds = encodeTemporaryAccessIds(entry.accessIds),
                barDiscountPercent = batch.barDiscountPercent.coerceIn(0, 100),
                lastModified = System.currentTimeMillis(),
                firebaseOrgId = resolveWriteOrgId(),
            )
            val saved = repository.insertGuest(guest)
            afterGuestSaved(saved)
        }
    }

    override suspend fun afterVolunteerGuestListRecalcNeeded() {
        val benefitGuests = repository.getVolunteerBenefitGuests()
        benefitGuests.forEach { afterGuestSaved(it) }
    }

    override suspend fun afterVolunteerSaved(volunteer: Volunteer) {
        upsert("volunteers", volunteer.id, firestoreGateway.volunteerToMap(volunteer), volunteer.firebaseOrgId)
    }

    override suspend fun afterVolunteerDeleted(volunteer: Volunteer, deleteShifts: Boolean) {
        delete("volunteers", volunteer.id, volunteer.firebaseOrgId)
    }

    override suspend fun afterJobSaved(job: Job) {
        val id = job.jobNanoId.ifBlank { job.compositeBusinessKey() }
        upsert("jobs", id, firestoreGateway.jobToMap(job), job.firebaseOrgId)
    }

    override suspend fun afterJobDeleted(job: Job) {
        val id = job.jobNanoId.ifBlank { job.compositeBusinessKey() }
        delete("jobs", id, job.firebaseOrgId)
    }

    override suspend fun afterBenefitEntryConsumed(job: Job) = afterJobSaved(job)

    override suspend fun afterJobTypeSaved(config: JobTypeConfig) {
        upsert("jobTypeConfigs", config.name, firestoreGateway.jobTypeToMap(config), config.firebaseOrgId)
    }

    override suspend fun afterJobTypeDeleted(config: JobTypeConfig) {
        delete("jobTypeConfigs", config.name, config.firebaseOrgId)
    }

    override suspend fun afterGuestFormSaved(form: GuestForm) {
        upsert("guestForms", form.formId, firestoreGateway.guestFormToMap(form), form.firebaseOrgId)
    }

    override suspend fun afterGuestFormDeleted(form: GuestForm) {
        delete("guestForms", form.formId, form.firebaseOrgId)
    }

    override suspend fun afterVenueSaved(venue: VenueEntity) {
        upsert("venues", venue.name, firestoreGateway.venueToMap(venue), venue.firebaseOrgId)
    }

    override suspend fun afterVenueDeleted(venue: VenueEntity) {
        delete("venues", venue.name, venue.firebaseOrgId)
    }

    override suspend fun afterSalesItemSaved(item: SalesSheetItem) {
        upsert("salesItems", item.name, firestoreGateway.salesItemToMap(item), item.firebaseOrgId)
    }

    override suspend fun afterSalesItemDeleted(item: SalesSheetItem) {
        delete("salesItems", item.name, item.firebaseOrgId)
    }

    override suspend fun afterTransfersChanged() {
        flushQueue()
        repository.getAllAccountTransfersOnce()
            .filter { it.syncState == com.eventmanager.app.data.models.AccountTransferSyncState.PENDING }
            .forEach { transfer ->
                ledgerService.commitTransfer(transfer)
            }
    }

    suspend fun seedAccountBalancesFromLocalLedger() {
        val orgId = resolveWriteOrgId()
        if (orgId.isBlank() || !firestoreGateway.isAvailable()) return
        val transfers = repository.getAllAccountTransfersOnce()
        val balances = com.eventmanager.app.data.utils.AccountBalanceService.computeAllBalances(transfers)
        balances.forEach { (holderKey, balance) ->
            firestoreGateway.upsertDocument(
                orgId,
                "accounts",
                holderKey.storageKey(),
                mapOf(
                    "balance" to balance,
                    "updatedAt" to System.currentTimeMillis(),
                ),
            )
        }
    }

    override suspend fun afterInstitutionSettingsChanged() {
        val orgId = resolveWriteOrgId()
        if (orgId.isBlank()) return
        if (!FirebaseOrgAdminAccess.currentUserIsOrgAdmin(platformContext, settingsManager)) {
            println(
                "Skipping institutionSettings push: signed-in user is not Firebase org admin " +
                    "(org=$orgId)",
            )
            return
        }
        val rows = settingsManager.getInstitutionSettingRowsPendingRemotePush()
        if (rows.isEmpty()) return
        rows.forEach { row ->
            upsert(
                "institutionSettings",
                row.key,
                mapOf("value" to row.value, "lastModified" to row.lastModified),
                orgId,
            )
            settingsManager.markInstitutionSettingRowPushed(row)
        }
    }

    override suspend fun updatePeopleCounter(venue: VenueEntity, count: Int) {
        updatePeopleCounter(venue, count, venue.firebaseOrgId)
    }

    suspend fun updatePeopleCounter(venue: VenueEntity, count: Int, orgId: String) {
        val targetOrg = resolveWriteOrgId(orgId)
        if (targetOrg.isBlank()) return
        val writerId = venue.peopleCounterWriterDeviceId
        if (firestoreGateway.isAvailable()) {
            firestoreGateway.runPeopleCounterTransaction(
                targetOrg,
                venue.name,
                count,
                writerId,
                venue.peopleCounterWriterAccountEmail,
                venue.peopleCounterLastModified,
            )
            return
        }
        upsert(
            "venues",
            venue.name,
            firestoreGateway.venueToMap(venue).toMutableMap().apply {
                put("peopleCounterCount", count)
                put("peopleCounterWriterDeviceId", writerId)
                put("peopleCounterWriterAccountEmail", venue.peopleCounterWriterAccountEmail)
                put(
                    "peopleCounterLastModified",
                    if (venue.peopleCounterLastModified > 0L) {
                        venue.peopleCounterLastModified
                    } else {
                        System.currentTimeMillis()
                    },
                )
            },
            targetOrg,
        )
    }

    override suspend fun sendVenueAnnouncement(venueIds: List<Long>, title: String, message: String) {
        val sentAt = System.currentTimeMillis()
        val deviceId = settingsManager.getOrCreatePersistentDeviceId()
        val venues = repository.getAllVenues().first()
        venues.filter { it.id in venueIds }.forEach { venue ->
            upsert(
                "venues",
                venue.name,
                firestoreGateway.venueToMap(venue).toMutableMap().apply {
                    put("announcementTitle", title)
                    put("announcementMessage", message)
                    put("announcementSentAt", sentAt)
                    put("announcementSenderDeviceId", deviceId)
                    put("lastModified", sentAt)
                },
                venue.firebaseOrgId,
            )
        }
    }

    suspend fun commitTransferTransactional(
        transfer: AccountTransfer,
        orgId: String? = null,
    ): FirebaseLedgerResult =
        ledgerService.commitTransfer(transfer, orgId ?: transfer.firebaseOrgId)

    suspend fun pushAllLocalEntities(): SyncResult {
        val orgId = settingsManager.getFirebaseOrgId()
        if (orgId.isBlank()) return SyncResult.Error("Firebase org ID not configured")
        return try {
            repository.getAllGuests().first().forEach { afterGuestSaved(it) }
            repository.getAllVolunteers().first().forEach { afterVolunteerSaved(it) }
            repository.getAllJobs().first().forEach { afterJobSaved(it) }
            repository.getAllJobTypeConfigs().first().forEach { afterJobTypeSaved(it) }
            repository.getAllVenues().first().forEach { afterVenueSaved(it) }
            repository.getAllSalesSheetItems().first().forEach { afterSalesItemSaved(it) }
            // Never bulk-push OPEN forms: a stale local OPEN would overwrite a public
            // PENDING_REVIEW answer already sitting in Firestore.
            repository.getAllGuestForms().first()
                .filter { it.status != GuestFormStatus.OPEN.name }
                .forEach { afterGuestFormSaved(it) }
            repository.getAllAccountTransfersOnce()
                .filter { it.syncState != com.eventmanager.app.data.models.AccountTransferSyncState.REJECTED }
                .forEach { transfer ->
                    upsert("transfers", transfer.sourceReference, firestoreGateway.transferToMap(transfer))
                }
            // institutionSettings must land before the balances: the accounts rule validates
            // every balance against purchase_credit_buffer, and settings writes only go through
            // the pending queue, so they would still be missing when the balances are written.
            settingsManager.markAllInstitutionSettingsPendingRemotePush()
            afterInstitutionSettingsChanged()
            flushQueue()
            seedAccountBalancesFromLocalLedger()
            flushQueue()
            SyncResult.Success("Pushed local entities to Firebase")
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "Firebase push failed")
        }
    }

    suspend fun pullGuestForms(): SyncResult = pullAll(listOf("guestForms"))

    private suspend fun pullAll(collections: Collection<String>? = null): SyncResult {
        if (!firestoreGateway.isAvailable()) {
            return SyncResult.Success("Firebase SDK not initialized — local Room remains source of truth")
        }
        return if (settingsManager.getFirebaseOrgViewMode() == FirebaseOrgViewMode.ALL) {
            pullAllConfiguredOrgs(collections)
        } else {
            val orgId = resolveWriteOrgId()
            if (orgId.isBlank()) return SyncResult.Error("Firebase org ID not configured")
            try {
                firestoreGateway.pullAllIntoRepository(orgId, repository, collections)
                recordRemoteActivity()
                notifyRemoteRepositoryChangedIfListenersInactive()
                SyncResult.Success("Firebase pull completed")
            } catch (e: Exception) {
                SyncResult.Error(e.message ?: "Firebase pull failed")
            }
        }
    }

    suspend fun pullAllConfiguredOrgs(collections: Collection<String>? = null): SyncResult {
        val orgIds = settingsManager.getFirebaseConfiguredOrgs()
            .map { it.orgId.trim() }
            .filter { it.isNotBlank() }
        if (orgIds.isEmpty()) return SyncResult.Error("No configured Firebase orgs")
        if (!firestoreGateway.isAvailable()) {
            return SyncResult.Success("Firebase SDK not initialized — local Room remains source of truth")
        }
        val errors = mutableListOf<String>()
        var successCount = 0
        orgIds.forEach { orgId ->
            runCatching {
                ensureOrgBootstrappedIfNeeded(orgId)
                firestoreGateway.pullAllIntoRepository(orgId, repository, collections)
            }.onSuccess { successCount++ }
                .onFailure { errors += "$orgId: ${it.message ?: "pull failed"}" }
        }
        if (successCount == 0) {
            return SyncResult.Error(errors.joinToString("; "))
        }
        if (collections == null) {
            repository.deleteAllDataNotInOrgs(orgIds)
        }
        recordRemoteActivity()
        notifyRemoteRepositoryChangedIfListenersInactive()
        return if (errors.isEmpty()) {
            SyncResult.Success("Firebase pull completed for ${successCount} org(s)")
        } else {
            SyncResult.Success(
                "Loaded $successCount org(s). Some orgs could not be synced: ${errors.joinToString("; ")}",
            )
        }
    }

    private suspend fun upsert(
        collection: String,
        docId: String,
        data: Map<String, Any?>,
        orgId: String? = null,
    ) {
        val targetOrg = resolveWriteOrgId(orgId)
        if (targetOrg.isBlank()) return
        val stamped = data.toMutableMap().apply {
            // Public guest-form answers must never look like a write from this device: the
            // creator's sourceDeviceId used to make NoctuList drop PENDING_REVIEW as an echo.
            if (collection != "guestForms") {
                put("sourceDeviceId", settingsManager.getOrCreatePersistentDeviceId())
            }
        }
        if (firestoreGateway.isAvailable()) {
            try {
                firestoreGateway.upsertDocument(targetOrg, collection, docId, stamped)
                return
            } catch (e: Exception) {
                if (FirestoreErrors.isPermissionDenied(e)) {
                    pendingWrites.enqueueUpsert(collection, docId, encodeMap(stamped), targetOrg)
                    notifySyncStatusChanged()
                    return
                }
            }
        }
        pendingWrites.enqueueUpsert(collection, docId, encodeMap(stamped), targetOrg)
    }

    private suspend fun delete(collection: String, docId: String, orgId: String? = null) {
        val targetOrg = resolveWriteOrgId(orgId)
        if (targetOrg.isBlank()) return
        if (firestoreGateway.isAvailable()) {
            try {
                firestoreGateway.deleteDocument(targetOrg, collection, docId)
                return
            } catch (e: Exception) {
                if (FirestoreErrors.isPermissionDenied(e)) {
                    pendingWrites.enqueueDelete(collection, docId, targetOrg)
                    notifySyncStatusChanged()
                    return
                }
            }
        }
        pendingWrites.enqueueDelete(collection, docId, targetOrg)
    }

    private suspend fun flushQueue() {
        configuredOrgIdsForSync().forEach { orgId ->
            flushPendingWritesForOrg(orgId)
        }
    }

    suspend fun flushPendingWritesForOrg(orgId: String) {
        val trimmed = orgId.trim()
        if (trimmed.isBlank() || !firestoreGateway.isAvailable()) {
            firestoreGateway.flushPendingWrites()
            return
        }
        val pending = pendingWrites.drainForOrg(trimmed)
        for (row in pending) {
            if (row.attempts >= 12) continue
            try {
                when (row.operation) {
                    "DELETE" -> firestoreGateway.deleteDocument(trimmed, row.collection, row.documentId)
                    else -> {
                        val data = decodeMap(row.payloadJson, trimmed)
                        if (data.isEmpty()) {
                            pendingWrites.recordFailedAttempt(row.id)
                            notifySyncStatusChanged()
                            continue
                        }
                        firestoreGateway.upsertDocument(trimmed, row.collection, row.documentId, data)
                    }
                }
                pendingWrites.acknowledge(row.id)
            } catch (e: Exception) {
                pendingWrites.recordFailedAttempt(row.id)
                notifySyncStatusChanged()
            }
        }
        pendingWrites.dropExceededMaxAttempts()
        firestoreGateway.flushPendingWrites()
    }

    /** Replicate multi-org list to every org where the user is Firebase org admin. */
    suspend fun replicateConfiguredOrgsToAllOrgs() {
        if (!firestoreGateway.isAvailable()) return
        val orgs = settingsManager.getFirebaseConfiguredOrgs()
        if (orgs.isEmpty()) return
        val uid = FirebaseAuthBridge.currentUserId()?.trim().orEmpty()
        if (uid.isBlank()) return

        val adminOrgs = mutableListOf<com.eventmanager.app.data.remote.FirebaseConfiguredOrg>()
        for (entry in orgs) {
            val id = entry.orgId.trim()
            if (!FirebaseOrgBootstrap.isValidOrgId(id)) continue
            val role = firestoreGateway.readMemberRoleFromServer(id, uid)
            if (com.eventmanager.app.data.security.firebaseRoleIsOrgAdmin(role)) {
                adminOrgs += entry
            }
        }
        if (adminOrgs.isEmpty()) {
            println("Skipping FIREBASE_CONFIGURED_ORGS replicate: not org admin on any configured org")
            return
        }

        val payload = mapOf(
            "value" to FirebaseConfiguredOrgCodec.encode(orgs),
            "lastModified" to System.currentTimeMillis(),
        )
        adminOrgs.forEach { entry ->
            runCatching {
                firestoreGateway.upsertDocument(
                    entry.orgId.trim(),
                    "institutionSettings",
                    com.eventmanager.app.data.sync.InstitutionSettingsKeys.FIREBASE_CONFIGURED_ORGS,
                    payload,
                )
            }
        }
    }

    private fun encodeMap(data: Map<String, Any?>): String =
        FirestoreJsonCodec.toEnvelope(data).json

    private fun decodeMap(payloadJson: String, orgId: String): Map<String, Any?> {
        if (payloadJson.isBlank() || payloadJson == "{}") return emptyMap()
        val plain = SensitiveFieldCodec.decryptPayloadJson(payloadJson, orgId)
        return FirestoreJsonCodec.fromEnvelope(FirestoreJsonEnvelope(plain))
    }

    private suspend fun applyRemoteChange(change: FirestoreRemoteChange) {
        val allowedOrgs = configuredOrgIdsForSync().toSet()
        if (change.orgId.isNotBlank() && allowedOrgs.isNotEmpty() && change.orgId !in allowedOrgs) {
            return
        }
        val echoDevice = change.data?.get("sourceDeviceId") as? String
        // guestForms answers are written by the public page without clearing the creator's
        // sourceDeviceId on older rules/sites. Never treat this collection as a self-echo —
        // otherwise the device that created the form silently drops PENDING_REVIEW forever.
        val isGuestFormChange = change.collection == "guestForms"
        if (!isGuestFormChange &&
            !echoDevice.isNullOrBlank() &&
            echoDevice == settingsManager.getOrCreatePersistentDeviceId()
        ) {
            return
        }
        if (change.collection == "institutionSettings" && change.data != null && !change.deleted) {
            val value = change.data["value"]?.toString().orEmpty()
            val lm = (change.data["lastModified"] as? Number)?.toLong()
                ?: (change.data["lastModified"] as? String)?.toLongOrNull()
                ?: System.currentTimeMillis()
            settingsManager.applyInstitutionSettingFromRemote(change.documentId, value, lm)
            if (change.documentId == com.eventmanager.app.data.sync.InstitutionSettingsKeys.BACKEND_TYPE ||
                change.documentId == com.eventmanager.app.data.sync.InstitutionSettingsKeys.BACKEND_MIGRATION_ID
            ) {
                onInstitutionBackendAnnouncementChanged?.invoke()
            }
            recordRemoteActivity()
            onRemoteRepositoryChanged?.invoke()
            return
        }
        if (change.collection == "metadata" && change.documentId == "config" && change.data != null && !change.deleted) {
            onInstitutionBackendAnnouncementChanged?.invoke()
            recordRemoteActivity()
            onRemoteRepositoryChanged?.invoke()
            return
        }
        firestoreGateway.applyChangeToRepository(change, repository)
        recordRemoteActivity()
        onRemoteRepositoryChanged?.invoke()
    }

    private fun notifyRemoteRepositoryChangedIfListenersInactive() {
        if (!listenersActive) {
            onRemoteRepositoryChanged?.invoke()
        }
    }

    private fun recordRemoteActivity() {
        lastActivityAt = System.currentTimeMillis()
        notifySyncStatusChanged()
    }

    private fun notifySyncStatusChanged() {
        onSyncStatusChanged?.invoke()
    }
}

private fun Job.compositeBusinessKey(): String =
    "$volunteerId|$jobTypeName|$date|$venueName|$shiftTime"
