package com.eventmanager.app.portal

import com.eventmanager.app.data.models.AccountHolderType
import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.GuestForm
import com.eventmanager.app.data.models.isPortalInvite
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.remote.FirestoreGateway
import kotlin.coroutines.cancellation.CancellationException

/**
 * Publishes the guest spaces: one page and QR document per permanent guest account, one manager
 * page and QR document per temporary guest account. Runs inside [VolunteerPortalService.publishAll],
 * after the volunteers, with the same per-device content hashes:
 *  - `"$orgId/$uid#guest"`, `#guestQr`, `#temp`, `#tempQr`;
 *  - the shared `"$orgId/$uid#photo"` key for the account photo when the guest owns it (§6.3: a
 *    linked volunteer with a photo comes first, otherwise the permanent guest's photo).
 * A space turned off, an access closed or a temporary group ended removes the documents.
 */
internal class GuestPortalPublisher(
    private val orgId: String,
    private val gateway: FirestoreGateway,
    private val host: VolunteerPortalHost,
) {
    /** Earliest end of a temporary list published by the last pass, so the list leaves on time. */
    @Volatile
    var nextTemporaryExpiry: Long? = null
        private set

    /**
     * Copies the account photo for every uid the guest space owns and returns those uids: the space
     * is on, a permanent guest is published for the uid and no linked volunteer of the uid has a
     * photo. Runs before the volunteer pages, which then skip these uids and read the shared hash.
     */
    suspend fun publishPhotos(guests: List<Guest>, volunteers: List<Volunteer>, hashes: MutableMap<String, String>): Set<String> {
        if (!host.guestPortalEnabled()) return emptySet()
        val volunteerPhotoUids = volunteerPhotoUids(volunteers)
        val owned = mutableSetOf<String>()
        for ((uid, guest) in permanentsByUid(guests)) {
            if (uid in volunteerPhotoUids) continue
            owned += uid
            val photoKey = key(uid, PHOTO_SUFFIX)
            val version = VolunteerPortalService.photoVersion(host.profilePhotosEnabled(), guest.profilePhotoUrl, guest.profilePhotoPath)
            if (version != (hashes[photoKey] ?: "") && publishPhoto(uid, guest, version)) {
                if (version.isBlank()) hashes.remove(photoKey) else hashes[photoKey] = version
            }
        }
        return owned
    }

    /** Pages and QR documents; call [publishPhotos] first, before the volunteer pages. */
    suspend fun publish(
        guests: List<Guest>,
        volunteers: List<Volunteer>,
        transfers: List<AccountTransfer>,
        hashes: MutableMap<String, String>,
        googleLinkAllowed: Boolean,
        now: Long,
        inviteForms: List<GuestForm> = emptyList(),
    ) {
        val inOrg = inOrg(guests)
        val volunteerPhotoUids = volunteerPhotoUids(volunteers)
        val guestTransfers = transfers.filter { it.holderType == AccountHolderType.GUEST }

        val keptGuests = mutableSetOf<String>()
        if (host.guestPortalEnabled()) {
            for ((uid, guest) in permanentsByUid(inOrg)) {
                keptGuests += uid
                publishGuest(uid, guest, guestTransfers, hashes, googleLinkAllowed, now)
            }
        }
        for (uid in uidsWith(hashes, GUEST_SUFFIX) - keptGuests) {
            removePage(GuestPortalProjectionBuilder.COLLECTION, uid, GUEST_SUFFIX, hashes)
            if (uid !in volunteerPhotoUids) removePhoto(uid, hashes)
        }
        for (uid in uidsWith(hashes, GUEST_QR_SUFFIX) - keptGuests) {
            deleteQr(GuestPortalQrDocument.COLLECTION, uid, GUEST_QR_SUFFIX, hashes)
        }

        val keptTemps = mutableSetOf<String>()
        var nextExpiry: Long? = null
        if (host.temporaryGuestPortalEnabled()) {
            val groups = inOrg
                .filter { it.isTemporaryGuest && it.portalEnabled && it.portalUid.isNotBlank() }
                .groupBy { it.portalUid }
            val formsByUid = inviteForms
                .filter { (it.firebaseOrgId.isBlank() || it.firebaseOrgId == orgId) && it.isPortalInvite }
                .groupBy { it.portalUid.trim() }
            // A volunteer or permanent guest on the uid means the account was not made for a list alone.
            val holderUids = volunteers.filter { it.portalUid.isNotBlank() }.map { it.portalUid }.toSet() +
                inOrg.filter { !it.isTemporaryGuest && it.portalUid.isNotBlank() }.map { it.portalUid }
            for (uid in groups.keys + formsByUid.keys) {
                val members = groups[uid].orEmpty()
                val forms = formsByUid[uid].orEmpty()
                val deviceCreated = members.isNotEmpty() && members.all { it.temporaryPortalCreated } && uid !in holderUids
                TemporaryGuestPortalProjectionBuilder.runningBatches(members, now).minOfOrNull { it.second }
                    ?.let { until -> nextExpiry = minOf(nextExpiry ?: until, until) }
                keptTemps += uid
                publishTemporary(uid, members, guestTransfers, hashes, now, forms, deviceCreated)
            }
        }
        nextTemporaryExpiry = nextExpiry
        for (uid in uidsWith(hashes, TEMP_SUFFIX) - keptTemps) {
            removePage(TemporaryGuestPortalProjectionBuilder.COLLECTION, uid, TEMP_SUFFIX, hashes)
        }
        for (uid in uidsWith(hashes, TEMP_QR_SUFFIX) - keptTemps) {
            deleteQr(TemporaryGuestPortalQrDocument.COLLECTION, uid, TEMP_QR_SUFFIX, hashes)
        }
    }

    /** Portal turned off: removes every guest document this device knows. Failed QR deletes keep their key for a retry. */
    suspend fun unpublishAll(hashes: MutableMap<String, String>) {
        for (uid in uidsWith(hashes, GUEST_SUFFIX)) {
            runCatching { gateway.deleteDocument(orgId, GuestPortalProjectionBuilder.COLLECTION, uid) }
            hashes.remove(key(uid, GUEST_SUFFIX))
            removePhoto(uid, hashes)
        }
        for (uid in uidsWith(hashes, TEMP_SUFFIX)) {
            runCatching { gateway.deleteDocument(orgId, TemporaryGuestPortalProjectionBuilder.COLLECTION, uid) }
            hashes.remove(key(uid, TEMP_SUFFIX))
        }
        for (uid in uidsWith(hashes, GUEST_QR_SUFFIX)) deleteQr(GuestPortalQrDocument.COLLECTION, uid, GUEST_QR_SUFFIX, hashes)
        for (uid in uidsWith(hashes, TEMP_QR_SUFFIX)) deleteQr(TemporaryGuestPortalQrDocument.COLLECTION, uid, TEMP_QR_SUFFIX, hashes)
    }

    /**
     * One guest's access closed or the guest deleted: deletes its page and QR document right away
     * ([removePage]) and the account photo ([removePhoto]), whoever wrote them. A failed page or
     * QR delete keeps its key, so the next publish retries.
     */
    suspend fun unpublishGuest(uid: String, hashes: MutableMap<String, String>, removePage: Boolean, removePhoto: Boolean) {
        if (removePage) {
            try {
                gateway.deleteDocument(orgId, GuestPortalProjectionBuilder.COLLECTION, uid)
                hashes.remove(key(uid, GUEST_SUFFIX))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hashes[key(uid, GUEST_SUFFIX)] = hashes[key(uid, GUEST_SUFFIX)] ?: VolunteerPortalService.QR_RETRY
                println("Portal: guest page delete failed: ${e.message}")
            }
            hashes.remove(key(uid, GUEST_QR_SUFFIX))
            deleteQr(GuestPortalQrDocument.COLLECTION, uid, GUEST_QR_SUFFIX, hashes)
        }
        if (removePhoto) {
            runCatching { host.deleteStorage(photoPath(uid)) }
            hashes.remove(key(uid, PHOTO_SUFFIX))
        }
    }

    private suspend fun publishGuest(
        uid: String,
        guest: Guest,
        transfers: List<AccountTransfer>,
        hashes: MutableMap<String, String>,
        googleLinkAllowed: Boolean,
        now: Long,
    ) {
        // The photo itself was copied by publishPhotos (guest-owned) or the volunteer publish.
        val photoVersion = hashes[key(uid, PHOTO_SUFFIX)] ?: ""
        val projection = GuestPortalProjectionBuilder.build(
            GuestPortalProjectionInput(
                guest = guest,
                transfers = transfers,
                currencyCode = host.currencyCode(),
                associationName = host.associationName(),
                hasPhoto = photoVersion.isNotBlank(),
                photoVersion = photoVersion,
                googleLinkAllowed = googleLinkAllowed,
            ),
        )
        writePage(GuestPortalProjectionBuilder.COLLECTION, uid, GUEST_SUFFIX, projection, hashes, now)
        publishQr(GuestPortalQrDocument.COLLECTION, uid, GUEST_QR_SUFFIX, GuestPortalQrDocument.build(guest, host.portalQrEnabled()), hashes)
    }

    private suspend fun publishTemporary(
        uid: String,
        members: List<Guest>,
        transfers: List<AccountTransfer>,
        hashes: MutableMap<String, String>,
        now: Long,
        inviteForms: List<GuestForm>,
        deviceCreated: Boolean,
    ) {
        val projection = TemporaryGuestPortalProjectionBuilder.build(
            TemporaryGuestPortalInput(
                guests = members,
                transfers = transfers,
                creditsEnabled = host.temporaryGuestCreditsEnabled(),
                currencyCode = host.currencyCode(),
                associationName = host.associationName(),
                now = now,
                deviceCreated = deviceCreated,
                accessNames = host.venueAccessNames(),
                inviteForms = inviteForms,
            ),
        )
        if (projection == null) {
            // Every group ended. The page may come from another device: delete it once (tombstone).
            if (hashes[key(uid, TEMP_SUFFIX)] != VolunteerPortalService.QR_TOMBSTONE) {
                try {
                    gateway.deleteDocument(orgId, TemporaryGuestPortalProjectionBuilder.COLLECTION, uid)
                    hashes[key(uid, TEMP_SUFFIX)] = VolunteerPortalService.QR_TOMBSTONE
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    println("Portal: temporary guest page delete failed: ${e.message}")
                }
            }
        } else {
            writePage(TemporaryGuestPortalProjectionBuilder.COLLECTION, uid, TEMP_SUFFIX, projection, hashes, now)
        }
        publishQr(
            TemporaryGuestPortalQrDocument.COLLECTION, uid, TEMP_QR_SUFFIX,
            TemporaryGuestPortalQrDocument.build(members, host.portalQrEnabled(), now), hashes,
        )
    }

    private suspend fun writePage(
        collection: String,
        uid: String,
        suffix: String,
        projection: PortalProjection,
        hashes: MutableMap<String, String>,
        now: Long,
    ) {
        val pageKey = key(uid, suffix)
        if (hashes[pageKey] == projection.contentHash) return
        try {
            gateway.setPlainDocument(orgId, collection, uid, projection.toFirestoreMap(now), merge = false)
            hashes[pageKey] = projection.contentHash
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Portal: guest page publish failed: ${e.message}")
        }
    }

    /** Same rules as the volunteer QR: hashed writes, a tombstone once deleted. */
    private suspend fun publishQr(
        collection: String,
        uid: String,
        suffix: String,
        data: Map<String, Any>?,
        hashes: MutableMap<String, String>,
    ) {
        val qrKey = key(uid, suffix)
        try {
            if (data == null) {
                if (hashes[qrKey] != VolunteerPortalService.QR_TOMBSTONE) {
                    gateway.deleteDocument(orgId, collection, uid)
                    hashes[qrKey] = VolunteerPortalService.QR_TOMBSTONE
                }
                return
            }
            val hash = PortalProjectionBuilder.sha256(data.toString())
            if (hashes[qrKey] == hash) return
            gateway.setPlainDocument(orgId, collection, uid, data, merge = false)
            hashes[qrKey] = hash
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Portal: guest QR publish failed: ${e.message}")
        }
    }

    /** The hash entry is dropped only on success, so a later publish retries. */
    private suspend fun deleteQr(collection: String, uid: String, suffix: String, hashes: MutableMap<String, String>) {
        val qrKey = key(uid, suffix)
        if (hashes[qrKey] == VolunteerPortalService.QR_TOMBSTONE) {
            hashes.remove(qrKey)
            return
        }
        try {
            gateway.deleteDocument(orgId, collection, uid)
            hashes.remove(qrKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hashes[qrKey] = VolunteerPortalService.QR_RETRY
            println("Portal: guest QR delete failed: ${e.message}")
        }
    }

    private suspend fun removePage(collection: String, uid: String, suffix: String, hashes: MutableMap<String, String>) {
        val pageKey = key(uid, suffix)
        if (hashes[pageKey] != VolunteerPortalService.QR_TOMBSTONE) {
            try {
                gateway.deleteDocument(orgId, collection, uid)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The key stays, so the next publish tries again.
                println("Portal: guest page delete failed: ${e.message}")
                return
            }
        }
        hashes.remove(pageKey)
    }

    /** Only when no linked volunteer has a photo: theirs is handled by the volunteer publish. */
    private suspend fun removePhoto(uid: String, hashes: MutableMap<String, String>) {
        val photoKey = key(uid, PHOTO_SUFFIX)
        if (photoKey !in hashes) return
        runCatching { host.deleteStorage(photoPath(uid)) }
        hashes.remove(photoKey)
    }

    /** Copies (or removes) the portal photo. True when the published state now matches [version]. */
    private suspend fun publishPhoto(uid: String, guest: Guest, version: String): Boolean {
        val path = photoPath(uid)
        if (version.isBlank()) {
            runCatching { host.deleteStorage(path) }
            return true
        }
        val bytes = host.guestPhotoBytes(guest) ?: return false
        return host.uploadStorageJpeg(path, bytes)
    }

    private fun inOrg(guests: List<Guest>) = guests.filter { it.firebaseOrgId.isBlank() || it.firebaseOrgId == orgId }

    /** Published permanent guests, one per uid: the most recently modified. */
    private fun permanentsByUid(guests: List<Guest>): Map<String, Guest> = inOrg(guests)
        .filter { !it.isTemporaryGuest && !it.isVolunteerBenefit && it.portalEnabled && it.portalUid.isNotBlank() }
        .groupBy { it.portalUid }
        .mapValues { (_, same) -> same.maxBy { it.lastModified } }

    /** Uids of linked volunteers that have a photo to publish: the volunteer owns the shared photo. */
    private fun volunteerPhotoUids(volunteers: List<Volunteer>): Set<String> = volunteers
        .filter {
            it.portalEnabled && it.portalUid.isNotBlank() && (it.firebaseOrgId.isBlank() || it.firebaseOrgId == orgId) &&
                VolunteerPortalService.photoVersion(host.profilePhotosEnabled(), it.profilePhotoUrl, it.profilePhotoPath).isNotBlank()
        }
        .mapTo(mutableSetOf()) { it.portalUid }

    private fun photoPath(uid: String) = "orgs/$orgId/portalPhotos/$uid.jpg"

    private fun key(uid: String, suffix: String) = "$orgId/$uid$suffix"

    private fun uidsWith(hashes: Map<String, String>, suffix: String): Set<String> =
        hashes.keys.filter { it.startsWith("$orgId/") && it.endsWith(suffix) }
            .mapTo(mutableSetOf()) { it.removePrefix("$orgId/").removeSuffix(suffix) }

    companion object {
        const val GUEST_SUFFIX = "#guest"
        const val GUEST_QR_SUFFIX = "#guestQr"
        const val TEMP_SUFFIX = "#temp"
        const val TEMP_QR_SUFFIX = "#tempQr"
        private const val PHOTO_SUFFIX = "#photo"
    }
}
