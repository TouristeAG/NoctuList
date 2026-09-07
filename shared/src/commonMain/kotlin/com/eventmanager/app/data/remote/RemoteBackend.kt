package com.eventmanager.app.data.remote

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.GuestForm
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.ManualTemporaryGuestBatch
import com.eventmanager.app.data.models.SalesSheetItem
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.sync.SyncResult
import kotlinx.coroutines.CoroutineScope

/**
 * Intent-level remote backend. SheetsRemoteBackend copies ViewModel orchestration verbatim;
 * FirebaseRemoteBackend uses Firestore listeners + transactional ledger writes.
 */
interface RemoteBackend {
    val backendType: BackendType

    fun startBackgroundRemoteSync(scope: CoroutineScope)
    fun stopBackgroundRemoteSync()

    suspend fun performStartupSync(): SyncResult
    suspend fun performManualSync(): SyncResult
    suspend fun performPageChangeSync(from: String, to: String): SyncResult
    suspend fun prepareForAdminGate(): SyncResult
    suspend fun bootstrapPosSessionSync(): SyncResult
    suspend fun repairRemoteStructureThenFullDownload(): SyncResult

    suspend fun readInstitutionBackendAnnouncement(): InstitutionBackendAnnouncement?
    suspend fun announceInstitutionBackendMigration(announcement: InstitutionBackendAnnouncement)

    suspend fun afterGuestSaved(guest: Guest)
    suspend fun afterGuestDeleted(guest: Guest)
    suspend fun afterTemporaryGuestBatch(batch: ManualTemporaryGuestBatch)
    suspend fun afterVolunteerSaved(volunteer: Volunteer)
    suspend fun afterVolunteerDeleted(volunteer: Volunteer, deleteShifts: Boolean)
    suspend fun afterJobSaved(job: Job)
    suspend fun afterJobDeleted(job: Job)
    suspend fun afterBenefitEntryConsumed(job: Job)
    suspend fun afterJobTypeSaved(config: JobTypeConfig)
    suspend fun afterJobTypeDeleted(config: JobTypeConfig)
    suspend fun afterVenueSaved(venue: VenueEntity)
    suspend fun afterVenueDeleted(venue: VenueEntity)
    suspend fun afterSalesItemSaved(item: SalesSheetItem)
    suspend fun afterSalesItemDeleted(item: SalesSheetItem)
    suspend fun afterGuestFormSaved(form: GuestForm)
    suspend fun afterGuestFormDeleted(form: GuestForm)
    suspend fun afterTransfersChanged()
    suspend fun afterInstitutionSettingsChanged()
    suspend fun afterVolunteerGuestListRecalcNeeded()

    suspend fun updatePeopleCounter(venue: VenueEntity, count: Int)
    suspend fun sendVenueAnnouncement(venueIds: List<Long>, title: String, message: String)
}
