package com.eventmanager.app.data.remote

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.GuestForm
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.ManualTemporaryGuestBatch
import com.eventmanager.app.data.models.SalesSheetItem
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.data.sync.SyncResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Router-only coordinator. No business logic — delegates to the active [RemoteBackend].
 */
class SyncCoordinator(
    private val settingsManager: SettingsManager,
    private val sheetsBackend: SheetsRemoteBackend,
    private val firebaseBackend: FirebaseRemoteBackend?,
) {
    private val _pendingBackendFollow = MutableStateFlow<InstitutionBackendAnnouncement?>(null)
    val pendingBackendFollow: StateFlow<InstitutionBackendAnnouncement?> = _pendingBackendFollow.asStateFlow()

    private val _crudSoftLocked = MutableStateFlow(false)
    val crudSoftLocked: StateFlow<Boolean> = _crudSoftLocked.asStateFlow()

    fun activeBackend(): RemoteBackend =
        when (settingsManager.getBackendType()) {
            BackendType.FIREBASE -> firebaseBackend ?: sheetsBackend
            BackendType.SHEETS -> sheetsBackend
        }

    fun startBackgroundRemoteSync(scope: CoroutineScope) {
        activeBackend().startBackgroundRemoteSync(scope)
    }

    fun stopBackgroundRemoteSync() {
        sheetsBackend.stopBackgroundRemoteSync()
        firebaseBackend?.stopBackgroundRemoteSync()
    }

    suspend fun performStartupSync(): SyncResult {
        val result = activeBackend().performStartupSync()
        refreshInstitutionBackendGuard()
        return result
    }

    suspend fun performManualSync(): SyncResult = activeBackend().performManualSync().also {
        refreshInstitutionBackendGuard()
    }

    suspend fun performPageChangeSync(from: String, to: String): SyncResult =
        activeBackend().performPageChangeSync(from, to)

    suspend fun prepareForAdminGate(): SyncResult =
        activeBackend().prepareForAdminGate().also { refreshInstitutionBackendGuard() }

    suspend fun bootstrapPosSessionSync(): SyncResult =
        activeBackend().bootstrapPosSessionSync().also { refreshInstitutionBackendGuard() }

    suspend fun repairRemoteStructureThenFullDownload(): SyncResult =
        activeBackend().repairRemoteStructureThenFullDownload()

    suspend fun afterGuestSaved(guest: Guest) = activeBackend().afterGuestSaved(guest)
    suspend fun afterGuestDeleted(guest: Guest) = activeBackend().afterGuestDeleted(guest)
    suspend fun afterTemporaryGuestBatch(batch: ManualTemporaryGuestBatch) =
        activeBackend().afterTemporaryGuestBatch(batch)
    suspend fun afterVolunteerSaved(volunteer: Volunteer) = activeBackend().afterVolunteerSaved(volunteer)
    suspend fun afterVolunteerDeleted(volunteer: Volunteer, deleteShifts: Boolean) =
        activeBackend().afterVolunteerDeleted(volunteer, deleteShifts)
    suspend fun afterJobSaved(job: Job) = activeBackend().afterJobSaved(job)
    suspend fun afterJobDeleted(job: Job) = activeBackend().afterJobDeleted(job)
    suspend fun afterBenefitEntryConsumed(job: Job) = activeBackend().afterBenefitEntryConsumed(job)
    suspend fun afterJobTypeSaved(config: JobTypeConfig) = activeBackend().afterJobTypeSaved(config)
    suspend fun afterJobTypeDeleted(config: JobTypeConfig) = activeBackend().afterJobTypeDeleted(config)
    suspend fun afterVenueSaved(venue: VenueEntity) = activeBackend().afterVenueSaved(venue)
    suspend fun afterVenueDeleted(venue: VenueEntity) = activeBackend().afterVenueDeleted(venue)
    suspend fun afterSalesItemSaved(item: SalesSheetItem) = activeBackend().afterSalesItemSaved(item)
    suspend fun afterSalesItemDeleted(item: SalesSheetItem) = activeBackend().afterSalesItemDeleted(item)
    suspend fun afterGuestFormSaved(form: GuestForm) = activeBackend().afterGuestFormSaved(form)

    /**
     * Create-time publish: the share link must not be shown until Firestore holds the OPEN doc.
     * Queued / silent failures are how the public page stays "closed".
     */
    suspend fun publishGuestForm(form: GuestForm) {
        val firebase = firebaseBackend
            ?: throw IllegalStateException("Guest forms require Firebase")
        if (settingsManager.getBackendType() != BackendType.FIREBASE) {
            throw IllegalStateException("Guest forms require Firebase")
        }
        firebase.publishGuestForm(form)
    }

    suspend fun afterGuestFormDeleted(form: GuestForm) = activeBackend().afterGuestFormDeleted(form)

    /** Force-refresh artist form documents so a public PENDING_REVIEW answer surfaces quickly. */
    suspend fun pullGuestForms(): SyncResult =
        firebaseBackend?.pullGuestForms()
            ?: SyncResult.Success("Guest forms require Firebase")

    suspend fun afterTransfersChanged() = activeBackend().afterTransfersChanged()
    suspend fun afterInstitutionSettingsChanged() = activeBackend().afterInstitutionSettingsChanged()
    suspend fun afterVolunteerGuestListRecalcNeeded() = activeBackend().afterVolunteerGuestListRecalcNeeded()
    suspend fun updatePeopleCounter(venue: VenueEntity, count: Int, orgId: String? = null) {
        when (val backend = activeBackend()) {
            is FirebaseRemoteBackend -> backend.updatePeopleCounter(venue, count, orgId ?: venue.firebaseOrgId)
            else -> backend.updatePeopleCounter(venue, count)
        }
    }
    suspend fun sendVenueAnnouncement(venueIds: List<Long>, title: String, message: String) =
        activeBackend().sendVenueAnnouncement(venueIds, title, message)

    suspend fun announceInstitutionBackendMigration(announcement: InstitutionBackendAnnouncement) {
        // Dual announce: write on both backends when available
        sheetsBackend.announceInstitutionBackendMigration(announcement)
        firebaseBackend?.announceInstitutionBackendMigration(announcement)
    }

    suspend fun commitTransfer(transfer: AccountTransfer, orgId: String? = null): FirebaseLedgerResult? {
        return when (settingsManager.getBackendType()) {
            BackendType.FIREBASE -> {
                val backend = firebaseBackend ?: return null
                backend.commitTransferTransactional(transfer, orgId ?: transfer.firebaseOrgId)
            }
            BackendType.SHEETS -> {
                afterTransfersChanged()
                null
            }
        }
    }

    suspend fun pullAllConfiguredFirebaseOrgs(): SyncResult =
        firebaseBackend?.pullAllConfiguredOrgs()
            ?: SyncResult.Error("Firebase backend unavailable")

    suspend fun <T> withFirebaseOrg(orgId: String, block: suspend () -> T): T {
        val backend = firebaseBackend ?: return block()
        return backend.withOrg(orgId, block)
    }

    suspend fun refreshInstitutionBackendGuard() {
        val announcement = readInstitutionBackendAnnouncementFromAnyBackend()
            ?: run {
                _pendingBackendFollow.value = null
                _crudSoftLocked.value = false
                return
            }
        val local = settingsManager.getBackendType()
        if (shouldPromptInstitutionBackendFollow(announcement, local)) {
            _pendingBackendFollow.value = announcement
            _crudSoftLocked.value = true
        } else {
            _pendingBackendFollow.value = null
            _crudSoftLocked.value = false
        }
    }

    fun clearPendingFollowAfterSuccess(migrationId: String) {
        settingsManager.setFollowedBackendMigrationId(migrationId)
        _pendingBackendFollow.value = null
        _crudSoftLocked.value = false
    }

    /** Prefer active backend; fall back to the other remote when dual-announced migration metadata exists. */
    private suspend fun readInstitutionBackendAnnouncementFromAnyBackend(): InstitutionBackendAnnouncement? {
        val primary = activeBackend().readInstitutionBackendAnnouncement()
        if (primary != null) return primary
        return when (settingsManager.getBackendType()) {
            BackendType.SHEETS -> firebaseBackend?.readInstitutionBackendAnnouncement()
            BackendType.FIREBASE -> sheetsBackend.readInstitutionBackendAnnouncement()
        }
    }

    suspend fun snapshotFirebaseSyncStatus(): FirebaseSyncStatus =
        firebaseBackend?.buildSyncStatus() ?: FirebaseSyncStatus.offline()

    /** True when Firestore snapshot listeners are delivering server-confirmed updates. */
    fun isFirebaseLiveListenerSyncActive(): Boolean =
        settingsManager.getBackendType() == BackendType.FIREBASE &&
            (firebaseBackend?.isLiveListenerSyncActive() == true)

    /**
     * On Sheets, `true` — that backend has no cache-vs-server ambiguity, real network failures
     * already surface as [SyncResult.Error]. On Firebase, `true` only once this session actually
     * got a server-confirmed Firestore response; a device that never reached the server (offline,
     * misconfigured, still warming up) must not be trusted to say the remote org is empty. See
     * [FirebaseRemoteBackend.hasServerConfirmedReachability].
     */
    fun isRemoteEmptinessTrustworthy(): Boolean = when (settingsManager.getBackendType()) {
        BackendType.SHEETS -> true
        BackendType.FIREBASE -> firebaseBackend?.hasServerConfirmedReachability() == true
    }

    suspend fun flushFirebasePendingWritesForOrg(orgId: String) {
        firebaseBackend?.flushPendingWritesForOrg(orgId)
    }

    suspend fun replicateFirebaseConfiguredOrgs() {
        firebaseBackend?.replicateConfiguredOrgsToAllOrgs()
    }

    suspend fun ensureFirebaseOrgReady(orgId: String) {
        firebaseBackend?.ensureOrgBootstrappedIfNeeded(orgId)
            ?: throw IllegalStateException("Firebase backend unavailable")
    }

    suspend fun provisionFirebaseOrg(orgId: String) {
        firebaseBackend?.provisionFirebaseOrg(orgId)
            ?: throw IllegalStateException("Firebase backend unavailable")
    }

    suspend fun repairFirebaseActiveOrgIfNeeded(): FirebaseOrgRepairResult =
        firebaseBackend?.repairActiveOrgIfNeeded()
            ?: FirebaseOrgRepairResult.NoOrgsConfigured
}
