package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.remote.NoOpFirestoreGateway

/** In-memory Firestore: documents per "collection/id", listeners fed by the test. */
internal class FakeGateway : NoOpFirestoreGateway() {
    val docs = mutableMapOf<String, Map<String, Any?>>()
    val deleted = mutableListOf<String>()
    val listeners = mutableMapOf<String, suspend (List<Pair<String, Map<String, Any?>>>) -> Unit>()
    var claimsRefused = false
    var qrWrites = 0
    var qrDeleteFailures = 0
    /** Every write, as "collection/id", in order. */
    val writes = mutableListOf<String>()

    override fun isAvailable() = true

    override suspend fun setPlainDocument(orgId: String, collection: String, docId: String, data: Map<String, Any?>, merge: Boolean) {
        val key = "$collection/$docId"
        if (collection == "volunteerPortalQr") qrWrites++
        writes += key
        docs[key] = if (merge) (docs[key].orEmpty() + data) else data
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
        val current = docs[key] ?: return null
        if (!claimable(current)) return null
        docs[key] = current + claimFields
        return current
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
    var googleEnabled = true
    var qrEnabled = true
    var googleDomains = listOf("asso.ch")
    private var pending = emptyList<VolunteerSignupForm>()
    private var hashes = emptyMap<String, String>()

    override val deviceId = "device-1"
    override fun apiKey() = "key"
    override fun currencyCode() = "CHF"
    override fun associationName() = "Asso"
    override fun dateOffsetHours() = 0
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
    override suspend fun portalMailTemplate(): PortalMailTemplate? =
        if (mailTemplateEnabled) PortalMailTemplate(mailSubject, "<p>Hello $PORTAL_FIRST_NAME_TOKEN</p>", "Hello $PORTAL_FIRST_NAME_TOKEN", "") else null
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
    override suspend fun createAccount(email: String, password: String) = create
    override suspend fun sendPasswordReset(email: String, languageCode: String): PortalAuthResult {
        resets += email
        return PortalAuthResult.Sent
    }
}
