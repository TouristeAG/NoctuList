package com.eventmanager.app.portal

import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.GuestForm
import com.eventmanager.app.data.models.temporaryEntryValidated
import com.eventmanager.app.data.models.GuestFormLogoCodec
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.remote.FirestoreGateway
import com.eventmanager.app.data.utils.DateTimeUtils
import com.eventmanager.app.data.utils.NanoIdGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

/** What the portal service needs from the app (persistence, sync, photos, e-mail). */
interface VolunteerPortalHost {
    val deviceId: String
    fun apiKey(): String
    fun currencyCode(): String
    fun associationName(): String
    fun dateOffsetHours(): Int
    /** Base64 PNG of the institution logo, or empty. */
    fun institutionLogoPng(): String
    fun profilePhotosEnabled(): Boolean
    /** Volunteers may also sign in with an association Google account. */
    fun portalGoogleEnabled(): Boolean
    /** Verified Google Workspace domains, lowercase. Ignored when [portalGoogleAnyAccount]. */
    fun portalGoogleDomains(): List<String>
    /** Every Google account may sign in, not only the association's domains. */
    fun portalGoogleAnyAccount(): Boolean = false
    /** Rule for the Google address of a newly opened access; empty when the automatic rule is off. */
    fun portalGoogleAddressTemplate(): String = ""

    /** Non-admin volunteers get their own QR code in the portal. */
    fun portalQrEnabled(): Boolean = true

    /** Permanent guests have their own space in the portal. */
    fun guestPortalEnabled(): Boolean = false

    /** Temporary guests have their own space in the portal. */
    fun temporaryGuestPortalEnabled(): Boolean = false

    /** Temporary guest credits are turned on. */
    fun temporaryGuestCreditsEnabled(): Boolean = false

    /** Venue access id to name, for the temporary guest page (ids never reach it). */
    fun venueAccessNames(): Map<String, String> = emptyMap()

    /** The venue access catalogue, for the guest list page and its edits. */
    fun venueAccessCatalog(): List<com.eventmanager.app.data.models.VenueAccess> = emptyList()

    /** Names of the active venues, where a guest list edit may add people. */
    fun activeVenueNames(): Set<String> = emptySet()

    /** Adds temporary guests like the manual "Add guests" dialog. */
    suspend fun addTemporaryGuests(batch: com.eventmanager.app.data.models.ManualTemporaryGuestBatch): Boolean = false

    /** Deletes one temporary guest. */
    suspend fun deleteTemporaryGuest(guest: Guest): Boolean = false

    suspend fun volunteers(): List<Volunteer>
    /** Inserts a new volunteer through the normal save + sync pipeline (with its optional photo). */
    suspend fun createVolunteer(volunteer: Volunteer, photo: ByteArray?): Volunteer?
    /** Saves an existing volunteer through the normal save + sync pipeline. */
    suspend fun saveVolunteer(volunteer: Volunteer): Boolean
    /** Adds a photo to a volunteer that already exists. */
    suspend fun attachVolunteerPhoto(volunteer: Volunteer, bytes: ByteArray): Boolean

    suspend fun downloadStorage(path: String): ByteArray?
    suspend fun uploadStorageJpeg(path: String, bytes: ByteArray): Boolean
    suspend fun deleteStorage(path: String)
    /** JPEG bytes of the volunteer's current profile photo, or null. */
    suspend fun volunteerPhotoBytes(volunteer: Volunteer): ByteArray?

    /** The "my QR code" mail for the server to send, or null when it cannot be rendered. Holds no volunteer data. */
    suspend fun portalMailTemplate(): PortalMailTemplate?
    /** The guest "my QR code" mail (guest e-mail settings, Guest theme), or null. Holds no guest data. */
    suspend fun portalGuestMailTemplate(): PortalMailTemplate? = null
    fun canSendQrEmail(): Boolean
    suspend fun sendQrEmail(volunteer: Volunteer): Boolean

    /** Every guest on this device (all organizations; the service keeps its own). */
    suspend fun guests(): List<Guest> = emptyList()
    /** Saves an existing guest through the normal save + sync pipeline. */
    suspend fun saveGuest(guest: Guest): Boolean = false
    /** Inserts a new guest through the normal save + sync pipeline (with its optional photo). */
    suspend fun createGuest(guest: Guest, photo: ByteArray?): Guest? = null
    /** JPEG bytes of the guest's current profile photo, or null. */
    suspend fun guestPhotoBytes(guest: Guest): ByteArray? = null
    /** Adds a photo to a guest that already exists. */
    suspend fun attachGuestPhoto(guest: Guest, bytes: ByteArray): Boolean = false
    suspend fun sendGuestQrEmail(guest: Guest): Boolean = false

    fun loadPendingSignupForms(): List<VolunteerSignupForm>
    fun savePendingSignupForms(forms: List<VolunteerSignupForm>)
    fun loadPublishedHashes(): Map<String, String>
    fun savePublishedHashes(hashes: Map<String, String>)
}

/** Outcome of "open the portal for this volunteer" in the admin UI. */
sealed class PortalGrantResult {
    /** New account; [password] is shown once. No e-mail is sent. */
    data class Created(val password: String) : PortalGrantResult()
    /** An account already existed for the address: linking happens at sign-in. No e-mail is sent. */
    data object ExistingAccount : PortalGrantResult()
    data class Failed(val code: String) : PortalGrantResult()
}

/**
 * Volunteer portal engine, one per active organization. Runs on every team device while the
 * portal is on:
 *  - turns submitted registration links into volunteers,
 *  - processes portal requests (profile edits, QR by e-mail, account linking),
 *  - links association Google accounts (`portalGoogleClaims`) to volunteers,
 *  - publishes each linked volunteer's filtered projection, photo copy and, for non-admins, their QR document.
 *
 * Every request is claimed with a Firestore transaction first, so two devices never act twice.
 */
