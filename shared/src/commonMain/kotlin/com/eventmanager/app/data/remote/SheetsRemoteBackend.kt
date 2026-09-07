package com.eventmanager.app.data.remote

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.GuestForm
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.ManualTemporaryGuestBatch
import com.eventmanager.app.data.models.SalesSheetItem
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.repository.EventManagerRepository
import com.eventmanager.app.data.sync.DeletionTracker
import com.eventmanager.app.data.sync.GoogleSheetsService
import com.eventmanager.app.data.sync.InstitutionSettingsKeys
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.data.sync.SyncManager
import com.eventmanager.app.data.sync.SyncResult
import com.eventmanager.app.data.sync.TwoWaySyncService
import com.eventmanager.app.platform.PlatformContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Pure delegation to existing Sheets stack. Behavior must match pre-Firebase ViewModel sequences.
 */
class SheetsRemoteBackend(
    private val platformContext: PlatformContext,
    private val repository: EventManagerRepository,
    private val googleSheetsService: GoogleSheetsService,
    private val twoWaySyncService: TwoWaySyncService,
    private val syncManager: SyncManager,
    private val deletionTracker: DeletionTracker?,
    private val settingsManager: SettingsManager = SettingsManager(platformContext),
    private val onVolunteerGuestListRecalc: (suspend () -> Unit)? = null,
    private val onBackgroundDifferentialSync: (suspend () -> Unit)? = null,
    private val onPeopleCounterUpdate: (suspend (VenueEntity, Int) -> Unit)? = null,
    private val onTemporaryGuestsRefresh: (suspend () -> Unit)? = null,
) : RemoteBackend {

    override val backendType: BackendType = BackendType.SHEETS

    /** Invoked after each background differential so peers can detect institution migrations. */
    var onAfterBackgroundSync: (suspend () -> Unit)? = null

    private var backgroundJob: CoroutineJob? = null

    override fun startBackgroundRemoteSync(scope: CoroutineScope) {
        stopBackgroundRemoteSync()
        if (!settingsManager.isSyncEnabled() || !settingsManager.isAutoSyncEnabled()) return
        backgroundJob = scope.launch {
            while (isActive) {
                val intervalMs = settingsManager.getSyncInterval() * 60_000L
                delay(intervalMs.coerceAtLeast(60_000L))
                try {
                    onBackgroundDifferentialSync?.invoke()
                        ?: syncManager.performDifferentialSync()
                    onAfterBackgroundSync?.invoke()
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun stopBackgroundRemoteSync() {
        backgroundJob?.cancel()
        backgroundJob = null
    }

    override suspend fun performStartupSync(): SyncResult {
        googleSheetsService.initializeSheetsService()
        return syncManager.performFullSync()
    }

    override suspend fun performManualSync(): SyncResult {
        // Characterization sequence: initialize → upload (merge-safe) → pull
        googleSheetsService.initializeSheetsService()
        twoWaySyncService.backupToGoogleSheets()
        return syncManager.performFullSync()
    }

    override suspend fun performPageChangeSync(from: String, to: String): SyncResult =
        syncManager.performSmartPageChangeSync(from, to)

    override suspend fun prepareForAdminGate(): SyncResult =
        syncManager.repairSheetStructureThenFullDownload()

    override suspend fun bootstrapPosSessionSync(): SyncResult {
        return try {
            syncManager.performSalesSheetItemDifferentialSync()
            syncManager.performTransferDifferentialSync()
            SyncResult.Success("POS session synced")
        } catch (e: Exception) {
            SyncResult.Error(e.message ?: "POS bootstrap failed")
        }
    }

    override suspend fun repairRemoteStructureThenFullDownload(): SyncResult =
        syncManager.repairSheetStructureThenFullDownload()

    override suspend fun readInstitutionBackendAnnouncement(): InstitutionBackendAnnouncement? {
        return try {
            googleSheetsService.initializeSheetsService()
            val rows = googleSheetsService.syncInstitutionSettingsFromSheets()
            parseAnnouncement(rows.associate { it.key to it.value })
        } catch (_: Exception) {
            parseAnnouncement(
                settingsManager.getInstitutionSettingRows().associate { it.key to it.value }
            )
        }
    }

    override suspend fun announceInstitutionBackendMigration(announcement: InstitutionBackendAnnouncement) {
        val now = maxOf(announcement.migratedAt, System.currentTimeMillis())
        fun write(key: String, value: String) {
            settingsManager.applyInstitutionSettingFromRemote(key, value, now)
        }
        write(InstitutionSettingsKeys.BACKEND_TYPE, announcement.backendType.name)
        write(InstitutionSettingsKeys.BACKEND_MIGRATION_ID, announcement.migrationId)
        write(InstitutionSettingsKeys.BACKEND_MIGRATION_AT, now.toString())
        write(InstitutionSettingsKeys.BACKEND_MIGRATION_BY, announcement.migratedBy)
        announcement.firebaseOrgId?.let { write(InstitutionSettingsKeys.FIREBASE_ORG_ID, it) }
        announcement.sheetsSpreadsheetIdHint?.let {
            write(InstitutionSettingsKeys.SHEETS_SPREADSHEET_ID_HINT, it)
        }
        announcement.firebaseProjectId?.takeIf { it.isNotBlank() }?.let {
            write(InstitutionSettingsKeys.FIREBASE_PROJECT_ID, it)
        }
        announcement.firebaseApplicationId?.takeIf { it.isNotBlank() }?.let {
            write(InstitutionSettingsKeys.FIREBASE_APPLICATION_ID, it)
        }
        announcement.firebaseWebClientId?.takeIf { it.isNotBlank() }?.let {
            write(InstitutionSettingsKeys.FIREBASE_WEB_CLIENT_ID, it)
        }
        twoWaySyncService.backupInstitutionSettingsToSheets()
        // Verify peers will see FIREBASE / SHEETS on the Settings sheet.
        val verified = runCatching {
            googleSheetsService.initializeSheetsService()
            googleSheetsService.syncInstitutionSettingsFromSheets()
                .firstOrNull { it.key == InstitutionSettingsKeys.BACKEND_TYPE }
                ?.value
                ?.let { BackendType.fromStorage(it) }
        }.getOrNull()
        if (verified != announcement.backendType) {
            throw IllegalStateException(
                "Failed to publish backend_type=${announcement.backendType.name} to Google Sheets Settings " +
                    "(read back: ${verified?.name ?: "missing"}). Migration aborted before local switch.",
            )
        }
    }

    override suspend fun afterGuestSaved(guest: Guest) {
        if (guest.isTemporaryGuest) {
            googleSheetsService.updateTemporaryGuestInSheets(guest)
            return
        }
        twoWaySyncService.backupGuestsToSheets()
        afterVolunteerGuestListRecalcNeeded()
    }

    override suspend fun afterGuestDeleted(guest: Guest) {
        if (guest.isTemporaryGuest) {
            googleSheetsService.deleteTemporaryGuestFromSheets(guest.sheetsId)
            return
        }
        deletionTracker?.trackGuestDeletion(guest.id.toString(), guest.sheetsId, businessKey = guest.nanoId)
        twoWaySyncService.backupGuestsToSheets()
        afterVolunteerGuestListRecalcNeeded()
    }

    override suspend fun afterTemporaryGuestBatch(batch: ManualTemporaryGuestBatch) {
        googleSheetsService.appendTemporaryGuestManualBatch(batch)
        onTemporaryGuestsRefresh?.invoke()
    }

    override suspend fun afterVolunteerSaved(volunteer: Volunteer) {
        twoWaySyncService.backupVolunteersToSheets()
        afterVolunteerGuestListRecalcNeeded()
    }

    override suspend fun afterVolunteerDeleted(volunteer: Volunteer, deleteShifts: Boolean) {
        deletionTracker?.trackVolunteerDeletion(volunteer.id, volunteer.sheetsId, businessKey = volunteer.id)
        twoWaySyncService.backupVolunteersToSheets()
        if (deleteShifts) twoWaySyncService.backupJobsToSheets()
        afterVolunteerGuestListRecalcNeeded()
    }

    override suspend fun afterJobSaved(job: Job) {
        // Prefer incremental write (matches pre-Firebase ViewModel add/update paths).
        if (job.sheetsId != null) {
            try {
                val venues = repository.getAllVenues().first()
                val configs = repository.getAllJobTypeConfigs().first()
                val volunteer = repository.getVolunteerById(job.volunteerId)
                val displayName = volunteer?.let { "${it.name} ${it.lastNameAbbreviation}".trim() }.orEmpty()
                googleSheetsService.updateJobInSheets(job, venues, configs, displayName)
            } catch (_: Exception) {
                twoWaySyncService.backupJobsToSheets()
            }
        } else {
            try {
                val venues = repository.getAllVenues().first()
                val configs = repository.getAllJobTypeConfigs().first()
                val volunteer = repository.getVolunteerById(job.volunteerId)
                val displayName = volunteer?.let { "${it.name} ${it.lastNameAbbreviation}".trim() }.orEmpty()
                val sheetsId = googleSheetsService.addJobToSheets(job, venues, configs, displayName)
                if (sheetsId.isNotBlank()) {
                    repository.updateJob(job.copy(sheetsId = sheetsId))
                }
            } catch (_: Exception) {
                twoWaySyncService.backupJobsToSheets()
            }
        }
        afterVolunteerGuestListRecalcNeeded()
    }

    override suspend fun afterJobDeleted(job: Job) {
        deletionTracker?.trackJobDeletion(
            job.id.toString(),
            job.sheetsId,
            businessKey = "${job.volunteerId}|${job.jobTypeName}|${job.date}|${job.venueName}|${job.shiftTime}",
        )
        if (job.sheetsId != null) {
            try {
                googleSheetsService.deleteJobFromSheets(job.id.toString(), job.sheetsId)
            } catch (_: Exception) {
                twoWaySyncService.backupJobsToSheets()
            }
        } else {
            twoWaySyncService.backupJobsToSheets()
        }
        afterVolunteerGuestListRecalcNeeded()
    }

    override suspend fun afterBenefitEntryConsumed(job: Job) {
        afterJobSaved(job)
    }

    override suspend fun afterJobTypeSaved(config: JobTypeConfig) {
        twoWaySyncService.backupJobTypesToSheets()
    }

    override suspend fun afterJobTypeDeleted(config: JobTypeConfig) {
        deletionTracker?.trackJobTypeDeletion(config.id.toString(), config.sheetsId, businessKey = config.name)
        twoWaySyncService.backupJobTypesToSheets()
    }

    override suspend fun afterVenueSaved(venue: VenueEntity) {
        twoWaySyncService.backupVenuesToSheets()
    }

    override suspend fun afterVenueDeleted(venue: VenueEntity) {
        deletionTracker?.trackVenueDeletion(venue.id.toString(), venue.sheetsId, businessKey = venue.name)
        twoWaySyncService.backupVenuesToSheets()
    }

    override suspend fun afterSalesItemSaved(item: SalesSheetItem) {
        twoWaySyncService.backupSalesSheetItemsToSheets()
    }

    override suspend fun afterSalesItemDeleted(item: SalesSheetItem) {
        deletionTracker?.trackSalesSheetItemDeletion(item.id.toString(), item.sheetsId, businessKey = item.name)
        twoWaySyncService.backupSalesSheetItemsToSheets()
    }

    // Guest list forms are Firebase-only: the Sheets contract has no tab for them.
    override suspend fun afterGuestFormSaved(form: GuestForm) = Unit

    override suspend fun afterGuestFormDeleted(form: GuestForm) = Unit

    override suspend fun afterTransfersChanged() {
        twoWaySyncService.backupTransfersToSheets()
    }

    override suspend fun afterInstitutionSettingsChanged() {
        twoWaySyncService.backupInstitutionSettingsToSheets()
    }

    override suspend fun afterVolunteerGuestListRecalcNeeded() {
        onVolunteerGuestListRecalc?.invoke()
    }

    override suspend fun updatePeopleCounter(venue: VenueEntity, count: Int) {
        onPeopleCounterUpdate?.invoke(venue, count) ?: run {
            val row = venue.sheetsId?.toIntOrNull()
            if (row != null) {
                val deviceId = settingsManager.getOrCreatePersistentDeviceId()
                twoWaySyncService.updateVenuePeopleCounterOnSheets(
                    row,
                    count,
                    deviceId,
                    System.currentTimeMillis(),
                )
            } else {
                twoWaySyncService.backupVenuesToSheets()
            }
        }
    }

    override suspend fun sendVenueAnnouncement(venueIds: List<Long>, title: String, message: String) {
        val deviceId = settingsManager.getOrCreatePersistentDeviceId()
        val venues = repository.getAllVenues().first().filter { it.id in venueIds }
        var wroteCell = false
        for (venue in venues) {
            val row = venue.sheetsId?.toIntOrNull() ?: continue
            syncManager.sendAnnouncement(row, title, message, deviceId)
            wroteCell = true
        }
        // Only fall back to full backup if no venue had a sheet row (legacy / unsynced)
        if (!wroteCell && venues.isNotEmpty()) {
            twoWaySyncService.backupVenuesToSheets()
        }
    }

    private fun parseAnnouncement(map: Map<String, String>): InstitutionBackendAnnouncement? {
        val typeRaw = map[InstitutionSettingsKeys.BACKEND_TYPE]?.trim().orEmpty()
        val migrationId = map[InstitutionSettingsKeys.BACKEND_MIGRATION_ID].orEmpty()
        if (typeRaw.isBlank() && migrationId.isBlank()) return null
        val type = BackendType.fromStorage(typeRaw.ifBlank { null })
        return InstitutionBackendAnnouncement(
            backendType = type,
            migrationId = migrationId,
            migratedAt = map[InstitutionSettingsKeys.BACKEND_MIGRATION_AT]?.toLongOrNull() ?: 0L,
            migratedBy = map[InstitutionSettingsKeys.BACKEND_MIGRATION_BY].orEmpty(),
            firebaseOrgId = map[InstitutionSettingsKeys.FIREBASE_ORG_ID]?.takeIf { it.isNotBlank() },
            sheetsSpreadsheetIdHint = map[InstitutionSettingsKeys.SHEETS_SPREADSHEET_ID_HINT]
                ?.takeIf { it.isNotBlank() },
            firebaseProjectId = map[InstitutionSettingsKeys.FIREBASE_PROJECT_ID]?.takeIf { it.isNotBlank() },
            firebaseApplicationId = map[InstitutionSettingsKeys.FIREBASE_APPLICATION_ID]?.takeIf { it.isNotBlank() },
            firebaseWebClientId = map[InstitutionSettingsKeys.FIREBASE_WEB_CLIENT_ID]?.takeIf { it.isNotBlank() },
        )
    }
}
