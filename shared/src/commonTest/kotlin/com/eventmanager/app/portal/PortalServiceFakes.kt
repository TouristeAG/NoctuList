package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.remote.NoOpFirestoreGateway
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory Firestore: documents per "collection/id", listeners fed by the test. Background
 * publish coroutines write while the test reads, so every collection here is thread-safe.
 */
internal class FakeGateway : NoOpFirestoreGateway() {
    val docs: MutableMap<String, Map<String, Any?>> = ConcurrentHashMap()
    val deleted: MutableList<String> = CopyOnWriteArrayList()
    val listeners: MutableMap<String, suspend (List<Pair<String, Map<String, Any?>>>) -> Unit> = ConcurrentHashMap()
    var claimsRefused = false
    var qrWrites = 0
    var qrDeleteFailures = 0
    /** Every write, as "collection/id", in order. */
    val writes: MutableList<String> = CopyOnWriteArrayList()

    override fun isAvailable() = true

    override suspend fun setPlainDocument(orgId: String, collection: String, docId: String, data: Map<String, Any?>, merge: Boolean) {
        val key = "$collection/$docId"
        if (collection == "volunteerPortalQr") qrWrites++
        writes += key
        synchronized(docs) { docs[key] = if (merge) (docs[key].orEmpty() + data) else data }
    }

    override suspend fun deleteDocument(orgId: String, collection: String, docId: String) {
        if (collection == "volunteerPortalQr" && qrDeleteFailures > 0) {
            qrDeleteFailures--
            throw IllegalStateException("offline")
        }
        docs.remove("$collection/$docId")
        deleted += "$collection/$docId"
    }

    override suspend fun getDocumentFromServer(orgId: String, collection: String, docId: String) = docs["$collection/$docId"]

    override fun listenCollection(
        orgId: String,
        collection: String,
        onSnapshot: suspend (List<Pair<String, Map<String, Any?>>>) -> Unit,
    ): AutoCloseable {
        listeners[collection] = onSnapshot
        return AutoCloseable { listeners.remove(collection) }
    }

    override suspend fun claimDocument(
        orgId: String,
        collection: String,
        docId: String,
        claimable: (Map<String, Any?>) -> Boolean,
        claimFields: Map<String, Any?>,
    ): Map<String, Any?>? {
        if (claimsRefused) return null
        val key = "$collection/$docId"
        synchronized(docs) {
            val current = docs[key] ?: return null
            if (!claimable(current)) return null
            docs[key] = current + claimFields
            return current
        }
    }

    suspend fun push(collection: String) {
        val prefix = "$collection/"
        val snapshot = docs.filterKeys { it.startsWith(prefix) }.map { (k, v) -> k.removePrefix(prefix) to v }
        listeners[collection]!!.invoke(snapshot)
    }
}

internal class FakeHost(initial: List<Volunteer>) : VolunteerPortalHost {
    val volunteers = initial.toMutableList()
    val created = mutableListOf<Volunteer>()
    val mailed = mutableListOf<Volunteer>()
    val storage = mutableMapOf<String, ByteArray>()
    var canMail = true
    var mailSubject = "Your QR code"
    var mailTemplateEnabled = true
    var guestMailSubject = "Your guest QR code"
    var guestMailTemplateEnabled = true
    var googleEnabled = true
    var qrEnabled = true
    var googleDomains = listOf("asso.ch")
    private var pending = emptyList<VolunteerSignupForm>()
    private var hashes = emptyMap<String, String>()