@OptIn(FlowPreview::class)
class VolunteerPortalService(
    private val orgId: String,
    private val gateway: FirestoreGateway,
    private val host: VolunteerPortalHost,
    private val scope: CoroutineScope,
    private val authClientFactory: (apiKey: String) -> PortalAuthApi = { PortalAuthClient(it) },
    private val clock: () -> Long = System::currentTimeMillis,
    private val publishDebounceMs: Long = PUBLISH_DEBOUNCE_MS,
    private val retryIntervalMs: Long = RETRY_INTERVAL_MS,
) {
    private val mutex = Mutex()
    private val guestPublisher = GuestPortalPublisher(orgId, gateway, host)
    /** Local day of the last publish: a new day republishes, so ended temporary groups go away. */
    @Volatile private var lastPublishDay: Long? = null
    private val listeners = mutableListOf<AutoCloseable>()
    private var publisherJob: CoroutineJob? = null
    private var latestSignupDocs: List<Pair<String, Map<String, Any?>>> = emptyList()
    private var latestRequestDocs: List<Pair<String, Map<String, Any?>>> = emptyList()
    @Volatile private var latestListEditDocs: List<Pair<String, Map<String, Any?>>> = emptyList()
    private val listEditProcessor by lazy { GuestListEditProcessor(host, clock) }
    private var latestClaimDocs: List<Pair<String, Map<String, Any?>>> = emptyList()
    @Volatile private var latestSnapshot: Snapshot? = null
    private var started = false
    private val _serverStatus = MutableStateFlow<PortalServerStatus?>(null)

    /** Last run reported by the Cloud Functions, or null when none ever ran. */
    val serverStatus: StateFlow<PortalServerStatus?> = _serverStatus.asStateFlow()

    fun start(
        volunteers: Flow<List<Volunteer>>,
        guests: Flow<List<Guest>>,
        jobs: Flow<List<Job>>,
        transfers: Flow<List<AccountTransfer>>,
        jobTypeConfigs: Flow<List<JobTypeConfig>>,
        guestForms: Flow<List<GuestForm>> = flowOf(emptyList()),
    ) {
        if (started) return
        started = true
        gateway.listenCollection(orgId, VolunteerSignupForm.COLLECTION) { docs ->
            latestSignupDocs = docs
            processSignups(docs)
        }?.let { listeners += it }
        gateway.listenCollection(orgId, GuestListEdit.COLLECTION) { docs ->
            latestListEditDocs = docs
            processListEdits(docs)
        }?.let { listeners += it }
        gateway.listenCollection(orgId, PortalRequest.COLLECTION) { docs ->
            latestRequestDocs = docs
            processRequests(docs)
        }?.let { listeners += it }
        gateway.listenCollection(orgId, PortalGoogleClaim.COLLECTION) { docs ->
            latestClaimDocs = docs
            processClaims(docs)
        }?.let { listeners += it }
        gateway.listenCollection(orgId, PortalServerStatus.COLLECTION) { docs ->
            _serverStatus.value = docs.firstOrNull { it.first == PortalServerStatus.DOC }
                ?.let { PortalServerStatus.fromMap(it.second) }
        }?.let { listeners += it }
        publisherJob = scope.launch {
            combine(
                combine(volunteers, guests, jobs, transfers, jobTypeConfigs) { v, g, j, t, c -> Snapshot(v, g, j, t, c) },
                guestForms,
            ) { snapshot, forms -> snapshot.copy(guestForms = forms).also { latestSnapshot = it } }
                .debounce(publishDebounceMs)
                .collect { snapshot ->
                    runCatchingLogged("publish") { publishAll(snapshot) }
                }
        }
        // Stale PROCESSING claims (a device that vanished mid-way) are retried periodically.
        scope.launch {
            while (started) {
                delay(retryIntervalMs)
                runCatchingLogged("retry") {
                    processSignups(latestSignupDocs)
                    processRequests(latestRequestDocs)
                    processListEdits(latestListEditDocs)
                    processClaims(latestClaimDocs)
                    cleanupExpiredForms()
                }
                val publishedDay = lastPublishDay
                val listEnded = guestPublisher.nextTemporaryExpiry?.let { clock() >= it } == true
                if (publishedDay != null && publishedDay != today() || listEnded) republish()
            }
        }
    }

    /**
     * Re-runs the publish with the latest data, e.g. after the Google option or its domains changed.
     * Content hashes keep unchanged documents from being rewritten.
     */
    suspend fun republish() {
        val snapshot = latestSnapshot ?: return
        runCatchingLogged("republish") { publishAll(snapshot) }
    }

    fun stop() {
        started = false
        listeners.forEach { runCatching { it.close() } }
        listeners.clear()
        publisherJob?.cancel()
        publisherJob = null
    }

    // region Registration links

    /** A single-use registration link; [venueName] is the venue of the guest a GUEST link creates. */
    suspend fun createSignupForm(
        holderType: PortalHolderKind = PortalHolderKind.VOLUNTEER,
        venueName: String = "",
    ): VolunteerSignupForm? {
        val now = clock()
        val form = VolunteerSignupForm(
            formId = NanoIdGenerator.generateGuestFormId(),
            orgId = orgId,
            createdAt = now,
            expiresAtMillis = now + VolunteerSignupForm.VALIDITY_MILLIS,
            photosEnabled = host.profilePhotosEnabled(),
            holderType = holderType,
            venueName = if (holderType == PortalHolderKind.GUEST) venueName.trim() else "",
        )
        return try {
            gateway.setPlainDocument(
                orgId,
                VolunteerSignupForm.COLLECTION,
                form.formId,
                form.toFirestoreMap(host.associationName()),
                merge = false,
            )
            host.savePendingSignupForms(host.loadPendingSignupForms() + form)
            form
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Portal: signup form create failed: ${e.message}")
            null
        }
    }

    /** True once the volunteer behind [form] exists (the link dialog closes itself). */
    suspend fun signupFormState(form: VolunteerSignupForm): SignupFormState {
        val doc = runCatching {
            gateway.getDocumentFromServer(orgId, VolunteerSignupForm.COLLECTION, form.formId)
        }.getOrNull()
        if (doc == null) {
            return if (host.loadPendingSignupForms().none { it.formId == form.formId }) {
                SignupFormState.Completed
            } else {
                SignupFormState.Waiting
            }
        }
        val status = doc["status"]?.toString().orEmpty()
        return when {
            status == VolunteerSignupForm.STATUS_OPEN && form.isExpiredAt(clock()) -> SignupFormState.Expired
            status == VolunteerSignupForm.STATUS_OPEN -> SignupFormState.Waiting
            else -> SignupFormState.Submitted
        }
    }

    private suspend fun processSignups(docs: List<Pair<String, Map<String, Any?>>>) {
        for ((formId, data) in docs) {
            val status = data["status"]?.toString().orEmpty()
            val retryable = status == VolunteerSignupForm.STATUS_SUBMITTED || isStaleProcessing(data)
            if (!retryable) continue
            if (isFreshForServer(data["submittedAt"])) continue
            // Guest space off: guest registrations wait, like the guest requests.
            if (isGuestSignup(data) && !host.guestPortalEnabled()) continue
            mutex.withLock { runCatchingLogged("signup $formId") { processSignup(formId) } }
        }
    }

    private suspend fun processSignup(formId: String) {
        val now = clock()
        val claimed = gateway.claimDocument(
            orgId,
            VolunteerSignupForm.COLLECTION,
            formId,
            claimable = {
                (it["status"] == VolunteerSignupForm.STATUS_SUBMITTED || isStaleProcessing(it)) &&
                    (!isGuestSignup(it) || host.guestPortalEnabled())
            },
            claimFields = processingFields(now),
        ) ?: return
        val submission = PortalInputValidator.parseSignup(formId, orgId, claimed.withStatus(VolunteerSignupForm.STATUS_SUBMITTED))
        if (submission == null) {
            // Unreadable answer: drop it rather than retrying forever.
            println("Portal: rejected invalid registration $formId")
            finishSignup(formId, VolunteerSignupForm.photoStoragePath(orgId, formId))
            return
        }
        val photo = submission.photoPath.takeIf { it.isNotBlank() }?.let { host.downloadStorage(it) }
        if (submission.holderType == PortalHolderKind.GUEST) {
            if (registerGuest(formId, submission, photo, now)) finishSignup(formId, submission.photoPath)
            return
        }
        val all = host.volunteers()
        val alreadyLinked = all.firstOrNull { it.portalUid == submission.uid }
        if (alreadyLinked == null) {
            val sameEmail = all.firstOrNull {
                it.email.trim().equals(submission.email, ignoreCase = true) && it.portalUid.isBlank() &&
                    (it.firebaseOrgId.isBlank() || it.firebaseOrgId == orgId)
            }
            if (sameEmail != null) {
                // Same person already on the roster: link instead of creating a duplicate profile.
                // Reading the portal still requires verifying this e-mail address.
                val linked = sameEmail.copy(portalEnabled = true, portalUid = submission.uid, lastModified = now)
                if (!host.saveVolunteer(linked)) return
                if (photo != null && linked.profilePhotoPath.isBlank()) host.attachVolunteerPhoto(linked, photo)
                applyGoogleAddressRule(linked.id)
            } else {
                val created = host.createVolunteer(
                    Volunteer(
                        name = submission.firstName,
                        lastNameAbbreviation = submission.lastName.uppercase(),
                        email = submission.email,
                        phoneNumber = submission.phone,
                        dateOfBirth = submission.dateOfBirth,
                        gender = submission.gender,
                        firebaseOrgId = orgId,
                        portalEnabled = true,
                        portalUid = submission.uid,
                        lastModified = now,
                    ),
                    photo,
                ) ?: return
                println("Portal: registered volunteer ${created.name} from link $formId")
                applyGoogleAddressRule(created.id)
            }
        }
        finishSignup(formId, submission.photoPath)
    }

    /**
     * A GUEST link: links the permanent guest of the same address that has no account yet, else
     * creates one (no invitations, no discount, the venue of the link). False when a save failed.
     */
    private suspend fun registerGuest(formId: String, submission: SignupSubmission, photo: ByteArray?, now: Long): Boolean {
        val permanents = orgGuests().filter { it.isPermanentPortalGuest }
        if (permanents.any { it.portalUid == submission.uid }) return true
        val sameEmail = permanents.firstOrNull {
            it.email.trim().equals(submission.email, ignoreCase = true) && it.portalUid.isBlank()
        }
        if (sameEmail != null) {
            // Reading the portal still requires verifying this e-mail address.
            val linked = sameEmail.copy(
                portalEnabled = true,
                portalUid = submission.uid,
                lastModified = maxOf(now, sameEmail.lastModified + 1),
            )
            if (!host.saveGuest(linked)) return false
            if (photo != null && linked.profilePhotoPath.isBlank()) host.attachGuestPhoto(linked, photo)
            applyGoogleAddressRule(PortalHolderKind.GUEST, linked.nanoId)
            return true
        }
        val created = host.createGuest(
            Guest(
                name = submission.firstName,
                lastNameAbbreviation = submission.lastName.uppercase(),
                email = submission.email,
                phoneNumber = submission.phone,
                invitations = 0,
                venueName = submission.venueName,
                barDiscountPercent = 0,
                firebaseOrgId = orgId,
                portalEnabled = true,
                portalUid = submission.uid,
                lastModified = now,
            ),
            photo,
        ) ?: return false
        println("Portal: registered a guest from link $formId")
        applyGoogleAddressRule(PortalHolderKind.GUEST, created.nanoId)
        return true
    }

    private fun isGuestSignup(data: Map<String, Any?>): Boolean = data["holderType"] == PortalHolderKind.GUEST.name

    private suspend fun finishSignup(formId: String, photoPath: String) {
        if (photoPath.isNotBlank()) runCatching { host.deleteStorage(photoPath) }
        runCatching { gateway.deleteDocument(orgId, VolunteerSignupForm.COLLECTION, formId) }
        host.savePendingSignupForms(host.loadPendingSignupForms().filterNot { it.formId == formId })
    }

    /** The device that created a link removes it once it expired unanswered. */
    private suspend fun cleanupExpiredForms() {
        val now = clock()
        val pending = host.loadPendingSignupForms()
        val expired = pending.filter { it.orgId == orgId && it.isExpiredAt(now) }
        if (expired.isEmpty()) return
        for (form in expired) {
            val status = latestSignupDocs.firstOrNull { it.first == form.formId }?.second?.get("status")?.toString()
            if (status == null || status == VolunteerSignupForm.STATUS_OPEN) {
                runCatching { gateway.deleteDocument(orgId, VolunteerSignupForm.COLLECTION, form.formId) }
            }
        }
        host.savePendingSignupForms(pending - expired.toSet())
    }

    // endregion

    // region Portal requests

    private suspend fun processRequests(docs: List<Pair<String, Map<String, Any?>>>) {
        for ((requestId, data) in docs) {
            val status = data["status"]?.toString().orEmpty()
            if (status != PortalRequest.STATUS_PENDING && !isStaleProcessing(data)) continue
            if (isFreshForServer(data["requestedAt"])) continue
            val type = data["type"]?.toString()
            // A GOOGLE_LINK request only waits for the Google claim that carries its code.
            if (type == PortalRequestType.GOOGLE_LINK.name) continue
            // A CERTIFICATE_EMAIL request is the server's: devices have no PDF engine.
            if (type == PortalRequestType.CERTIFICATE_EMAIL.name) continue
            // Only a device that can actually send mail takes a QR request; others leave it waiting.
            if ((type == PortalRequestType.QR_EMAIL.name || type == PortalRequestType.GUEST_QR_EMAIL.name) && !host.canSendQrEmail()) continue
            // Guest space off: guest requests wait, like Google claims while that option is off.
            if (isGuestRequestType(type) && !host.guestPortalEnabled()) continue
            mutex.withLock { runCatchingLogged("request $requestId") { processRequest(requestId) } }
        }
    }

    /** The server handles new work within seconds; devices only step in after [DEVICE_GRACE_MS]. Unknown age counts as old. */
    private fun isFreshForServer(timestamp: Any?): Boolean {
        val at = firestoreMillisOf(timestamp) ?: return false
        return clock() - at < DEVICE_GRACE_MS
    }

    private suspend fun processRequest(requestId: String) {
        val now = clock()
        val claimed = gateway.claimDocument(
            orgId,
            PortalRequest.COLLECTION,
            requestId,
            claimable = {
                (it["status"] == PortalRequest.STATUS_PENDING || isStaleProcessing(it)) &&
                    it["type"] != PortalRequestType.GOOGLE_LINK.name &&
                    it["type"] != PortalRequestType.CERTIFICATE_EMAIL.name &&
                    (!isGuestRequestType(it["type"]?.toString()) || host.guestPortalEnabled()) &&
                    (it["type"] != PortalRequestType.TEMP_GUEST_NAME.name || host.temporaryGuestPortalEnabled())
            },
            claimFields = processingFields(now),
        ) ?: return
        val request = PortalInputValidator.parseRequest(requestId, claimed)
        if (request == null) {
            complete(requestId, PortalRequest.STATUS_REJECTED, "INVALID")
            return
        }
        val volunteers = host.volunteers().filter { it.firebaseOrgId.isBlank() || it.firebaseOrgId == orgId }
        val owner = volunteers.firstOrNull { it.portalEnabled && it.portalUid == request.uid }
        val guests = orgGuests()
        when (request.type) {
            PortalRequestType.CLAIM -> handleClaim(request, owner, volunteers, guests)
            PortalRequestType.PROFILE -> {
                val uploadPath = portalUploadPath(request.uid)
                try {
                    updateProfile(request, owner, uploadPath, now)
                } finally {
                    if (request.photo) runCatching { host.deleteStorage(uploadPath) }
                }
            }
            PortalRequestType.QR_EMAIL -> {
                val reason = when {
                    owner == null -> "NO_ACCESS"
                    owner.isAdmin -> "NOT_ALLOWED"
                    owner.email.isBlank() -> "NO_EMAIL"
                    else -> null
                }
                if (reason != null) return complete(requestId, PortalRequest.STATUS_REJECTED, reason)
                val sent = host.sendQrEmail(owner!!)
                complete(requestId, if (sent) PortalRequest.STATUS_DONE else PortalRequest.STATUS_REJECTED, if (sent) "" else "SEND_FAILED")
            }
            PortalRequestType.GUEST_PROFILE -> {
                val uploadPath = portalUploadPath(request.uid)
                try {
                    updateGuestProfile(request, guestOwner(request.uid, guests), uploadPath, now)
                } finally {
                    if (request.photo) runCatching { host.deleteStorage(uploadPath) }
                }
            }
            PortalRequestType.GUEST_QR_EMAIL -> {
                val guest = guestOwner(request.uid, guests)
                val reason = when {
                    guest == null -> "NO_ACCESS"
                    guest.isAdmin -> "NOT_ALLOWED"
                    guest.email.isBlank() -> "NO_EMAIL"
                    else -> null
                }
                if (reason != null) return complete(requestId, PortalRequest.STATUS_REJECTED, reason)
                val sent = host.sendGuestQrEmail(guest!!)
                complete(requestId, if (sent) PortalRequest.STATUS_DONE else PortalRequest.STATUS_REJECTED, if (sent) "" else "SEND_FAILED")
            }
            PortalRequestType.GOOGLE_UNLINK -> {
                val account = PortalGoogleLinkProcessor.accountOf(request.uid, googleHolders(volunteers, guests))
                // Clearing never grants access: guests of the account lose the link even with their space off.
                val accountGuests = if (account.isEmpty()) emptyList() else sameAccountGuests(request.uid, guests)
                val everyone = account + accountGuests.map { it.asPortalHolder() }
                // Any admin-held link on the account keeps it, whichever profile carries it.
                val decider = everyone.firstOrNull { it.portalGoogleLinkedBy == Volunteer.GOOGLE_LINKED_BY_ADMIN }
                    ?: account.firstOrNull()?.let { PortalGoogleLinkProcessor.primary(account) }
                val reason = PortalGoogleLinkProcessor.canUnlink(decider)
                if (reason != null) return complete(requestId, PortalRequest.STATUS_REJECTED, reason)
                val ok = clearGoogleLink(
                    volunteers.filter { v -> account.any { it.kind == PortalHolderKind.VOLUNTEER && it.id == v.id } },
                    accountGuests,
                )
                complete(requestId, if (ok) PortalRequest.STATUS_DONE else PortalRequest.STATUS_REJECTED, if (ok) "" else "SAVE_FAILED")
            }
            PortalRequestType.TEMP_GUEST_NAME -> renameTemporaryGuest(request, guests, now)
            // Never claimed (see processRequests): it is answered through its Google claim.
            PortalRequestType.GOOGLE_LINK -> Unit
            // Never claimed either: only the server renders the certificate PDF.
            PortalRequestType.CERTIFICATE_EMAIL -> Unit
        }
    }

    /**
     * Name, last name, phone and, when the request says so, the photo waiting in
     * `portalPhotoUploads/{uid}.jpg`. Same answers as updateProfile in the Cloud Functions.
     */
    private suspend fun updateProfile(request: PortalRequest, owner: Volunteer?, uploadPath: String, now: Long) {
        val requestId = request.requestId
        if (owner == null) return complete(requestId, PortalRequest.STATUS_REJECTED, "NO_ACCESS")
        val photo = if (request.photo) {
            // Photos turned off, nothing uploaded or not a JPEG: the whole edit is refused.
            val bytes = if (host.profilePhotosEnabled()) host.downloadStorage(uploadPath) else null
            if (bytes == null || !isJpeg(bytes)) return complete(requestId, PortalRequest.STATUS_REJECTED, "INVALID")
            bytes
        } else {
            null
        }
        val updated = owner.copy(
            name = request.firstName,
            lastNameAbbreviation = request.lastName.uppercase(),
            phoneNumber = request.phone,
            lastModified = maxOf(now, owner.lastModified + 1),
        )
        val ok = (updated == owner || host.saveVolunteer(updated)) &&
            (photo == null || host.attachVolunteerPhoto(updated, photo))
        complete(requestId, if (ok) PortalRequest.STATUS_DONE else PortalRequest.STATUS_REJECTED, if (ok) "" else "SAVE_FAILED")
    }

    /** Same as [updateProfile], on the permanent guest of the account. */
    private suspend fun updateGuestProfile(request: PortalRequest, owner: Guest?, uploadPath: String, now: Long) {
        val requestId = request.requestId
        if (owner == null) return complete(requestId, PortalRequest.STATUS_REJECTED, "NO_ACCESS")
        val photo = if (request.photo) {
            val bytes = if (host.profilePhotosEnabled()) host.downloadStorage(uploadPath) else null
            if (bytes == null || !isJpeg(bytes)) return complete(requestId, PortalRequest.STATUS_REJECTED, "INVALID")
            bytes
        } else {
            null
        }
        val updated = owner.copy(
            name = request.firstName,
            lastNameAbbreviation = request.lastName.uppercase(),
            phoneNumber = request.phone,
            lastModified = maxOf(now, owner.lastModified + 1),
        )
        val ok = (updated == owner || host.saveGuest(updated)) &&
            (photo == null || host.attachGuestPhoto(updated, photo))
        complete(requestId, if (ok) PortalRequest.STATUS_DONE else PortalRequest.STATUS_REJECTED, if (ok) "" else "SAVE_FAILED")
    }

    private fun isGuestRequestType(type: String?): Boolean =
        type == PortalRequestType.GUEST_PROFILE.name || type == PortalRequestType.GUEST_QR_EMAIL.name

    private suspend fun orgGuests(): List<Guest> =
        host.guests().filter { it.firebaseOrgId.isBlank() || it.firebaseOrgId == orgId }

    /** The published permanent guest of [uid] (most recently modified, as the publisher picks), or null. */
    private fun guestOwner(uid: String, guests: List<Guest>): Guest? {
        if (!host.guestPortalEnabled()) return null
        return guests.filter { it.isPermanentPortalGuest && it.portalEnabled && it.portalUid == uid }.maxByOrNull { it.lastModified }
    }

    /** Volunteers, plus permanent guests while the guest space is on. */
    private fun googleHolders(volunteers: List<Volunteer>, guests: List<Guest>): List<PortalHolder> =
        volunteers.map { it.asPortalHolder() } +
            if (host.guestPortalEnabled()) guests.filter { it.isPermanentPortalGuest }.map { it.asPortalHolder() } else emptyList()

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size in 4 until MAX_UPLOAD_BYTES &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    /**
     * Links to the request's uid every open profile with a blank portal uid and the verified
     * address: volunteers (`email`), permanent guests (`email`, guest space on) and temporary
     * groups (`temporaryPortalEmail`, else `temporaryContactEmail`; their space on). Volunteers keep their own answers (an
     * already linked volunteer is done, two free ones are ambiguous); a guest profile of that
     * address linked to another uid refuses the whole claim.
     */
    private suspend fun handleClaim(request: PortalRequest, owner: Volunteer?, volunteers: List<Volunteer>, guests: List<Guest>) {
        val uid = request.uid
        var volunteerToLink: Volunteer? = null
        if (owner == null) {
            val candidates = volunteers.filter {
                it.portalEnabled && it.email.trim().equals(request.email, ignoreCase = true)
            }
            val free = candidates.filter { it.portalUid.isBlank() }
            if (candidates.isNotEmpty() && free.size != 1) {
                return complete(request.requestId, PortalRequest.STATUS_REJECTED, "LINKED_ELSEWHERE")
            }
            volunteerToLink = free.singleOrNull()
        }
        val guestSpace = host.guestPortalEnabled()
        val tempSpace = host.temporaryGuestPortalEnabled()
        val claimable = guests.filter {
            it.portalEnabled && when {
                it.isTemporaryGuest -> tempSpace
                it.isVolunteerBenefit -> false
                else -> guestSpace
            }
        }
        val guestOwned = claimable.any { it.portalUid == uid }
        val guestTargets = claimable.filter {
            it.portalUid != uid && (if (it.isTemporaryGuest) it.temporaryPortalEmail.ifBlank { it.temporaryContactEmail } else it.email).trim()
                .equals(request.email, ignoreCase = true)
        }
        val foreign = guestTargets.filter { it.portalUid.isNotBlank() }
        // Someone who already has access gets no error: the profile linked elsewhere is just skipped.
        val hasAccess = owner != null || guestOwned
        if (foreign.isNotEmpty() && !hasAccess) {
            return complete(request.requestId, PortalRequest.STATUS_REJECTED, "LINKED_ELSEWHERE")
        }
        val freeGuests = guestTargets - foreign.toSet()
        if (volunteerToLink == null && freeGuests.isEmpty()) {
            return complete(request.requestId, if (hasAccess) PortalRequest.STATUS_DONE else PortalRequest.STATUS_REJECTED, if (hasAccess) "" else "NO_ACCESS")
        }
        var ok = true
        volunteerToLink?.let { v ->
            ok = host.saveVolunteer(v.copy(portalUid = uid, lastModified = maxOf(clock(), v.lastModified + 1))) && ok
        }
        for (g in freeGuests) {
            ok = host.saveGuest(g.copy(portalUid = uid, lastModified = maxOf(clock(), g.lastModified + 1))) && ok
        }
        complete(request.requestId, if (ok) PortalRequest.STATUS_DONE else PortalRequest.STATUS_REJECTED, if (ok) "" else "SAVE_FAILED")
    }

    /**
     * First and last name of one temporary guest of the account, found by its page key among the
     * open temporary guests of the uid. A used entry keeps its name. Same answers as the Functions.
     */
    private suspend fun renameTemporaryGuest(request: PortalRequest, guests: List<Guest>, now: Long) {
        val target = guests.firstOrNull {
            it.isTemporaryGuest && it.portalEnabled && it.portalUid == request.uid &&
                TemporaryGuestPortalQrDocument.guestKey(it) == request.guestKey
        } ?: return complete(request.requestId, PortalRequest.STATUS_REJECTED, "NOT_FOUND")
        if (target.temporaryEntryValidated) return complete(request.requestId, PortalRequest.STATUS_REJECTED, "ENTRY_USED")
        val ok = host.saveGuest(
            target.copy(
                name = request.firstName.ifEmpty { target.name },
                lastNameAbbreviation = request.lastName.uppercase().ifEmpty { target.lastNameAbbreviation },
                lastModified = maxOf(now, target.lastModified + 1),
            ),
        )
        complete(request.requestId, if (ok) PortalRequest.STATUS_DONE else PortalRequest.STATUS_REJECTED, if (ok) "" else "SAVE_FAILED")
    }

    // region Guest list edits (spec guest list access §6.3)

    /** Edits older than this are deleted once answered. */
    private val listEditRetentionMs = 7 * 86_400_000L

    private suspend fun processListEdits(docs: List<Pair<String, Map<String, Any?>>>) {
        val now = clock()
        for ((editId, data) in docs) {
            val status = data["status"]?.toString().orEmpty()
            if (status == "DONE" || status == "REJECTED") {
                val at = firestoreMillisOf(data["processedAt"]) ?: continue
                if (now - at > listEditRetentionMs) runCatching { gateway.deleteDocument(orgId, GuestListEdit.COLLECTION, editId) }
                continue
            }
            if (status != PortalRequest.STATUS_PENDING && !isStaleProcessing(data)) continue
            if (!host.temporaryGuestPortalEnabled()) continue
            mutex.withLock { runCatchingLogged("guest list edit $editId") { processListEdit(editId) } }
        }
    }

    private suspend fun processListEdit(editId: String) {
        val claimed = gateway.claimDocument(
            orgId,
            GuestListEdit.COLLECTION,
            editId,
            claimable = { it["status"] == PortalRequest.STATUS_PENDING || isStaleProcessing(it) },
            claimFields = processingFields(clock()),
        ) ?: return
        val edit = GuestListEdit.parse(editId, claimed)
        val (status, reason) = if (edit == null) {
            GuestListEditProcessor.REJECTED to "INVALID"
        } else {
            listEditProcessor.apply(
                edit,
                host.volunteers().filter { it.firebaseOrgId.isBlank() || it.firebaseOrgId == orgId },
                orgGuests(),
            )
        }
        gateway.setPlainDocument(
            orgId,
            GuestListEdit.COLLECTION,
            editId,
            mapOf("status" to status, "reason" to reason, "processedAt" to clock()),
            merge = true,
        )
    }

    // endregion

    private suspend fun complete(requestId: String, status: String, reason: String) {
        gateway.setPlainDocument(
            orgId,
            PortalRequest.COLLECTION,
            requestId,
            mapOf("status" to status, "reason" to reason, "processedAt" to clock()),
            merge = true,
        )
    }

    // endregion

    // region Google account links

    private suspend fun processClaims(docs: List<Pair<String, Map<String, Any?>>>) {
        // Option off: claims wait, as in the Cloud Functions; turning it back on resumes them.
        if (docs.isEmpty() || !host.portalGoogleEnabled()) return
        for ((googleUid, data) in docs) {
            val status = data["status"]?.toString().orEmpty()
            if (status != PortalRequest.STATUS_PENDING && !isStaleProcessing(data)) continue
            if (isFreshForServer(data["requestedAt"])) continue
            mutex.withLock { runCatchingLogged("google claim") { processClaim(googleUid) } }
        }
    }

    private suspend fun processClaim(googleUid: String) {
        val now = clock()
        val claimed = gateway.claimDocument(
            orgId,
            PortalGoogleClaim.COLLECTION,
            googleUid,
            claimable = { it["status"] == PortalRequest.STATUS_PENDING || isStaleProcessing(it) },
            claimFields = processingFields(now),
        ) ?: return
        val claim = PortalInputValidator.parseGoogleClaim(googleUid, claimed)
            ?: return completeClaim(googleUid, PortalRequest.STATUS_REJECTED, "INVALID")
        val volunteers = host.volunteers().filter { it.firebaseOrgId.isBlank() || it.firebaseOrgId == orgId }
        val guests = orgGuests()
        val holders = googleHolders(volunteers, guests)
        val domains = host.portalGoogleDomains()
        val anyAccount = host.portalGoogleAnyAccount()
        var aliasTarget = gateway.getDocumentFromServer(orgId, PortalGoogleAccount.COLLECTION, googleUid)
            ?.get("volunteerUid") as? String
        if (aliasTarget != null && !PortalGoogleLinkProcessor.isAliasValid(googleUid, aliasTarget, holders)) {
            // Revoked, deleted or relinked volunteer: the alias must not keep answering for them.
            gateway.deleteDocument(orgId, PortalGoogleAccount.COLLECTION, googleUid)
            aliasTarget = null
        }
        var linkRequestId: String? = null
        val decision = when (claim.type) {
            PortalGoogleClaimType.ADMIN_MATCH ->
                PortalGoogleLinkProcessor.adminMatch(claim, holders, domains, aliasTarget, anyAccount)
            PortalGoogleClaimType.LINK_CODE -> {
                val hash = PortalProjectionBuilder.sha256(claim.code)
                val found = latestRequestDocs.firstOrNull { (_, data) ->
                    data["type"] == PortalRequestType.GOOGLE_LINK.name && data["codeHash"] == hash
                }
                linkRequestId = found?.first
                val data = found?.let { (id, snapshot) ->
                    gateway.getDocumentFromServer(orgId, PortalRequest.COLLECTION, id) ?: snapshot
                }
                val request = if (found != null && data != null) PortalInputValidator.parseRequest(found.first, data) else null
                PortalGoogleLinkProcessor.linkCode(
                    claim, request, data?.let { firestoreMillisOf(it["requestedAt"]) }, now, holders, domains, aliasTarget, anyAccount,
                )
            }
        }
        when (decision) {
            is GoogleLinkDecision.Reject -> completeClaim(googleUid, PortalRequest.STATUS_REJECTED, decision.reason)
            GoogleLinkDecision.AlreadyLinked -> {
                completeClaim(googleUid, PortalRequest.STATUS_DONE, "")
                linkRequestId?.let { complete(it, PortalRequest.STATUS_DONE, "") }
            }
            is GoogleLinkDecision.Link -> {
                val account = PortalGoogleLinkProcessor.accountOf(decision.holder.portalUid, holders)
                val ok = writeGoogleLink(
                    decision.holder.portalUid,
                    volunteers.filter { v -> account.any { it.kind == PortalHolderKind.VOLUNTEER && it.id == v.id } },
                    guests.filter { g -> account.any { it.kind == PortalHolderKind.GUEST && it.id == g.nanoId } },
                    googleUid, claim.email, decision.linkedBy, now,
                )
                completeClaim(googleUid, if (ok) PortalRequest.STATUS_DONE else PortalRequest.STATUS_REJECTED, if (ok) "" else "SAVE_FAILED")
                if (ok) linkRequestId?.let { complete(it, PortalRequest.STATUS_DONE, "") }
            }
        }
    }

    /**
     * Alias first, then every profile of the account [portalUid]: a failed save leaves no alias
     * behind. A previous Google account of the account loses its alias. Republished by the flows.
     */
    private suspend fun writeGoogleLink(
        portalUid: String,
        volunteers: List<Volunteer>,
        guests: List<Guest>,
        googleUid: String,
        email: String,
        linkedBy: String,
        now: Long,
    ): Boolean {
        val previousUids = (volunteers.map { it.portalGoogleUid } + guests.map { it.portalGoogleUid })
            .filter { it.isNotBlank() && it != googleUid }
            .distinct()
        for (previousUid in previousUids) {
            val previousTarget = gateway.getDocumentFromServer(orgId, PortalGoogleAccount.COLLECTION, previousUid)
                ?.get("volunteerUid") as? String
            if (previousTarget == portalUid) gateway.deleteDocument(orgId, PortalGoogleAccount.COLLECTION, previousUid)
        }
        gateway.setPlainDocument(
            orgId,
            PortalGoogleAccount.COLLECTION,
            googleUid,
            mapOf("volunteerUid" to portalUid, "email" to email, "linkedBy" to linkedBy, "linkedAt" to now),
            merge = false,
        )
        var saved = true
        for (volunteer in volunteers) {
            saved = host.saveVolunteer(
                volunteer.copy(
                    portalGoogleEmail = volunteer.portalGoogleEmail.trim().ifBlank { email },
                    portalGoogleUid = googleUid,
                    portalGoogleLinkedBy = linkedBy,
                    lastModified = maxOf(now, volunteer.lastModified + 1),
                ),
            ) && saved
        }
        for (guest in guests) {
            saved = host.saveGuest(
                guest.copy(
                    portalGoogleEmail = guest.portalGoogleEmail.trim().ifBlank { email },
                    portalGoogleUid = googleUid,
                    portalGoogleLinkedBy = linkedBy,
                    lastModified = maxOf(now, guest.lastModified + 1),
                ),
            ) && saved
        }
        if (!saved) runCatching { gateway.deleteDocument(orgId, PortalGoogleAccount.COLLECTION, googleUid) }
        return saved
    }

    /** Answers the claim and blanks the one-time code, whatever the outcome. */
    private suspend fun completeClaim(googleUid: String, status: String, reason: String) {
        gateway.setPlainDocument(
            orgId,
            PortalGoogleClaim.COLLECTION,
            googleUid,
            mapOf("status" to status, "reason" to reason, "processedAt" to clock(), "code" to ""),
            merge = true,
        )
    }

    /** Deletes the aliases (so the Google account loses access first), then blanks the three fields on each profile. */
    private suspend fun clearGoogleLink(volunteers: List<Volunteer>, guests: List<Guest>): Boolean {
        (volunteers.map { it.portalGoogleUid } + guests.map { it.portalGoogleUid })
            .filter { it.isNotBlank() }
            .distinct()
            .forEach { gateway.deleteDocument(orgId, PortalGoogleAccount.COLLECTION, it) }
        var ok = true
        for (volunteer in volunteers) {
            val cleared = volunteer.copy(
                portalGoogleEmail = "",
                portalGoogleUid = "",
                portalGoogleLinkedBy = "",
                lastModified = maxOf(clock(), volunteer.lastModified + 1),
            )
            ok = (cleared == volunteer || host.saveVolunteer(cleared)) && ok
        }
        for (guest in guests) {
            val cleared = guest.copy(
                portalGoogleEmail = "",
                portalGoogleUid = "",
                portalGoogleLinkedBy = "",
                lastModified = maxOf(clock(), guest.lastModified + 1),
            )
            ok = (cleared == guest || host.saveGuest(cleared)) && ok
        }
        return ok
    }

    /**
     * Permanent guests on the portal account [portalUid], whatever the guest space setting: the
     * admin's Google changes and removals must reach them, or a stale link comes back with the space.
     */
    private fun sameAccountGuests(portalUid: String, guests: List<Guest>): List<Guest> =
        if (portalUid.isBlank()) emptyList()
        else guests.filter { it.isPermanentPortalGuest && it.portalUid == portalUid }

    /**
     * Admin types the volunteer's association Google address. Null when saved, else INVALID,
     * DOMAIN, SAME_AS_EMAIL, TAKEN or SAVE_FAILED. A different address drops the previous link.
     */
    suspend fun assignGoogleAccount(volunteer: Volunteer, email: String): String? {
        val clean = PortalInputValidator.cleanEmail(email) ?: return "INVALID"
        if (!PortalGoogleLinkProcessor.accountAllowed(clean, host.portalGoogleDomains(), host.portalGoogleAnyAccount())) return "DOMAIN"
        val all = host.volunteers().filter { it.firebaseOrgId.isBlank() || it.firebaseOrgId == orgId }
        val current = all.firstOrNull { it.id == volunteer.id } ?: volunteer
        // The roster address already signs in with a password; the Google one must be the association's.
        if (PortalGoogleLinkProcessor.sameAddress(current.email, clean)) return "SAME_AS_EMAIL"
        if (all.any { it.id != volunteer.id && PortalGoogleLinkProcessor.sameAddress(it.portalGoogleEmail, clean) }) return "TAKEN"
        // A permanent guest of another account holding the address would make the match ambiguous.
        val guests = orgGuests()
        val accountGuests = sameAccountGuests(current.portalUid, guests)
        val ownGuests = accountGuests.map { it.nanoId }.toSet()
        if (host.guestPortalEnabled() && guests.any {
                it.isPermanentPortalGuest && it.nanoId !in ownGuests && PortalGoogleLinkProcessor.sameAddress(it.portalGoogleEmail, clean)
            }
        ) return "TAKEN"
        // Same person, same Google account: the guests of the account follow the volunteer.
        return writeAdminGoogleAddress(current.asPortalHolder(), listOf(current), accountGuests, clean)
    }

    /**
     * Admin types the permanent guest's association Google address; same answers as
     * [assignGoogleAccount]. The volunteer and other guests of the same portal account follow.
     */
    suspend fun assignGuestGoogleAccount(guest: Guest, email: String): String? {
        val clean = PortalInputValidator.cleanEmail(email) ?: return "INVALID"
        if (!PortalGoogleLinkProcessor.accountAllowed(clean, host.portalGoogleDomains(), host.portalGoogleAnyAccount())) return "DOMAIN"
        val guests = orgGuests()
        val current = guests.firstOrNull { it.nanoId == guest.nanoId } ?: guest
        if (PortalGoogleLinkProcessor.sameAddress(current.email, clean)) return "SAME_AS_EMAIL"
        val (accountVolunteers, accountGuests) = accountOf(current, guests)
        val ownVolunteers = accountVolunteers.map { it.id }.toSet()
        val ownGuests = accountGuests.map { it.nanoId }.toSet()
        if (orgVolunteers().any { it.id !in ownVolunteers && PortalGoogleLinkProcessor.sameAddress(it.portalGoogleEmail, clean) }) return "TAKEN"
        if (guests.any {
                it.isPermanentPortalGuest && it.nanoId !in ownGuests && PortalGoogleLinkProcessor.sameAddress(it.portalGoogleEmail, clean)
            }
        ) return "TAKEN"
        return writeAdminGoogleAddress(current.asPortalHolder(), accountVolunteers, accountGuests, clean)
    }

    /**
     * Saves [clean] as the admin-held Google address of every given profile ([current] among them).
     * Every former Google account of the person loses its alias; the same account keeps its link,
     * now held by the admin. Null when saved, else SAVE_FAILED.
     */
    private suspend fun writeAdminGoogleAddress(
        current: PortalHolder,
        volunteers: List<Volunteer>,
        guests: List<Guest>,
        clean: String,
    ): String? {
        val sameAddress = PortalGoogleLinkProcessor.sameAddress(current.portalGoogleEmail, clean)
        return try {
            val keptUid = if (sameAddress) current.portalGoogleUid else ""
            (volunteers.map { it.portalGoogleUid } + guests.map { it.portalGoogleUid })
                .filter { it.isNotBlank() && it != keptUid }
                .distinct()
                .forEach { gateway.deleteDocument(orgId, PortalGoogleAccount.COLLECTION, it) }
            if (keptUid.isNotBlank() && current.portalGoogleLinkedBy != Volunteer.GOOGLE_LINKED_BY_ADMIN) {
                // Same account, now held by the admin: the person can no longer remove it.
                gateway.setPlainDocument(
                    orgId, PortalGoogleAccount.COLLECTION, keptUid,
                    mapOf("linkedBy" to Volunteer.GOOGLE_LINKED_BY_ADMIN), merge = true,
                )
            }
            var ok = true
            for (volunteer in volunteers) {
                val updated = volunteer.copy(
                    portalGoogleEmail = clean,
                    portalGoogleUid = keptUid,
                    portalGoogleLinkedBy = Volunteer.GOOGLE_LINKED_BY_ADMIN,
                    lastModified = maxOf(clock(), volunteer.lastModified + 1),
                )
                ok = (updated == volunteer || host.saveVolunteer(updated)) && ok
            }
            for (guest in guests) {
                val follows = guest.copy(
                    portalGoogleEmail = clean,
                    portalGoogleUid = keptUid,
                    portalGoogleLinkedBy = Volunteer.GOOGLE_LINKED_BY_ADMIN,
                    lastModified = maxOf(clock(), guest.lastModified + 1),
                )
                ok = (follows == guest || host.saveGuest(follows)) && ok
            }
            if (ok) null else "SAVE_FAILED"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Portal: Google address save failed: ${e.message}")
            "SAVE_FAILED"
        }
    }

    /** [guest] and every volunteer and permanent guest sharing its portal account. */
    private suspend fun accountOf(guest: Guest, guests: List<Guest>): Pair<List<Volunteer>, List<Guest>> {
        val uid = guest.portalUid
        if (uid.isBlank()) return emptyList<Volunteer>() to listOf(guest)
        return orgVolunteers().filter { it.portalUid == uid } to
            (listOf(guest) + sameAccountGuests(uid, guests).filter { it.nanoId != guest.nanoId })
    }

    private suspend fun orgVolunteers(): List<Volunteer> =
        host.volunteers().filter { it.firebaseOrgId.isBlank() || it.firebaseOrgId == orgId }

    /** Who else still uses the portal account [uid] once the profile [kind]/[id] is gone. */
    private data class AccountSharing(
        /** Open volunteers and permanent guests of the account: Google link and account photo. */
        val holders: List<PortalHolder>,
        /** An open temporary group signs in with the same account. */
        val temporaryGroups: Boolean,
    ) {
        /** Requests and uploads of the account itself (CLAIM, Google, photo upload) are still in use. */
        val inUse: Boolean get() = holders.isNotEmpty() || temporaryGroups

        fun carries(googleUid: String): Boolean = holders.any { it.portalGoogleUid == googleUid }
    }

    private suspend fun othersOnAccount(uid: String, kind: PortalHolderKind, id: String): AccountSharing {
        if (uid.isBlank()) return AccountSharing(emptyList(), false)
        val guests = orgGuests().filter { it.portalEnabled && it.portalUid == uid }
        val holders = orgVolunteers().filter { it.portalEnabled && it.portalUid == uid }.map { it.asPortalHolder() } +
            guests.filter { it.isPermanentPortalGuest }.map { it.asPortalHolder() }
        return AccountSharing(
            holders = holders.filterNot { it.kind == kind && it.id == id },
            temporaryGroups = guests.any { it.isTemporaryGuest },
        )
    }

    /**
     * A portal access was just opened: links the Google address built by the team's rule, if the
     * rule is on and the volunteer has none yet. Never fails the caller; a refused address
     * (taken, wrong domain, unusable name) leaves the field empty for the admin to fill in.
     */
    private suspend fun applyGoogleAddressRule(volunteerId: String) =
        applyGoogleAddressRule(PortalHolderKind.VOLUNTEER, volunteerId)

    /** Same rule for a permanent guest ([id] is then its nanoId, never logged). */
    private suspend fun applyGoogleAddressRule(kind: PortalHolderKind, id: String) {
        if (!host.portalGoogleEnabled()) return
        val template = host.portalGoogleAddressTemplate().trim()
        if (template.isEmpty()) return
        runCatchingLogged("Google address rule") {
            val who = if (kind == PortalHolderKind.GUEST) "guest" else "volunteer"
            val volunteer = if (kind == PortalHolderKind.VOLUNTEER) host.volunteers().firstOrNull { it.id == id } else null
            val guest = if (kind == PortalHolderKind.GUEST) orgGuests().firstOrNull { it.nanoId == id } else null
            val name = volunteer?.name ?: guest?.name ?: return@runCatchingLogged
            val lastName = volunteer?.lastNameAbbreviation ?: guest?.lastNameAbbreviation.orEmpty()
            if ((volunteer?.portalGoogleEmail ?: guest?.portalGoogleEmail).orEmpty().isNotBlank()) return@runCatchingLogged
            val address = PortalGoogleAddressTemplate.render(template, name, lastName)
            if (address == null) {
                println("Portal: Google address rule gave no address for a $who")
                return@runCatchingLogged
            }
            // Never a guessed address outside the verified domains, even when any account may sign in.
            if (!PortalGoogleAddressTemplate.usesVerifiedDomain(address, host.portalGoogleDomains())) {
                println("Portal: Google address rule refused: DOMAIN")
                return@runCatchingLogged
            }
            val refused = if (volunteer != null) assignGoogleAccount(volunteer, address) else assignGuestGoogleAccount(guest!!, address)
            refused?.let { println("Portal: Google address rule refused: $it") }
        }
    }

    /**
     * The volunteer was deleted: removes what the portal kept for them right away, without waiting
     * for the next publish, which only cleans pages this device published itself. Best effort per
     * item; a failed QR delete is left to the next publish.
     */
    suspend fun forgetVolunteer(volunteer: Volunteer) {
        if (volunteer.firebaseOrgId.isNotBlank() && volunteer.firebaseOrgId != orgId) return
        // A permanent guest on the same account keeps what it still uses (alias, photo, shared requests).
        val others = othersOnAccount(volunteer.portalUid.trim(), PortalHolderKind.VOLUNTEER, volunteer.id)
        val googleUid = volunteer.portalGoogleUid.trim()
        if (googleUid.isNotBlank() && !others.carries(googleUid)) {
            // Alias first, so the Google account loses access before anything else.
            runCatchingLogged("Google alias delete") { gateway.deleteDocument(orgId, PortalGoogleAccount.COLLECTION, googleUid) }
            runCatchingLogged("Google claim delete") { gateway.deleteDocument(orgId, PortalGoogleClaim.COLLECTION, googleUid) }
        }
        val uid = volunteer.portalUid.trim()
        if (uid.isBlank()) return
        mutex.withLock {
            val key = "$orgId/$uid"
            val qrKey = "$key$QR_SUFFIX"
            val hashes = host.loadPublishedHashes().toMutableMap()
            try {
                gateway.deleteDocument(orgId, PortalProjection.COLLECTION, uid)
                hashes.remove(key)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The key stays, so the next publish sees a stale page and deletes it.
                println("Portal: page delete failed for a deleted volunteer: ${e.message}")
            }
            if (others.holders.isEmpty()) {
                runCatchingLogged("portal photo delete") { host.deleteStorage(portalPhotoPath(uid)) }
                hashes.remove("$key#photo")
            }
            if (!others.inUse) runCatchingLogged("portal upload delete") { host.deleteStorage(portalUploadPath(uid)) }
            try {
                gateway.deleteDocument(orgId, PortalQrDocument.COLLECTION, uid)
                hashes.remove(qrKey)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hashes[qrKey] = QR_RETRY
                println("Portal: QR delete failed for a deleted volunteer: ${e.message}")
            }
            // Old requests keep the e-mail, name and phone the volunteer sent.
            for (type in PortalRequestType.entries) {
                if (others.inUse && type != PortalRequestType.PROFILE && type != PortalRequestType.QR_EMAIL) continue
                runCatchingLogged("request delete") {
                    gateway.deleteDocument(orgId, PortalRequest.COLLECTION, "${uid}_${type.name}")
                }
            }
            host.savePublishedHashes(hashes)
        }
    }

    /** Admin "Remove": drops the link whoever made it. */
    suspend fun removeGoogleAccount(volunteer: Volunteer): Boolean {
        val current = host.volunteers().firstOrNull { it.id == volunteer.id } ?: volunteer
        return try {
            clearGoogleLink(listOf(current), sameAccountGuests(current.portalUid, orgGuests()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Portal: Google account removal failed: ${e.message}")
            false
        }
    }

    // endregion

    // region Admin actions

    /** Opens the access without e-mailing the volunteer: the admin sends the reset link from the dialog if wanted. */
    suspend fun grantAccess(volunteer: Volunteer): PortalGrantResult =
        openAccess(volunteer.asPortalHolder()) { uid ->
            host.saveVolunteer(volunteer.copy(portalEnabled = true, portalUid = uid, portalRevoked = false, lastModified = clock()))
        }

    /**
     * Same as [grantAccess] for a permanent guest, with the guest space on. Nothing is mailed.
     * Failed with GUEST_SPACE_OFF, NOT_PERMANENT (temporary guest or volunteer benefit) or INVALID_EMAIL.
     */
    suspend fun grantGuestAccess(guest: Guest): PortalGrantResult {
        if (!host.guestPortalEnabled()) return PortalGrantResult.Failed("GUEST_SPACE_OFF")
        if (!guest.isPermanentPortalGuest) return PortalGrantResult.Failed("NOT_PERMANENT")
        // The address already has a linked account (the guest itself or a volunteer of the same
        // address): use it. The portal sends no CLAIM for a session that already shows a profile.
        val email = PortalInputValidator.cleanEmail(guest.email)
        if (email != null) {
            val linkedUid = linkedUidOf(email, guest)
            if (linkedUid != null) {
                val current = orgGuests().firstOrNull { it.nanoId == guest.nanoId } ?: guest
                val opened = current.copy(portalEnabled = true, portalUid = linkedUid, portalRevoked = false, lastModified = maxOf(clock(), current.lastModified + 1))
                if (opened != current && !host.saveGuest(opened)) return PortalGrantResult.Failed("SAVE_FAILED")
                applyGoogleAddressRule(PortalHolderKind.GUEST, guest.nanoId)
                return PortalGrantResult.ExistingAccount
            }
        }
        return openAccess(guest.asPortalHolder()) { uid ->
            host.saveGuest(guest.copy(portalEnabled = true, portalUid = uid, portalRevoked = false, lastModified = maxOf(clock(), guest.lastModified + 1)))
        }
    }

    /** Portal uid already linked to [email] in this organization: [guest] itself first, then volunteers and other permanent guests. */
    private suspend fun linkedUidOf(email: String, guest: Guest): String? {
        val guests = orgGuests()
        val self = guests.firstOrNull { it.nanoId == guest.nanoId } ?: guest
        if (self.portalUid.isNotBlank() && PortalGoogleLinkProcessor.sameAddress(self.email, email)) return self.portalUid
        orgVolunteers().firstOrNull { it.portalUid.isNotBlank() && PortalGoogleLinkProcessor.sameAddress(it.email, email) }
            ?.let { return it.portalUid }
        return guests.firstOrNull {
            it.isPermanentPortalGuest && it.portalUid.isNotBlank() && PortalGoogleLinkProcessor.sameAddress(it.email, email)
        }?.portalUid
    }

    /**
     * Creates the Firebase account of [holder] and saves the profile through [save] with its uid,
     * or a blank uid when the address already has an account (linked at sign-in by CLAIM). Then
     * applies the Google address rule. No e-mail is sent.
     */
    private suspend fun openAccess(holder: PortalHolder, save: suspend (portalUid: String) -> Boolean): PortalGrantResult {
        val email = holder.email.trim()
        if (PortalInputValidator.cleanEmail(email) == null) return PortalGrantResult.Failed("INVALID_EMAIL")
        val client = authClientFactory(host.apiKey())
        val password = PortalPasswordGenerator.generate()
        return when (val created = client.createAccount(email, password)) {
            is PortalAuthResult.Created -> {
                if (!save(created.uid)) return PortalGrantResult.Failed("SAVE_FAILED")
                applyGoogleAddressRule(holder.kind, holder.id)
                PortalGrantResult.Created(password)
            }
            PortalAuthResult.EmailExists -> {
                if (!save("")) return PortalGrantResult.Failed("SAVE_FAILED")
                applyGoogleAddressRule(holder.kind, holder.id)
                PortalGrantResult.ExistingAccount
            }
            is PortalAuthResult.Failed -> PortalGrantResult.Failed(created.code)
            PortalAuthResult.Sent -> PortalGrantResult.Failed("UNEXPECTED")
        }
    }

    suspend fun sendPasswordReset(volunteer: Volunteer, languageCode: String): Boolean =
        authClientFactory(host.apiKey()).sendPasswordReset(volunteer.email, languageCode) is PortalAuthResult.Sent

    suspend fun sendGuestPasswordReset(guest: Guest, languageCode: String): Boolean =
        authClientFactory(host.apiKey()).sendPasswordReset(guest.email, languageCode) is PortalAuthResult.Sent

    /**
     * Closes the guest's access and takes its page and QR document down. What the account still
     * uses for a volunteer or another guest on it (Google alias, account photo) stays.
     */
    suspend fun revokeGuestAccess(guest: Guest): Boolean {
        if (!guest.isPermanentPortalGuest) return false
        val current = orgGuests().firstOrNull { it.nanoId == guest.nanoId } ?: guest
        val uid = current.portalUid
        val googleUid = current.portalGoogleUid
        val others = othersOnAccount(uid, PortalHolderKind.GUEST, current.nanoId)
        if (googleUid.isNotBlank() && !others.carries(googleUid)) {
            runCatching { gateway.deleteDocument(orgId, PortalGoogleAccount.COLLECTION, googleUid) }
        }
        // Same rule as revokeAccess: only a link the person made goes; an address the admin typed
        // stays, so reopening the access links the same account again.
        val selfLink = current.portalGoogleLinkedBy == Volunteer.GOOGLE_LINKED_BY_VOLUNTEER
        val saved = host.saveGuest(
            current.copy(
                portalEnabled = false,
                portalUid = "",
                portalRevoked = true,
                portalGoogleUid = "",
                portalGoogleEmail = if (selfLink) "" else current.portalGoogleEmail,
                portalGoogleLinkedBy = if (selfLink) "" else current.portalGoogleLinkedBy,
                lastModified = maxOf(clock(), current.lastModified + 1),
            ),
        )
        if (uid.isNotBlank()) mutex.withLock { unpublishGuest(uid, others) }
        return saved
    }

    /**
     * The permanent guest was deleted: same as [forgetVolunteer] for its page, QR document and
     * guest requests. The account's own documents (Google alias, photo, CLAIM and Google requests,
     * upload) go only when nobody else uses the account.
     */
    suspend fun forgetGuest(guest: Guest) {
        if (guest.firebaseOrgId.isNotBlank() && guest.firebaseOrgId != orgId) return
        if (!guest.isPermanentPortalGuest) return
        val uid = guest.portalUid.trim()
        val others = othersOnAccount(uid, PortalHolderKind.GUEST, guest.nanoId)
        val googleUid = guest.portalGoogleUid.trim()
        if (googleUid.isNotBlank() && !others.carries(googleUid)) {
            runCatchingLogged("Google alias delete") { gateway.deleteDocument(orgId, PortalGoogleAccount.COLLECTION, googleUid) }
            runCatchingLogged("Google claim delete") { gateway.deleteDocument(orgId, PortalGoogleClaim.COLLECTION, googleUid) }
        }
        if (uid.isBlank()) return
        mutex.withLock {
            unpublishGuest(uid, others)
            if (!others.inUse) runCatchingLogged("portal upload delete") { host.deleteStorage(portalUploadPath(uid)) }
            val otherGuest = others.holders.any { it.kind == PortalHolderKind.GUEST }
            for (type in PortalRequestType.entries) {
                // Guest requests stay for another permanent guest of the uid; the account's own while anyone uses it.
                if (if (isGuestRequestType(type.name)) otherGuest else others.inUse) continue
                runCatchingLogged("request delete") { gateway.deleteDocument(orgId, PortalRequest.COLLECTION, "${uid}_${type.name}") }
            }
        }
    }

    /** Call under [mutex]. Another permanent guest of the account keeps the page; any holder keeps the photo. */
    private suspend fun unpublishGuest(uid: String, others: AccountSharing) {
        val hashes = host.loadPublishedHashes().toMutableMap()
        guestPublisher.unpublishGuest(
            uid,
            hashes,
            removePage = others.holders.none { it.kind == PortalHolderKind.GUEST },
            removePhoto = others.holders.isEmpty(),
        )
        host.savePublishedHashes(hashes)
    }

    /** Admin "Remove" on a guest: drops the link of the whole portal account, whoever made it. */
    suspend fun removeGuestGoogleAccount(guest: Guest): Boolean {
        val guests = orgGuests()
        val current = guests.firstOrNull { it.nanoId == guest.nanoId } ?: guest
        return try {
            val (volunteers, accountGuests) = accountOf(current, guests)
            clearGoogleLink(volunteers, accountGuests)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Portal: Google account removal failed: ${e.message}")
            false
        }
    }

    suspend fun revokeAccess(volunteer: Volunteer): Boolean {
        val uid = volunteer.portalUid
        val googleUid = volunteer.portalGoogleUid
        // A permanent guest on the same account keeps its Google alias and the account photo.
        val others = othersOnAccount(uid, PortalHolderKind.VOLUNTEER, volunteer.id)
        if (googleUid.isNotBlank() && !others.carries(googleUid)) runCatching { gateway.deleteDocument(orgId, PortalGoogleAccount.COLLECTION, googleUid) }
        // An address the admin typed stays, so reopening the access links the same account again.
        val volunteerLink = volunteer.portalGoogleLinkedBy == Volunteer.GOOGLE_LINKED_BY_VOLUNTEER
        val saved = host.saveVolunteer(
            volunteer.copy(
                portalEnabled = false,
                portalUid = "",
                portalRevoked = true,
                portalGoogleUid = "",
                portalGoogleEmail = if (volunteerLink) "" else volunteer.portalGoogleEmail,
                portalGoogleLinkedBy = if (volunteerLink) "" else volunteer.portalGoogleLinkedBy,
                lastModified = clock(),
            ),
        )
        if (uid.isNotBlank()) unpublish(uid, keepPhoto = others.holders.isNotEmpty())
        return saved
    }

    /** Portal turned off: remove every projection this device published. */
    suspend fun unpublishAll() = mutex.withLock {
        val hashes = host.loadPublishedHashes()
        hashes.keys.filter { it.startsWith("$orgId/") && !it.contains("#") }
            .map { it.removePrefix("$orgId/") }
            .forEach { uid -> runCatching { unpublish(uid) } }
        val guestHashes = host.loadPublishedHashes().toMutableMap()
        guestPublisher.unpublishAll(guestHashes)
        host.savePublishedHashes(guestHashes)
        runCatching { gateway.deleteDocument(orgId, BRANDING_COLLECTION, BRANDING_DOC) }
        runCatching { gateway.deleteDocument(orgId, MAIL_COLLECTION, MAIL_DOC) }
        runCatching { gateway.deleteDocument(orgId, MAIL_COLLECTION, GUEST_MAIL_DOC) }
        // Failed QR deletes stay so the next publish retries them.
        host.savePublishedHashes(
            host.loadPublishedHashes().filterKeys {
                (!it.startsWith("$orgId/") || it.endsWith(QR_SUFFIX) || it.endsWith(GuestPortalPublisher.GUEST_QR_SUFFIX) ||
                    it.endsWith(GuestPortalPublisher.TEMP_QR_SUFFIX)) && it != brandingKey && it != mailKey &&
                    it != guestMailKey
            },
        )
    }

    // endregion

    // region Projection publishing

    private data class Snapshot(
        val volunteers: List<Volunteer>,
        val guests: List<Guest>,
        val jobs: List<Job>,
        val transfers: List<AccountTransfer>,
        val configs: List<JobTypeConfig>,
        val guestForms: List<GuestForm> = emptyList(),
    )

    private val _addressConflicts = MutableStateFlow<Set<String>>(emptySet())

    /** Addresses linked to two portal accounts (spec 2026-11-21 §5.2), shown on the access cards. */
    val addressConflicts: StateFlow<Set<String>> = _addressConflicts.asStateFlow()

    /**
     * One address, one account: joins every profile of an address to the account it already has,
     * and lets a profile whose address changed leave a shared account. Returns the snapshot with the
     * saved profiles, so this pass already publishes them.
     */
    private suspend fun mergeAccounts(snapshot: Snapshot): Snapshot {
        fun inOrg(id: String) = id.isBlank() || id == orgId
        val plan = PortalAccountMerger.plan(
            snapshot.volunteers.filter { inOrg(it.firebaseOrgId) },
            snapshot.guests.filter { inOrg(it.firebaseOrgId) },
            host.guestPortalEnabled(),
            host.temporaryGuestPortalEnabled(),
            clock(),
        )
        _addressConflicts.value = plan.conflicts
        if (plan.volunteers.isEmpty() && plan.guests.isEmpty()) return snapshot
        val savedV = plan.volunteers.map { it.copy(lastModified = maxOf(clock(), it.lastModified + 1)) }
            .filter { host.saveVolunteer(it) }.associateBy { it.id }
        val savedG = plan.guests.map { it.copy(lastModified = maxOf(clock(), it.lastModified + 1)) }
            .filter { host.saveGuest(it) }.associateBy { it.nanoId }
        return snapshot.copy(
            volunteers = snapshot.volunteers.map { savedV[it.id] ?: it },
            guests = snapshot.guests.map { savedG[it.nanoId] ?: it },
        )
    }

    private suspend fun publishAll(given: Snapshot) = mutex.withLock {
        val snapshot = mergeAccounts(given)
        val now = clock()
        val hashes = host.loadPublishedHashes().toMutableMap()
        publishBranding(hashes)
        publishMailTemplate(hashes)
        val googleLinkAllowed = googleLoginOn()
        val linked = snapshot.volunteers.filter {
            it.portalEnabled && it.portalUid.isNotBlank() &&
                (it.firebaseOrgId.isBlank() || it.firebaseOrgId == orgId)
        }
        val jobsByVolunteer = snapshot.jobs.groupBy { it.volunteerId }
        val transfersByHolder = snapshot.transfers.groupBy { it.holderId }
        val keep = mutableSetOf<String>()
        // §6.3: a volunteer without a photo leaves the account photo to the permanent guest.
        val guestPhotoUids = guestPublisher.publishPhotos(snapshot.guests, snapshot.volunteers, hashes)
        for (volunteer in linked) {
            val key = "$orgId/${volunteer.portalUid}"
            keep += key
            val photoKey = "$key#photo"
            val photoVersion = photoVersionOf(volunteer)
            if (volunteer.portalUid !in guestPhotoUids && photoVersion != (hashes[photoKey] ?: "")) {
                if (publishPhoto(volunteer, photoVersion)) {
                    if (photoVersion.isBlank()) hashes.remove(photoKey) else hashes[photoKey] = photoVersion
                }
            }
            val projection = PortalProjectionBuilder.build(
                PortalProjectionInput(
                    volunteer = volunteer,
                    jobs = jobsByVolunteer[volunteer.id].orEmpty(),
                    transfers = transfersByHolder[volunteer.id].orEmpty(),
                    jobTypeConfigs = snapshot.configs,
                    currencyCode = host.currencyCode(),
                    associationName = host.associationName(),
                    hasPhoto = (hashes[photoKey] ?: "").isNotBlank(),
                    photoVersion = hashes[photoKey] ?: "",
                    mustSetPassword = false,
                    googleLinkAllowed = googleLinkAllowed,
                    now = now,
                    offsetHours = host.dateOffsetHours(),
                ),
            )
            if (hashes[key] != projection.contentHash) try {
                gateway.setPlainDocument(
                    orgId,
                    PortalProjection.COLLECTION,
                    volunteer.portalUid,
                    projection.toFirestoreMap(now),
                    merge = false,
                )
                hashes[key] = projection.contentHash
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("Portal: publish failed for one volunteer: ${e.message}")
            }
            publishQr(volunteer, hashes)
        }
        // Access revoked, volunteer deleted or unlinked: take their page down.
        val stale = hashes.keys.filter {
            it.startsWith("$orgId/") && !it.contains("#") && it != brandingKey && it !in keep
        }
        for (key in stale) {
            val uid = key.removePrefix("$orgId/")
            runCatching { gateway.deleteDocument(orgId, PortalProjection.COLLECTION, uid) }
            if (uid !in guestPhotoUids) {
                runCatching { host.deleteStorage(portalPhotoPath(uid)) }
                hashes.remove("$key#photo")
            }
            hashes.remove(key)
            deleteQrDocument(uid, hashes)
        }
        // QR deletes that failed earlier (page already gone): retry them.
        val staleQr = hashes.keys.filter {
            it.startsWith("$orgId/") && it.endsWith(QR_SUFFIX) && it.removeSuffix(QR_SUFFIX) !in keep
        }
        for (qrKey in staleQr) {
            deleteQrDocument(qrKey.removePrefix("$orgId/").removeSuffix(QR_SUFFIX), hashes)
        }
        // After the volunteers: a page they just took down may hand the shared photo to a guest.
        guestPublisher.publish(snapshot.guests, snapshot.volunteers, snapshot.transfers, hashes, googleLinkAllowed, now, snapshot.guestForms)
        host.savePublishedHashes(hashes)
        lastPublishDay = DateTimeUtils.getStartOfDayWithOffset(now, host.dateOffsetHours())
    }

    private fun today(): Long = DateTimeUtils.getStartOfDayWithOffset(clock(), host.dateOffsetHours())

    /** Deletes the QR document; the hash entry is dropped only on success so a later publish retries. */
    private suspend fun deleteQrDocument(uid: String, hashes: MutableMap<String, String>) {
        val qrKey = "$orgId/$uid$QR_SUFFIX"
        if (hashes[qrKey] == QR_TOMBSTONE) {
            hashes.remove(qrKey)
            return
        }
        try {
            gateway.deleteDocument(orgId, PortalQrDocument.COLLECTION, uid)
            hashes.remove(qrKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hashes[qrKey] = hashes[qrKey] ?: QR_RETRY
            println("Portal: QR delete failed for one volunteer: ${e.message}")
        }
    }

    /** Writes, refreshes or removes the volunteer's QR document so it follows admin status and the setting. */
    private suspend fun publishQr(volunteer: Volunteer, hashes: MutableMap<String, String>) {
        val qrKey = "$orgId/${volunteer.portalUid}$QR_SUFFIX"
        val data = PortalQrDocument.build(volunteer, host.portalQrEnabled())
        try {
            if (data == null) {
                // Hashes are per device: the doc may come from the signup function or another
                // device, so delete unless this device already did (tombstone).
                if (hashes[qrKey] != QR_TOMBSTONE) {
                    gateway.deleteDocument(orgId, PortalQrDocument.COLLECTION, volunteer.portalUid)
                    hashes[qrKey] = QR_TOMBSTONE
                }
                return
            }
            val hash = PortalProjectionBuilder.sha256(data.toString())
            if (hashes[qrKey] == hash) return
            gateway.setPlainDocument(orgId, PortalQrDocument.COLLECTION, volunteer.portalUid, data, merge = false)
            hashes[qrKey] = hash
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Portal: QR publish failed for one volunteer: ${e.message}")
        }
    }

    private val brandingKey get() = "$orgId#branding"

    /** Last logo seen and its public version: shrinking a large PNG is not redone on every publish. */
    private var logoSource: String = ""
    private var logoPublished: String = ""

    /** A logo the public page can show: large pictures are shrunk to a small JPEG, unusable ones dropped. */
    private fun publicLogo(raw: String): String {
        if (raw != logoSource) {
            logoSource = raw
            logoPublished = if (raw.isBlank()) "" else GuestFormLogoCodec.fitForFirestore(raw).let {
                if (it.length <= MAX_PUBLIC_LOGO_CHARS) it else ""
            }
        }
        return logoPublished
    }

    private suspend fun publishBranding(hashes: MutableMap<String, String>) {
        val logo = publicLogo(host.institutionLogoPng().trim())
        val data = mapOf(
            "associationName" to host.associationName().trim().take(80),
            // Lets the portal offer a photo change only when the rules accept the upload.
            "photosEnabled" to host.profilePhotosEnabled(),
            // The rules cap nothing here, so keep the public document small.
            "logoDataUri" to logo,
            // Shows "Sign in with Google"; the domains themselves are never published.
            "googleLogin" to googleLoginOn(),
        )
        val hash = PortalProjectionBuilder.sha256(data.toString())
        if (hashes[brandingKey] == hash) return
        try {
            gateway.setPlainDocument(orgId, BRANDING_COLLECTION, BRANDING_DOC, data + ("lastModified" to clock()), merge = false)
            hashes[brandingKey] = hash
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Portal: branding publish failed: ${e.message}")
        }
    }

    private fun googleLoginOn(): Boolean = PortalGoogleLinkProcessor.loginOn(
        host.portalGoogleEnabled(), host.portalGoogleAnyAccount(), host.portalGoogleDomains(),
    )

    private val mailKey get() = "$orgId#mail"
    private val guestMailKey get() = "$orgId#mailGuest"

    private suspend fun publishMailTemplate(hashes: MutableMap<String, String>) {
        publishMailTemplate(hashes, mailKey, MAIL_DOC) { host.portalMailTemplate() }
        publishMailTemplate(hashes, guestMailKey, GUEST_MAIL_DOC) { host.portalGuestMailTemplate() }
    }

    private suspend fun publishMailTemplate(
        hashes: MutableMap<String, String>,
        hashKey: String,
        docId: String,
        render: suspend () -> PortalMailTemplate?,
    ) {
        val template = try {
            render()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Portal: mail template failed: ${e.message}")
            null
        } ?: return
        val data = template.toFirestoreMap()
        val hash = PortalProjectionBuilder.sha256(data.toString())
        if (hashes[hashKey] == hash) return
        try {
            gateway.setPlainDocument(orgId, MAIL_COLLECTION, docId, template.toFirestoreMap(clock()), merge = false)
            hashes[hashKey] = hash
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Portal: mail template publish failed: ${e.message}")
        }
    }

    private fun photoVersionOf(volunteer: Volunteer): String =
        photoVersion(host.profilePhotosEnabled(), volunteer.profilePhotoUrl, volunteer.profilePhotoPath)

    /** Copies (or removes) the portal photo. True when the published state now matches [version]. */
    private suspend fun publishPhoto(volunteer: Volunteer, version: String): Boolean {
        val path = portalPhotoPath(volunteer.portalUid)
        if (version.isBlank()) {
            runCatching { host.deleteStorage(path) }
            return true
        }
        val bytes = host.volunteerPhotoBytes(volunteer) ?: return false
        return host.uploadStorageJpeg(path, bytes)
    }

    /** [keepPhoto]: the account photo stays for a guest of the account (with its hash, so the guest publish replaces it). */
    private suspend fun unpublish(uid: String, keepPhoto: Boolean = false) {
        runCatching { gateway.deleteDocument(orgId, PortalProjection.COLLECTION, uid) }
        if (!keepPhoto) runCatching { host.deleteStorage(portalPhotoPath(uid)) }
        val hashes = host.loadPublishedHashes().toMutableMap()
        hashes.remove("$orgId/$uid")
        if (!keepPhoto) hashes.remove("$orgId/$uid#photo")
        hashes.remove("$orgId/$uid$QR_SUFFIX")
        deleteQrDocument(uid, hashes)
        host.savePublishedHashes(hashes)
    }

    fun portalPhotoPath(uid: String): String = "orgs/$orgId/portalPhotos/$uid.jpg"

    /** A new photo picked in the portal, announced by a PROFILE request with `photo: true`. */
    fun portalUploadPath(uid: String): String = "orgs/$orgId/portalPhotoUploads/$uid.jpg"

    // endregion

    private fun processingFields(now: Long): Map<String, Any?> = mapOf(
        "status" to STATUS_PROCESSING,
        "processingAt" to now,
        "processingDevice" to host.deviceId.take(64),
    )

    private fun isStaleProcessing(data: Map<String, Any?>): Boolean {
        if (data["status"] != STATUS_PROCESSING) return false
        val at = firestoreMillisOf(data["processingAt"]) ?: return true
        return clock() - at > PortalRequest.PROCESSING_STALE_MILLIS
    }

    private fun Map<String, Any?>.withStatus(status: String): Map<String, Any?> = this + ("status" to status)

    private suspend fun runCatchingLogged(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Portal: $what failed: ${e.message}")
        }
    }

    companion object {
        private const val STATUS_PROCESSING = "PROCESSING"
        const val BRANDING_COLLECTION = "portalBranding"
        const val BRANDING_DOC = "default"
        const val MAIL_COLLECTION = "portalMail"
        const val MAIL_DOC = "default"
        const val GUEST_MAIL_DOC = "guest"
        private const val QR_SUFFIX = "#qr"
        /** QR hash value meaning "this device deleted the QR doc". */
        internal const val QR_TOMBSTONE = "-"
        /** QR hash value meaning "a delete failed, retry it". */
        internal const val QR_RETRY = "retry"

        /** Version of a profile photo reference, empty when there is none to publish. */
        internal fun photoVersion(photosEnabled: Boolean, url: String, path: String): String {
            if (!photosEnabled) return ""
            val ref = url.ifBlank { path }.trim()
            if (ref.isBlank() || ref.startsWith("cleared", ignoreCase = true)) return ""
            return PortalProjectionBuilder.sha256(ref).take(16)
        }
        /** Age under which a signup or request is left to the Cloud Functions. */
        const val DEVICE_GRACE_MS = 120_000L
        /** The branding document is public and read on every page load: keep the logo small. */
        const val MAX_PUBLIC_LOGO_CHARS = 300_000
        /** Same cap as the Storage rules for portal uploads. */
        private const val MAX_UPLOAD_BYTES = 3 * 1024 * 1024
        private const val PUBLISH_DEBOUNCE_MS = 4_000L
        private const val RETRY_INTERVAL_MS = 2 * 60_000L

        private val hashJson = Json
        fun encodeHashes(map: Map<String, String>): String =
            hashJson.encodeToString(MapSerializer(String.serializer(), String.serializer()), map)

        fun decodeHashes(raw: String): Map<String, String> =
            if (raw.isBlank()) emptyMap()
            else runCatching {
                hashJson.decodeFromString(MapSerializer(String.serializer(), String.serializer()), raw)
            }.getOrDefault(emptyMap())
    }
}

enum class SignupFormState { Waiting, Submitted, Completed, Expired }
