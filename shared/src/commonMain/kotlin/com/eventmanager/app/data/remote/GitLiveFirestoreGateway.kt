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
import com.eventmanager.app.data.security.crypto.SensitiveFieldCodec
import com.eventmanager.app.data.sync.InstitutionSettingsKeys
import com.eventmanager.app.data.sync.SettingsManager
import com.eventmanager.app.platform.PlatformContext
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.firestore.ChangeType
import dev.gitlive.firebase.firestore.FirebaseFirestore
import dev.gitlive.firebase.firestore.Source
import dev.gitlive.firebase.firestore.firestore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/**
 * GitLive-backed Firestore gateway with real set/get/listen when Firebase is initialized.
 */
class GitLiveFirestoreGateway(
    private val platformContext: PlatformContext? = null,
    private val settingsManager: SettingsManager? = null,
) : FirestoreGateway {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val listenerJobs = mutableListOf<CoroutineJob>()
    private val mutex = Mutex()
    private var serverReachable: Boolean = false
    private var serverReachabilityListener: (() -> Unit)? = null

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

    private fun ensureReady(): Boolean {
        val settings = settingsManager
        val ctx = platformContext
        if (settings != null && ctx != null) {
            FirebaseBootstrap.ensureInitialized(ctx, FirebaseOptionsReader.fromSettings(settings))
        }
        return FirebaseBootstrap.isInitialized() || runCatching {
            Class.forName("dev.gitlive.firebase.Firebase")
            Firebase.firestore
            true
        }.getOrDefault(false)
    }

    private fun db(): FirebaseFirestore? = runCatching {
        if (!ensureReady()) return null
        Firebase.firestore
    }.getOrNull()

    override fun isAvailable(): Boolean = ensureReady() && db() != null

    override fun isServerReachable(): Boolean = serverReachable

    override fun setServerReachabilityListener(listener: (() -> Unit)?) {
        serverReachabilityListener = listener
    }

    private fun noteServerReachability(fromServer: Boolean) {
        if (serverReachable == fromServer) return
        serverReachable = fromServer
        serverReachabilityListener?.invoke()
    }

    override suspend fun startOrgListeners(orgIds: List<String>, onChange: suspend (FirestoreRemoteChange) -> Unit) {
        val distinct = orgIds.map { it.trim() }.filter { it.isNotBlank() }.distinct()
        if (distinct.isEmpty()) return
        val firestore = db() ?: return
        stopOrgListeners()
        mutex.withLock {
            for (orgId in distinct) {
                for (collection in watchedCollections) {
                    val job = scope.launch {
                        firestore.collection("orgs").document(orgId).collection(collection)
                            .snapshots(includeMetadataChanges = true)
                            .catch { /* keep other listeners alive */ }
                            .collect { snapshot ->
                                noteServerReachability(!snapshot.metadata.isFromCache)
                                snapshot.documentChanges.forEach { change ->
                                    val docId = change.document.id
                                    val deleted = change.type == ChangeType.REMOVED
                                    val data = if (deleted) {
                                        null
                                    } else {
                                        decodeSnapshot(change.document)
                                    }
                                    if (FirestoreApplyPolicy.shouldSkipIncompleteSnapshot(deleted, data)) {
                                        return@forEach
                                    }
                                    try {
                                        onChange(
                                            FirestoreRemoteChange(
                                                orgId = orgId,
                                                collection = collection,
                                                documentId = docId,
                                                data = data,
                                                deleted = deleted,
                                            ),
                                        )
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        println("Firestore listener apply failed for $collection/$docId: ${e.message}")
                                    }
                                }
                            }
                    }
                    listenerJobs.add(job)
                }
            }
        }
    }

    override fun stopOrgListeners() {
        listenerJobs.forEach { it.cancel() }
        listenerJobs.clear()
        noteServerReachability(false)
    }

    override suspend fun flushPendingWrites() {
        firestoreWaitForPendingWrites()
    }

    override suspend fun upsertDocument(orgId: String, collection: String, docId: String, data: Map<String, Any?>) {
        val firestore = db() ?: throw IllegalStateException("Firestore is not initialized")
        if (orgId.isBlank() || docId.isBlank()) return
        val ref = firestore.collection("orgs").document(orgId).collection(collection).document(docId)
        val fields = ruleCompatibleFirestoreMap(data)
        try {
            when (collection) {
                // Append-only ledger: create once, never update (firestore.rules).
                "transfers" -> {
                    val exists = runCatching { ref.get().exists }.getOrDefault(false)
                    if (!exists) {
                        ref.set(toFirestoreFieldMap(fields), merge = false)
                    }
                }
                // Merge: missing docs are still creates (first-admin / invite); existing docs update.
                // Skip a prior get() — it can hang or fail and made role assignment look like a no-op.
                "members" -> {
                    ref.set(toFirestoreFieldMap(fields), merge = true)
                }
                else -> {
                    ref.set(toFirestoreFieldMap(fields), merge = true)
                }
            }
            // Do not waitForPendingWrites() here. That call waits for the entire SDK queue
            // (other large guest-form logo writes, unrelated docs). Close/expire already
            // awaited this document's set(); a later queue timeout used to look like a
            // failed write and fill pending_remote_writes while the public page was closed.
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Firebase write failed for $collection/$docId in $orgId: ${e.message}")
            throw e
        }
    }

    private fun decodeSnapshot(doc: dev.gitlive.firebase.firestore.DocumentSnapshot): Map<String, Any?> {
        // Prefer platform SDK map: GitLive data<JsonObject>/data<Map> fails under FirebaseDecoder,
        // and envelope-only reads miss public flat updates (status/submissionJson).
        val fromNative = runCatching { firestoreSnapshotRawMap(doc) }.getOrNull()
        if (!fromNative.isNullOrEmpty()) return fromNative
        val fromJsonObj = runCatching {
            val asJson = doc.data<kotlinx.serialization.json.JsonObject>()
            FirestoreJsonCodec.fromJsonObject(asJson)
        }.getOrNull()
        if (!fromJsonObj.isNullOrEmpty()) return fromJsonObj
        val fromMap = runCatching {
            @Suppress("UNCHECKED_CAST")
            val raw = doc.data<Map<String, Any?>>() ?: emptyMap()
            FirestoreJsonCodec.snapshotToMap(raw)
        }.getOrNull()
        if (!fromMap.isNullOrEmpty()) return fromMap
        val fromEnvelope = runCatching {
            val envelope = doc.data<FirestoreJsonEnvelope>()
            if (envelope.json.isNotBlank()) FirestoreJsonCodec.fromEnvelope(envelope) else emptyMap()
        }.getOrNull()
        if (!fromEnvelope.isNullOrEmpty()) return fromEnvelope
        return emptyMap()
    }

    override suspend fun deleteDocument(orgId: String, collection: String, docId: String) {
        val firestore = db() ?: return
        if (orgId.isBlank() || docId.isBlank()) return
        firestore.collection("orgs").document(orgId).collection(collection).document(docId).delete()
    }

    override suspend fun getDocumentFromServer(
        orgId: String,
        collection: String,
        docId: String,
    ): Map<String, Any?>? {
        val firestore = db() ?: return null
        if (orgId.isBlank() || collection.isBlank() || docId.isBlank()) return null
        val snap = firestore.collection("orgs").document(orgId).collection(collection).document(docId)
            .get(source = Source.SERVER)
        if (!snap.exists) return null
        return decodeSnapshot(snap)
    }

    override suspend fun pullAllIntoRepository(
        orgId: String,
        repository: EventManagerRepository,
        collections: Collection<String>?,
    ) {
        val firestore = db() ?: return
        if (orgId.isBlank()) return
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
            val snap = firestore.collection("orgs").document(orgId).collection(collection).get()
            if (!snap.metadata.isFromCache) {
                noteServerReachability(true)
            }
            for (doc in snap.documents) {
                val data = decodeSnapshot(doc)
                if (data.isEmpty()) continue
                if (collection == "institutionSettings") {
                    val value = data["value"]?.toString().orEmpty()
                    val lm = when (val raw = data["lastModified"]) {
                        is Number -> raw.toLong()
                        is String -> raw.toLongOrNull() ?: System.currentTimeMillis()
                        else -> System.currentTimeMillis()
                    }
                    settingsManager?.applyInstitutionSettingFromRemote(doc.id, value, lm)
                    continue
                }
                if (collection == "metadata") continue
                FirestoreChangeApplier.apply(
                    FirestoreRemoteChange(
                        orgId = orgId,
                        collection = collection,
                        documentId = doc.id,
                        data = data,
                        deleted = false,
                    ),
                    repository,
                )
            }
        }
    }

    override suspend fun applyChangeToRepository(change: FirestoreRemoteChange, repository: EventManagerRepository) {
        FirestoreChangeApplier.apply(change, repository)
    }

    override suspend fun readBackendAnnouncement(orgId: String): InstitutionBackendAnnouncement? {
        val firestore = db() ?: return null
        if (orgId.isBlank()) return null
        return runCatching {
            val snap = firestore.collection("orgs").document(orgId)
                .collection("metadata").document("config").get()
            if (!snap.exists) return null
            val data = decodeSnapshot(snap)
            val typeRaw = data["backendType"] as? String ?: return null
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

    override suspend fun readMemberRole(orgId: String, uid: String): String? {
        return readMemberRole(orgId, uid, fromServer = false)
    }

    override suspend fun readMemberRoleFromServer(orgId: String, uid: String): String? {
        return readMemberRole(orgId, uid, fromServer = true)
    }

    override suspend fun isOrgAccessibleOnServer(orgId: String, uid: String): Boolean =
        readMemberRole(orgId, uid, fromServer = true) != null

    override suspend fun probeMembership(orgId: String, uid: String): MembershipProbe {
        val firestore = db() ?: return MembershipProbe.Unavailable
        if (orgId.isBlank() || uid.isBlank() || isFirebaseOrgAllSentinel(orgId)) {
            return MembershipProbe.Unavailable
        }
        return try {
            val snap = firestore.collection("orgs").document(orgId)
                .collection("members").document(uid).get(source = Source.SERVER)
            noteServerReachability(true)
            when {
                !snap.exists -> MembershipProbe.Absent
                // A member doc with no readable role still proves membership.
                else -> MembershipProbe.Member(
                    memberRecordFromSnapshot(snap).role ?: MemberRole.MEMBER.storageValue(),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (isFirestorePermissionDenied(e)) {
                println("Firebase membership probe denied for $orgId/$uid: ${e.message}")
                MembershipProbe.Denied
            } else {
                println("Firebase membership probe unavailable for $orgId/$uid: ${e.message}")
                MembershipProbe.Unavailable
            }
        }
    }

    override suspend fun listMembers(orgId: String): List<FirebaseTeamMemberListing> {
        val firestore = db() ?: error("Firestore is not initialized")
        if (orgId.isBlank() || isFirebaseOrgAllSentinel(orgId)) return emptyList()
        return runCatching {
            val query = firestore.collection("orgs").document(orgId).collection("members")
            val snap = runCatching { query.get(source = Source.SERVER) }.getOrElse { query.get() }
            snap.documents.map { doc -> memberListingFromSnapshot(doc) }.sortedWith(
                compareBy<FirebaseTeamMemberListing> { MemberRole.fromStorage(it.role) != MemberRole.ADMIN }
                    .thenBy { it.email.orEmpty().lowercase() }
            )
        }.getOrElse { e ->
            if (e is CancellationException) throw e
            println("Firebase team: listMembers failed: ${e.message}")
            throw e
        }
    }

    private fun memberListingFromSnapshot(
        doc: dev.gitlive.firebase.firestore.DocumentSnapshot,
    ): FirebaseTeamMemberListing {
        val record = memberRecordFromSnapshot(doc)
        return FirebaseTeamMemberListing(email = record.email, role = record.role)
    }

    private fun memberRecordFromSnapshot(
        doc: dev.gitlive.firebase.firestore.DocumentSnapshot,
    ): FirestoreMemberRecord {
        val data = decodeSnapshot(doc)
        val role = data["role"]?.toString()?.trim()?.ifBlank { null }
            ?: runCatching {
                if (doc.contains("role")) doc.get<String?>("role") else null
            }.getOrNull()?.trim()?.ifBlank { null }
        val email = data["email"]?.toString()?.trim()?.ifBlank { null }
            ?: runCatching {
                if (doc.contains("email")) doc.get<String?>("email") else null
            }.getOrNull()?.trim()?.ifBlank { null }
        return FirestoreMemberRecord(uid = doc.id, email = email, role = role)
    }

    private suspend fun readMemberRole(orgId: String, uid: String, fromServer: Boolean): String? {
        val firestore = db() ?: return null
        if (orgId.isBlank() || uid.isBlank()) return null
        return runCatching {
            val snap = if (fromServer) {
                firestore.collection("orgs").document(orgId)
                    .collection("members").document(uid).get(source = Source.SERVER)
            } else {
                firestore.collection("orgs").document(orgId)
                    .collection("members").document(uid).get()
            }
            if (!snap.exists) return null
            memberRecordFromSnapshot(snap).role
        }.getOrNull()
    }

    override suspend fun writeBackendAnnouncement(orgId: String, announcement: InstitutionBackendAnnouncement) {
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
        val firestore = db() ?: return
        if (orgId.isBlank() || venueName.isBlank()) return
        val writeAt = if (lastModified > 0L) lastModified else System.currentTimeMillis()
        val sourceDeviceId = settingsManager?.getOrCreatePersistentDeviceId()?.trim().orEmpty()
        firestore.runTransaction {
            val ref = firestore.collection("orgs").document(orgId).collection("venues").document(venueName)
            val snap = get(ref)
            val existing = if (snap.exists) decodeSnapshot(snap) else emptyMap()
            val currentWriter = existing["peopleCounterWriterDeviceId"]?.toString().orEmpty().trim()
            if (currentWriter.isNotEmpty() && currentWriter != deviceId && deviceId.isNotEmpty()) {
                // Soft arbitration: another device holds the counter; only force-steal paths pass empty→deviceId.
                // Callers that need steal should pass the new deviceId after reading remote (claim with force).
            }
            val remotePcm = when (val raw = existing["peopleCounterLastModified"]) {
                is Number -> raw.toLong()
                is String -> raw.toLongOrNull() ?: 0L
                else -> 0L
            }
            // Stale in-flight writes must not clobber a newer count that already committed.
            if (FirestoreApplyPolicy.shouldKeepLocal(remotePcm, writeAt) && remotePcm > 0L) {
                return@runTransaction
            }
            val merged = existing.toMutableMap().apply {
                put("peopleCounterCount", count)
                put("peopleCounterWriterDeviceId", deviceId)
                put("peopleCounterWriterAccountEmail", writerAccountEmail.trim())
                put("peopleCounterLastModified", writeAt)
                put("name", venueName)
                put("lastModified", maxOf(
                    (existing["lastModified"] as? Number)?.toLong() ?: 0L,
                    writeAt,
                ))
                if (sourceDeviceId.isNotBlank()) {
                    put("sourceDeviceId", sourceDeviceId)
                }
            }
            set(ref, toFirestoreFieldMap(ruleCompatibleFirestoreMap(merged)), merge = true)
        }
    }

    override suspend fun runLedgerTransaction(
        orgId: String,
        transfer: AccountTransfer,
        holderKey: String,
        newBalance: Double,
        buffer: Double,
    ): LedgerCommitResult {
        val firestore = db() ?: return LedgerCommitResult.Unknown
        if (orgId.isBlank()) return LedgerCommitResult.Unknown
        return try {
            var result = LedgerCommitResult.Unknown
            firestore.runTransaction {
                val transferRef = firestore.collection("orgs").document(orgId)
                    .collection("transfers").document(transfer.sourceReference)
                val accountRef = firestore.collection("orgs").document(orgId)
                    .collection("accounts").document(holderKey)

                val existingTransfer = get(transferRef)
                if (existingTransfer.exists) {
                    result = LedgerCommitResult.Accepted
                    return@runTransaction
                }

                val accountSnap = get(accountRef)
                val currentBalance = if (accountSnap.exists) {
                    val mapped = decodeSnapshot(accountSnap)
                    when (val v = mapped["balance"]) {
                        is Number -> v.toDouble()
                        is String -> v.toDoubleOrNull() ?: 0.0
                        else -> 0.0
                    }
                } else {
                    newBalance - transfer.amount
                }
                val nextBalance = currentBalance + transfer.amount
                if (nextBalance < -buffer) {
                    result = LedgerCommitResult.RejectedInsufficientFunds
                    return@runTransaction
                }

                // Flat fields so rules can read balance; transfers are create-only (append-only rules).
                val transferFields = ruleCompatibleFirestoreMap(transferToMap(transfer)).toMutableMap()
                settingsManager?.getOrCreatePersistentDeviceId()?.trim()?.takeIf { it.isNotBlank() }?.let {
                    transferFields["sourceDeviceId"] = it
                }
                val accountFields = ruleCompatibleFirestoreMap(
                    mapOf(
                        "balance" to nextBalance,
                        "updatedAt" to System.currentTimeMillis(),
                    ),
                )
                set(transferRef, toFirestoreFieldMap(transferFields), merge = false)
                set(accountRef, toFirestoreFieldMap(accountFields), merge = true)
                result = LedgerCommitResult.Accepted
            }
            result
        } catch (e: Exception) {
            if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            LedgerCommitResult.Unknown
        }
    }

    override fun guestToMap(guest: Guest) = SensitiveFieldCodec.encryptGuestMap(
        buildMap {
            put("nanoId", guest.nanoId)
            put("name", guest.name)
            put("lastNameAbbreviation", guest.lastNameAbbreviation)
            put("email", guest.email)
            put("phone", guest.phoneNumber)
            put("invitations", guest.invitations)
            put("venueName", guest.venueName)
            put("notes", guest.notes)
            put("isVolunteerBenefit", guest.isVolunteerBenefit)
            put("volunteerId", guest.volunteerId)
            put("lastModified", guest.lastModified)
            put("isTemporaryGuest", guest.isTemporaryGuest)
            put("temporaryArtistName", guest.temporaryArtistName)
            put("temporaryEventDate", guest.temporaryEventDate)
            put("temporaryContactPhone", guest.temporaryContactPhone)
            put("temporaryVenueName", guest.temporaryVenueName)
            put("temporaryContactEmail", guest.temporaryContactEmail)
            put("temporaryAccessIds", guest.temporaryAccessIds)
            put("temporaryEntryValidatedAt", guest.temporaryEntryValidatedAt)
            put("temporaryEntryValidatedBy", guest.temporaryEntryValidatedBy)
            put("nfcCardUid", guest.nfcCardUid)
            put(
                "nfcCardUidHash",
                guest.nfcCardUidHash.ifBlank {
                    SensitiveFieldCodec.nfcLookupHash(guest.nfcCardUid, guest.firebaseOrgId)
                },
            )
            put("isAdmin", guest.isAdmin)
            put("barDiscountPercent", guest.barDiscountPercent)
            putProfilePhotoFields(guest.profilePhotoPath, guest.profilePhotoUrl)
        },
        guest.firebaseOrgId,
    )

    override fun volunteerToMap(volunteer: Volunteer) = SensitiveFieldCodec.encryptVolunteerMap(
        buildMap {
            put("id", volunteer.id)
            put("name", volunteer.name)
            put("lastNameAbbreviation", volunteer.lastNameAbbreviation)
            put("email", volunteer.email)
            put("phone", volunteer.phoneNumber)
            put("dateOfBirth", volunteer.dateOfBirth)
            put("gender", volunteer.gender?.name)
            put("currentRank", volunteer.currentRank?.name)
            put("isActive", volunteer.isActive)
            put("lastShiftDate", volunteer.lastShiftDate)
            put("lastModified", volunteer.lastModified)
            put("nfcCardUid", volunteer.nfcCardUid)
            put(
                "nfcCardUidHash",
                volunteer.nfcCardUidHash.ifBlank {
                    SensitiveFieldCodec.nfcLookupHash(volunteer.nfcCardUid, volunteer.firebaseOrgId)
                },
            )
            put("isAdmin", volunteer.isAdmin)
            putProfilePhotoFields(volunteer.profilePhotoPath, volunteer.profilePhotoUrl)
        },
        volunteer.firebaseOrgId,
    )

    override fun jobToMap(job: Job) = mapOf(
        "jobNanoId" to job.jobNanoId,
        "volunteerId" to job.volunteerId,
        "jobTypeName" to job.jobTypeName,
        "venueName" to job.venueName,
        "date" to job.date,
        "shiftTime" to job.shiftTime.name,
        "benefitFutureEntriesRemaining" to job.benefitFutureEntriesRemaining,
        "benefitFutureEntryInvites" to job.benefitFutureEntryInvites,
        "notes" to job.notes,
        "lastModified" to job.lastModified,
    )

    override fun jobTypeToMap(config: JobTypeConfig) = mapOf(
        "name" to config.name,
        "isActive" to config.isActive,
        "isShiftJob" to config.isShiftJob,
        "isOrionJob" to config.isOrionJob,
        "requiresShiftTime" to config.requiresShiftTime,
        "novaJobType" to config.novaJobType.name,
        "benefitSystemType" to config.benefitSystemType.name,
        "manualRewards" to com.eventmanager.app.data.models.Converters().fromManualRewards(config.manualRewards),
        "accountCreditChf" to config.accountCreditChf,
        "description" to config.description,
        "lastModified" to config.lastModified,
    )

    override fun guestFormToMap(form: GuestForm) = mapOf(
        "formId" to form.formId,
        "venueName" to form.venueName,
        "eventName" to form.eventName,
        "eventDateMillis" to firestoreMillis(form.eventDateMillis),
        "artistName" to form.artistName,
        "offeredAccessIds" to form.offeredAccessIds,
        "maxGuests" to form.maxGuests,
        "expiryMode" to form.expiryMode,
        "expiresAtMillis" to firestoreMillis(form.expiresAtMillis),
        "allowMultipleResponses" to form.allowMultipleResponses,
        "parentFormId" to form.parentFormId,
        "showInstitutionLogo" to form.showInstitutionLogo,
        "institutionLogoDataUri" to form.institutionLogoDataUri,
        "institutionLogoShape" to form.institutionLogoShape,
        "institutionLogoInvert" to form.institutionLogoInvert,
        "guestLogoDataUri" to form.guestLogoDataUri,
        "guestLogoShape" to form.guestLogoShape,
        "guestLogoInvert" to form.guestLogoInvert,
        "askEmail" to form.askEmail,
        "askPhone" to form.askPhone,
        "prefillEmail" to form.prefillEmail,
        "prefillPhone" to form.prefillPhone,
        "fieldLabelsJson" to form.fieldLabelsJson,
        "status" to form.status,
        "submissionJson" to form.submissionJson,
        "submittedAt" to firestoreMillis(form.submittedAt),
        "reviewedAt" to firestoreMillis(form.reviewedAt),
        "reviewedBy" to form.reviewedBy,
        "createdAt" to firestoreMillis(form.createdAt),
        "lastModified" to firestoreMillis(form.lastModified),
    )

    override fun venueToMap(venue: VenueEntity) = mapOf(
        "name" to venue.name,
        "isActive" to venue.isActive,
        "peopleCounterCount" to venue.peopleCounterCount,
        "peopleCounterWriterDeviceId" to venue.peopleCounterWriterDeviceId,
        "peopleCounterWriterAccountEmail" to venue.peopleCounterWriterAccountEmail,
        "peopleCounterLastModified" to venue.peopleCounterLastModified,
        "announcementTitle" to venue.announcementTitle,
        "announcementMessage" to venue.announcementMessage,
        "announcementSentAt" to venue.announcementSentAt,
        "announcementSenderDeviceId" to venue.announcementSenderDeviceId,
        "lastModified" to venue.lastModified,
    )

    override fun salesItemToMap(item: SalesSheetItem) = mapOf(
        "name" to item.name,
        "price" to item.price,
        "categories" to item.categories,
        "subcategory" to item.subcategory,
        "emoji" to item.emoji,
        "availableVenues" to item.availableVenues,
        "isActive" to item.isActive,
        "hasDiscount" to item.hasDiscount,
        "isDeposit" to item.isDeposit,
        "requiredRank" to item.requiredRank?.name,
        "lastModified" to item.lastModified,
    )

    override fun transferToMap(transfer: AccountTransfer): Map<String, Any?> {
        val mapped = SensitiveFieldCodec.encryptTransferMap(
            mapOf(
                "transferId" to transfer.transferId,
                "sourceReference" to transfer.sourceReference,
                "holderType" to transfer.holderType.name,
                "holderId" to transfer.holderId,
                "holderName" to transfer.holderName,
                "amount" to transfer.amount,
                "type" to transfer.type.name,
                "currencyCode" to transfer.currencyCode,
                "description" to transfer.description,
                "jobReferenceKey" to transfer.jobReferenceKey,
                "jobTypeName" to transfer.jobTypeName,
                "jobDate" to transfer.jobDate,
                "creditAmountPaid" to transfer.creditAmountPaid,
                "cashAmountPaid" to transfer.cashAmountPaid,
                "posBarDiscountPercent" to transfer.posBarDiscountPercent,
                "posItemsJson" to transfer.posItemsJson,
                "posVenueName" to transfer.posVenueName,
                "createdAt" to transfer.createdAt,
                "lastModified" to transfer.lastModified,
                "syncState" to transfer.syncState.name,
            ),
            transfer.firebaseOrgId,
        ).toMutableMap()
        settingsManager?.getOrCreatePersistentDeviceId()?.trim()?.takeIf { it.isNotBlank() }?.let {
            mapped["sourceDeviceId"] = it
        }
        return mapped
    }
}

private fun MutableMap<String, Any?>.putProfilePhotoFields(path: String, url: String) {
    // Omit blanks so a later volunteer/guest save on a device without the photo
    // cannot merge-overwrite Firestore and wipe the URL for everyone else.
    if (path.isNotBlank()) this["profilePhotoPath"] = path
    if (url.isNotBlank()) this["profilePhotoUrl"] = url
}