    override val deviceId = "device-1"
    override fun apiKey() = "key"
    override fun currencyCode() = "CHF"
    override fun associationName() = "Asso"
    var dateOffset = 0
    var venues: Set<String> = emptySet()
    var catalog: List<com.eventmanager.app.data.models.VenueAccess> = emptyList()
    val addedBatches = mutableListOf<com.eventmanager.app.data.models.ManualTemporaryGuestBatch>()
    override fun activeVenueNames(): Set<String> = venues
    override fun venueAccessCatalog(): List<com.eventmanager.app.data.models.VenueAccess> = catalog
    override suspend fun addTemporaryGuests(batch: com.eventmanager.app.data.models.ManualTemporaryGuestBatch): Boolean {
        addedBatches += batch
        return true
    }
    override suspend fun deleteTemporaryGuest(guest: Guest): Boolean = guests.removeAll { it.nanoId == guest.nanoId }
    override fun dateOffsetHours() = dateOffset
    var portalValidate = false
    override fun portalValidateEnabled() = portalValidate
    val forms = mutableListOf<com.eventmanager.app.data.models.GuestForm>()
    override suspend fun guestForms() = forms.toList()
    data class AcceptedReview(
        val form: com.eventmanager.app.data.models.GuestForm,
        val people: List<com.eventmanager.app.data.models.ManualTemporaryGuestEntry>,
        val contactEmail: String,
        val phone: String,
        val notes: String,
        val reviewedBy: String,
    )
    val acceptedReviews = mutableListOf<AcceptedReview>()
    val rejectedReviews = mutableListOf<Pair<com.eventmanager.app.data.models.GuestForm, String>>()
    val removedReviews = mutableListOf<Pair<com.eventmanager.app.data.models.GuestForm, String>>()
    var acceptResult = true
    override suspend fun acceptGuestFormFromPortal(
        form: com.eventmanager.app.data.models.GuestForm,
        people: List<com.eventmanager.app.data.models.ManualTemporaryGuestEntry>,
        contactEmail: String,
        phone: String,
        notes: String,
        reviewedBy: String,
    ): Boolean {
        if (!acceptResult) return false
        acceptedReviews += AcceptedReview(form, people, contactEmail, phone, notes, reviewedBy)
        return true
    }
    override suspend fun acceptGuestFormRemovalFromPortal(form: com.eventmanager.app.data.models.GuestForm, reviewedBy: String): Boolean {
        removedReviews += form to reviewedBy
        return true
    }
    override suspend fun rejectGuestFormFromPortal(form: com.eventmanager.app.data.models.GuestForm, reviewedBy: String): Boolean {
        rejectedReviews += form to reviewedBy
        return true
    }
    override fun institutionLogoPng() = ""
    override fun profilePhotosEnabled() = true
    override fun portalGoogleEnabled() = googleEnabled
    override fun portalGoogleDomains() = googleDomains
    var googleTemplate = ""
    var googleAny = false
    override fun portalGoogleAnyAccount() = googleAny
    override fun portalGoogleAddressTemplate() = googleTemplate
    override fun portalQrEnabled() = qrEnabled
    override suspend fun volunteers() = volunteers.toList()
    override suspend fun createVolunteer(volunteer: Volunteer, photo: ByteArray?): Volunteer {
        created += volunteer
        volunteers += volunteer
        return volunteer
    }
    override suspend fun saveVolunteer(volunteer: Volunteer): Boolean {
        val i = volunteers.indexOfFirst { it.id == volunteer.id }
        if (i < 0) return false
        volunteers[i] = volunteer
        return true
    }
    val attached = mutableListOf<Pair<String, ByteArray>>()
    override suspend fun attachVolunteerPhoto(volunteer: Volunteer, bytes: ByteArray): Boolean {
        attached += volunteer.id to bytes
        return true
    }
    override suspend fun downloadStorage(path: String) = storage[path]
    /** Storage paths uploaded and deleted, in order. */
    val storageUploads = mutableListOf<String>()
    val storageDeletes = mutableListOf<String>()
    override suspend fun uploadStorageJpeg(path: String, bytes: ByteArray): Boolean {
        storageUploads += path
        storage[path] = bytes
        return true
    }
    override suspend fun deleteStorage(path: String) {
        storageDeletes += path
        storage.remove(path)
    }
    /** Photo bytes by volunteer id; none by default. */
    val volunteerPhotos = mutableMapOf<String, ByteArray>()
    override suspend fun volunteerPhotoBytes(volunteer: Volunteer): ByteArray? = volunteerPhotos[volunteer.id]

    val guests = mutableListOf<Guest>()
    var guestSpace = false
    var tempSpace = false
    var tempCredits = false
    /** Photo bytes by guest nanoId; none by default. */
    val guestPhotos = mutableMapOf<String, ByteArray>()
    override fun guestPortalEnabled() = guestSpace
    override fun temporaryGuestPortalEnabled() = tempSpace
    override fun temporaryGuestCreditsEnabled() = tempCredits
    override suspend fun guests() = guests.toList()
    override suspend fun guestPhotoBytes(guest: Guest): ByteArray? = guestPhotos[guest.nanoId]
    override suspend fun saveGuest(guest: Guest): Boolean {
        val i = guests.indexOfFirst { it.nanoId == guest.nanoId }
        if (i < 0) return false
        guests[i] = guest
        return true
    }
    /** Guests created through [createGuest], with the photo they came with. */
    val createdGuests = mutableListOf<Pair<Guest, ByteArray?>>()
    override suspend fun createGuest(guest: Guest, photo: ByteArray?): Guest {
        createdGuests += guest to photo
        guests += guest
        return guest
    }
    /** Photos attached to guests, as nanoId to bytes. */
    val guestAttached = mutableListOf<Pair<String, ByteArray>>()
    override suspend fun attachGuestPhoto(guest: Guest, bytes: ByteArray): Boolean {
        guestAttached += guest.nanoId to bytes
        return true
    }
    val guestMailed = mutableListOf<Guest>()
    override suspend fun sendGuestQrEmail(guest: Guest): Boolean {
        guestMailed += guest
        return true
    }
    override suspend fun portalMailTemplate(): PortalMailTemplate? =
        if (mailTemplateEnabled) PortalMailTemplate(mailSubject, "<p>Hello $PORTAL_FIRST_NAME_TOKEN</p>", "Hello $PORTAL_FIRST_NAME_TOKEN", "") else null
    override suspend fun portalGuestMailTemplate(): PortalMailTemplate? =
        if (guestMailTemplateEnabled) PortalMailTemplate(guestMailSubject, "<p>Welcome $PORTAL_FIRST_NAME_TOKEN</p>", "Welcome $PORTAL_FIRST_NAME_TOKEN", "") else null
    override fun canSendQrEmail() = canMail
    override suspend fun sendQrEmail(volunteer: Volunteer): Boolean {
        mailed += volunteer
        return true
    }
    override fun loadPendingSignupForms() = pending
    override fun savePendingSignupForms(forms: List<VolunteerSignupForm>) {
        pending = forms
    }
    override fun loadPublishedHashes() = hashes
    override fun savePublishedHashes(hashes: Map<String, String>) {
        this.hashes = hashes
    }
}

internal class FakeAuth(private val create: PortalAuthResult) : PortalAuthApi {
    val resets = mutableListOf<String>()
    /** Addresses createAccount was called with. */
    val creates = mutableListOf<String>()
    override suspend fun createAccount(email: String, password: String): PortalAuthResult {
        creates += email
        return create
    }
    override suspend fun sendPasswordReset(email: String, languageCode: String): PortalAuthResult {
        resets += email
        return PortalAuthResult.Sent
    }
}
