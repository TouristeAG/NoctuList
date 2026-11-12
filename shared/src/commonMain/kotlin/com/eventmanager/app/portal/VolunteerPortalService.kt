package com.eventmanager.app.portal

import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.GuestFormLogoCodec
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.remote.FirestoreGateway
import com.eventmanager.app.data.utils.NanoIdGenerator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job as CoroutineJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** Verified Google Workspace domains, lowercase. */
    fun portalGoogleDomains(): List<String>
    /** Non-admin volunteers get their own QR code in the portal. */
    fun portalQrEnabled(): Boolean = true

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
    fun canSendQrEmail(): Boolean
    suspend fun sendQrEmail(volunteer: Volunteer): Boolean

    fun loadPendingSignupForms(): List<VolunteerSignupForm>
    fun savePendingSignupForms(forms: List<VolunteerSignupForm>)
    fun loadPublishedHashes(): Map<String, String>
    fun savePublishedHashes(hashes: Map<String, String>)
}

/** Outcome of "open the portal for this volunteer" in the admin UI. */
sealed class PortalGrantResult {
    /** New account; [password] is shown once. A "set your password" mail was also sent if [resetSent]. */
    data class Created(val password: String, val resetSent: Boolean) : PortalGrantResult()
    /** An account already existed for the address: a reset link was sent, linking happens at sign-in. */
    data class ExistingAccount(val resetSent: Boolean) : PortalGrantResult()
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
) {
    private val mutex = Mutex()
    private val listeners = mutableListOf<AutoCloseable>()
    private var publisherJob: CoroutineJob? = null
    private var latestSignupDocs: List<Pair<String, Map<String, Any?>>> = emptyList()
    private var latestRequestDocs: List<Pair<String, Map<String, Any?>>> = emptyList()
    private var latestClaimDocs: List<Pair<String, Map<String, Any?>>> = emptyList()
    @Volatile private var latestSnapshot: Snapshot? = null
    private var started = false
    private val _serverStatus = MutableStateFlow<PortalServerStatus?>(null)

    /** Last run reported by the Cloud Functions, or null when none ever ran. */
    val serverStatus: StateFlow<PortalServerStatus?> = _serverStatus.asStateFlow()

    fun start(
        volunteers: Flow<List<Volunteer>>,
        jobs: Flow<List<Job>>,
        transfers: Flow<List<AccountTransfer>>,
        jobTypeConfigs: Flow<List<JobTypeConfig>>,
    ) {
        if (started) return
        started = true
        gateway.listenCollection(orgId, VolunteerSignupForm.COLLECTION) { docs ->
            latestSignupDocs = docs
            processSignups(docs)
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
            combine(volunteers, jobs, transfers, jobTypeConfigs) { v, j, t, c -> Snapshot(v, j, t, c).also { latestSnapshot = it } }
                .debounce(publishDebounceMs)
                .collect { snapshot ->
                    runCatchingLogged("publish") { publishAll(snapshot) }
                }
        }
        // Stale PROCESSING claims (a device that vanished mid-way) are retried periodically.
        scope.launch {
            while (started) {
                delay(RETRY_INTERVAL_MS)
                runCatchingLogged("retry") {
                    processSignups(latestSignupDocs)
                    processRequests(latestRequestDocs)
                    processClaims(latestClaimDocs)
                    cleanupExpiredForms()
                }
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

    suspend fun createSignupForm(): VolunteerSignupForm? {
        val now = clock()
        val form = VolunteerSignupForm(
            formId = NanoIdGenerator.generateGuestFormId(),
            orgId = orgId,
            createdAt = now,
            expiresAtMillis = now + VolunteerSignupForm.VALIDITY_MILLIS,
            photosEnabled = host.profilePhotosEnabled(),
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
            mutex.withLock { runCatchingLogged("signup $formId") { processSignup(formId) } }
        }
    }

    private suspend fun processSignup(formId: String) {
        val now = clock()
        val claimed = gateway.claimDocument(
            orgId,
            VolunteerSignupForm.COLLECTION,
            formId,
            claimable = { it["status"] == VolunteerSignupForm.STATUS_SUBMITTED || isStaleProcessing(it) },
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
            }
        }
        finishSignup(formId, submission.photoPath)
    }

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
            // Only a device that can actually send mail takes a QR request; others leave it waiting.
            if (type == PortalRequestType.QR_EMAIL.name && !host.canSendQrEmail()) continue
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
                    it["type"] != PortalRequestType.GOOGLE_LINK.name
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
        when (request.type) {
            PortalRequestType.CLAIM -> handleClaim(request, owner, volunteers)
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
            PortalRequestType.GOOGLE_UNLINK -> {
                val reason = PortalGoogleLinkProcessor.canUnlink(owner)
                if (reason != null) return complete(requestId, PortalRequest.STATUS_REJECTED, reason)
                val ok = clearGoogleLink(owner!!)
                complete(requestId, if (ok) PortalRequest.STATUS_DONE else PortalRequest.STATUS_REJECTED, if (ok) "" else "SAVE_FAILED")
            }
            // Never claimed (see processRequests): it is answered through its Google claim.
            PortalRequestType.GOOGLE_LINK -> Unit
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

    private fun isJpeg(bytes: ByteArray): Boolean =
        bytes.size in 4 until MAX_UPLOAD_BYTES &&
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

    private suspend fun handleClaim(request: PortalRequest, owner: Volunteer?, volunteers: List<Volunteer>) {
        if (owner != null) {
            complete(request.requestId, PortalRequest.STATUS_DONE, "")
            return
        }
        val candidates = volunteers.filter {
            it.portalEnabled && it.email.trim().equals(request.email, ignoreCase = true)
        }
        val free = candidates.filter { it.portalUid.isBlank() }
        when {
            candidates.isEmpty() -> complete(request.requestId, PortalRequest.STATUS_REJECTED, "NO_ACCESS")
            free.size != 1 -> complete(request.requestId, PortalRequest.STATUS_REJECTED, "LINKED_ELSEWHERE")
            else -> {
                val linked = free.single().copy(portalUid = request.uid, lastModified = maxOf(clock(), free.single().lastModified + 1))
                val ok = host.saveVolunteer(linked)
                complete(request.requestId, if (ok) PortalRequest.STATUS_DONE else PortalRequest.STATUS_REJECTED, if (ok) "" else "SAVE_FAILED")
            }
        }
    }

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
        val domains = host.portalGoogleDomains()
        var aliasTarget = gateway.getDocumentFromServer(orgId, PortalGoogleAccount.COLLECTION, googleUid)
            ?.get("volunteerUid") as? String
        if (aliasTarget != null && !PortalGoogleLinkProcessor.isAliasValid(googleUid, aliasTarget, volunteers)) {
            // Revoked, deleted or relinked volunteer: the alias must not keep answering for them.
            gateway.deleteDocument(orgId, PortalGoogleAccount.COLLECTION, googleUid)
            aliasTarget = null
        }
        var linkRequestId: String? = null
        val decision = when (claim.type) {
            PortalGoogleClaimType.ADMIN_MATCH ->
                PortalGoogleLinkProcessor.adminMatch(claim, volunteers, domains, aliasTarget)
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
                    claim, request, data?.let { firestoreMillisOf(it["requestedAt"]) }, now, volunteers, domains, aliasTarget,
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
                val ok = writeGoogleLink(decision.volunteer, googleUid, claim.email, decision.linkedBy, now)
                completeClaim(googleUid, if (ok) PortalRequest.STATUS_DONE else PortalRequest.STATUS_REJECTED, if (ok) "" else "SAVE_FAILED")
                if (ok) linkRequestId?.let { complete(it, PortalRequest.STATUS_DONE, "") }
            }
        }
    }

    /**
     * Alias first, then the volunteer: a failed save leaves no alias behind. A previous Google
     * account of the volunteer loses its alias. Republished by the volunteers flow.
     */
    private suspend fun writeGoogleLink(volunteer: Volunteer, googleUid: String, email: String, linkedBy: String, now: Long): Boolean {
        val previousUid = volunteer.portalGoogleUid
        if (previousUid.isNotBlank() && previousUid != googleUid) {
            val previousTarget = gateway.getDocumentFromServer(orgId, PortalGoogleAccount.COLLECTION, previousUid)
                ?.get("volunteerUid") as? String
            if (previousTarget == volunteer.portalUid) gateway.deleteDocument(orgId, PortalGoogleAccount.COLLECTION, previousUid)
        }
        gateway.setPlainDocument(
            orgId,
            PortalGoogleAccount.COLLECTION,
            googleUid,
            mapOf("volunteerUid" to volunteer.portalUid, "email" to email, "linkedBy" to linkedBy, "linkedAt" to now),
            merge = false,
        )
        val saved = host.saveVolunteer(
            volunteer.copy(
                portalGoogleEmail = volunteer.portalGoogleEmail.trim().ifBlank { email },
                portalGoogleUid = googleUid,
                portalGoogleLinkedBy = linkedBy,
                lastModified = maxOf(now, volunteer.lastModified + 1),
            ),
        )
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

    /** Deletes the alias (so the Google account loses access first), then blanks the three fields. */
    private suspend fun clearGoogleLink(volunteer: Volunteer): Boolean {
        if (volunteer.portalGoogleUid.isNotBlank()) {
            gateway.deleteDocument(orgId, PortalGoogleAccount.COLLECTION, volunteer.portalGoogleUid)
        }
        val cleared = volunteer.copy(
            portalGoogleEmail = "",
            portalGoogleUid = "",
            portalGoogleLinkedBy = "",
            lastModified = maxOf(clock(), volunteer.lastModified + 1),
        )
        return cleared == volunteer || host.saveVolunteer(cleared)
    }

    /**
     * Admin types the volunteer's association Google address. Null when saved, else INVALID,
     * DOMAIN, SAME_AS_EMAIL, TAKEN or SAVE_FAILED. A different address drops the previous link.
     */
    suspend fun assignGoogleAccount(volunteer: Volunteer, email: String): String? {
        val clean = PortalInputValidator.cleanEmail(email) ?: return "INVALID"
        if (!PortalGoogleLinkProcessor.domainAllowed(clean, host.portalGoogleDomains())) return "DOMAIN"
        val all = host.volunteers().filter { it.firebaseOrgId.isBlank() || it.firebaseOrgId == orgId }
        val current = all.firstOrNull { it.id == volunteer.id } ?: volunteer
        // The roster address already signs in with a password; the Google one must be the association's.
        if (PortalGoogleLinkProcessor.sameAddress(current.email, clean)) return "SAME_AS_EMAIL"
        if (all.any { it.id != volunteer.id && PortalGoogleLinkProcessor.sameAddress(it.portalGoogleEmail, clean) }) return "TAKEN"
        val sameAddress = PortalGoogleLinkProcessor.sameAddress(current.portalGoogleEmail, clean)
        return try {
            val keptUid = if (sameAddress) current.portalGoogleUid else ""
            if (!sameAddress && current.portalGoogleUid.isNotBlank()) {
                gateway.deleteDocument(orgId, PortalGoogleAccount.COLLECTION, current.portalGoogleUid)
            } else if (keptUid.isNotBlank() && current.portalGoogleLinkedBy != Volunteer.GOOGLE_LINKED_BY_ADMIN) {
                // Same account, now held by the admin: the volunteer can no longer remove it.
                gateway.setPlainDocument(
                    orgId, PortalGoogleAccount.COLLECTION, keptUid,
                    mapOf("linkedBy" to Volunteer.GOOGLE_LINKED_BY_ADMIN), merge = true,
                )
            }
            val updated = current.copy(
                portalGoogleEmail = clean,
                portalGoogleUid = keptUid,
                portalGoogleLinkedBy = Volunteer.GOOGLE_LINKED_BY_ADMIN,
                lastModified = maxOf(clock(), current.lastModified + 1),
            )
            if (updated == current || host.saveVolunteer(updated)) null else "SAVE_FAILED"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Portal: Google address save failed: ${e.message}")
            "SAVE_FAILED"
        }
    }

    /** The volunteer was deleted: its Google account loses access. Best effort. */
    suspend fun forgetGoogleAlias(volunteer: Volunteer) {
        val googleUid = volunteer.portalGoogleUid
        if (googleUid.isBlank()) return
        try {
            gateway.deleteDocument(orgId, PortalGoogleAccount.COLLECTION, googleUid)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Portal: Google alias not deleted: ${e.message}")
        }
    }

    /** Admin "Remove": drops the link whoever made it. */
    suspend fun removeGoogleAccount(volunteer: Volunteer): Boolean {
        val current = host.volunteers().firstOrNull { it.id == volunteer.id } ?: volunteer
        return try {
            clearGoogleLink(current)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Portal: Google account removal failed: ${e.message}")
            false
        }
    }

    // endregion

    // region Admin actions

    suspend fun grantAccess(volunteer: Volunteer, languageCode: String): PortalGrantResult {
        val email = volunteer.email.trim()
        if (PortalInputValidator.cleanEmail(email) == null) return PortalGrantResult.Failed("INVALID_EMAIL")
        val client = authClientFactory(host.apiKey())
        val password = PortalPasswordGenerator.generate()
        return when (val created = client.createAccount(email, password)) {
            is PortalAuthResult.Created -> {
                val saved = host.saveVolunteer(
                    volunteer.copy(portalEnabled = true, portalUid = created.uid, lastModified = clock()),
                )
                if (!saved) return PortalGrantResult.Failed("SAVE_FAILED")
                val reset = client.sendPasswordReset(email, languageCode) is PortalAuthResult.Sent
                PortalGrantResult.Created(password, reset)
            }
            PortalAuthResult.EmailExists -> {
                val saved = host.saveVolunteer(
                    volunteer.copy(portalEnabled = true, portalUid = "", lastModified = clock()),
                )
                if (!saved) return PortalGrantResult.Failed("SAVE_FAILED")
                val reset = client.sendPasswordReset(email, languageCode) is PortalAuthResult.Sent
                PortalGrantResult.ExistingAccount(reset)
            }
            is PortalAuthResult.Failed -> PortalGrantResult.Failed(created.code)
            PortalAuthResult.Sent -> PortalGrantResult.Failed("UNEXPECTED")
        }
    }

    suspend fun sendPasswordReset(volunteer: Volunteer, languageCode: String): Boolean =
        authClientFactory(host.apiKey()).sendPasswordReset(volunteer.email, languageCode) is PortalAuthResult.Sent

    suspend fun revokeAccess(volunteer: Volunteer): Boolean {
        val uid = volunteer.portalUid
        val googleUid = volunteer.portalGoogleUid
        if (googleUid.isNotBlank()) runCatching { gateway.deleteDocument(orgId, PortalGoogleAccount.COLLECTION, googleUid) }
        // An address the admin typed stays, so reopening the access links the same account again.
        val volunteerLink = volunteer.portalGoogleLinkedBy == Volunteer.GOOGLE_LINKED_BY_VOLUNTEER
        val saved = host.saveVolunteer(
            volunteer.copy(
                portalEnabled = false,
                portalUid = "",
                portalGoogleUid = "",
                portalGoogleEmail = if (volunteerLink) "" else volunteer.portalGoogleEmail,
                portalGoogleLinkedBy = if (volunteerLink) "" else volunteer.portalGoogleLinkedBy,
                lastModified = clock(),
            ),
        )
        if (uid.isNotBlank()) unpublish(uid)
        return saved
    }

    /** Portal turned off: remove every projection this device published. */
    suspend fun unpublishAll() {
        val hashes = host.loadPublishedHashes()
        hashes.keys.filter { it.startsWith("$orgId/") && !it.contains("#") }
            .map { it.removePrefix("$orgId/") }
            .forEach { uid -> runCatching { unpublish(uid) } }
        runCatching { gateway.deleteDocument(orgId, BRANDING_COLLECTION, BRANDING_DOC) }
        runCatching { gateway.deleteDocument(orgId, MAIL_COLLECTION, MAIL_DOC) }
        // Failed QR deletes stay so the next publish retries them.
        host.savePublishedHashes(
            host.loadPublishedHashes().filterKeys {
                (!it.startsWith("$orgId/") || it.endsWith(QR_SUFFIX)) && it != brandingKey && it != mailKey
            },
        )
    }

    // endregion

    // region Projection publishing

    private data class Snapshot(
        val volunteers: List<Volunteer>,
        val jobs: List<Job>,
        val transfers: List<AccountTransfer>,
        val configs: List<JobTypeConfig>,
    )

    private suspend fun publishAll(snapshot: Snapshot) = mutex.withLock {
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
        for (volunteer in linked) {
            val key = "$orgId/${volunteer.portalUid}"
            keep += key
            val photoKey = "$key#photo"
            val photoVersion = photoVersionOf(volunteer)
            if (photoVersion != (hashes[photoKey] ?: "")) {
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
            runCatching { host.deleteStorage(portalPhotoPath(uid)) }
            hashes.remove(key)
            hashes.remove("$key#photo")
            deleteQrDocument(uid, hashes)
        }
        // QR deletes that failed earlier (page already gone): retry them.
        val staleQr = hashes.keys.filter {
            it.startsWith("$orgId/") && it.endsWith(QR_SUFFIX) && it.removeSuffix(QR_SUFFIX) !in keep
        }
        for (qrKey in staleQr) {
            deleteQrDocument(qrKey.removePrefix("$orgId/").removeSuffix(QR_SUFFIX), hashes)
        }
        host.savePublishedHashes(hashes)
    }

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

    private fun googleLoginOn(): Boolean =
        host.portalGoogleEnabled() && PortalGoogleLinkProcessor.usableDomains(host.portalGoogleDomains()).isNotEmpty()

    private val mailKey get() = "$orgId#mail"

    private suspend fun publishMailTemplate(hashes: MutableMap<String, String>) {
        val template = try {
            host.portalMailTemplate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Portal: mail template failed: ${e.message}")
            null
        } ?: return
        val data = template.toFirestoreMap()
        val hash = PortalProjectionBuilder.sha256(data.toString())
        if (hashes[mailKey] == hash) return
        try {
            gateway.setPlainDocument(orgId, MAIL_COLLECTION, MAIL_DOC, template.toFirestoreMap(clock()), merge = false)
            hashes[mailKey] = hash
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Portal: mail template publish failed: ${e.message}")
        }
    }

    private fun photoVersionOf(volunteer: Volunteer): String {
        if (!host.profilePhotosEnabled()) return ""
        val ref = volunteer.profilePhotoUrl.ifBlank { volunteer.profilePhotoPath }.trim()
        if (ref.isBlank() || ref.startsWith("cleared", ignoreCase = true)) return ""
        return PortalProjectionBuilder.sha256(ref).take(16)
    }

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

    private suspend fun unpublish(uid: String) {
        runCatching { gateway.deleteDocument(orgId, PortalProjection.COLLECTION, uid) }
        runCatching { host.deleteStorage(portalPhotoPath(uid)) }
        val hashes = host.loadPublishedHashes().toMutableMap()
        hashes.remove("$orgId/$uid")
        hashes.remove("$orgId/$uid#photo")
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
        private const val QR_SUFFIX = "#qr"
        /** QR hash value meaning "this device deleted the QR doc". */
        private const val QR_TOMBSTONE = "-"
        /** QR hash value meaning "a delete failed, retry it". */
        private const val QR_RETRY = "retry"
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
