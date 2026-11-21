package com.eventmanager.app.data.remote

import com.eventmanager.app.data.models.AccountHolderType
import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.AccountTransferSyncState
import com.eventmanager.app.data.models.AccountTransferType
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.VenueEntity
import com.eventmanager.app.data.utils.NanoIdGenerator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirestoreSyncIntegrityTest {

    @Test
    fun stampLedgerTransfer_persistsOrgAndSyncState() {
        val original = AccountTransfer(
            holderType = AccountHolderType.VOLUNTEER,
            holderId = "v1",
            holderName = "Ada",
            amount = 10.0,
            type = AccountTransferType.MANUAL_ADJUSTMENT,
            sourceReference = "manual:abc:v1",
            firebaseOrgId = "",
        )
        val stamped = stampLedgerTransfer(original, "org-a", AccountTransferSyncState.CONFIRMED)
        assertEquals("org-a", stamped.firebaseOrgId)
        assertEquals(AccountTransferSyncState.CONFIRMED, stamped.syncState)
        assertEquals(10.0, stamped.amount)
    }

    @Test
    fun applyPolicy_emptyNonDeleteSnapshotIsNotADelete() {
        assertFalse(FirestoreApplyPolicy.isRemoteDelete(deleted = false))
        assertTrue(FirestoreApplyPolicy.shouldSkipIncompleteSnapshot(deleted = false, data = null))
        assertTrue(FirestoreApplyPolicy.shouldSkipIncompleteSnapshot(deleted = false, data = emptyMap()))
        assertFalse(FirestoreApplyPolicy.shouldSkipIncompleteSnapshot(deleted = true, data = null))
        assertFalse(
            FirestoreApplyPolicy.shouldSkipIncompleteSnapshot(
                deleted = false,
                data = mapOf("lastModified" to 1L),
            ),
        )
    }

    @Test
    fun applyPolicy_lwwKeepsLocalAndBackfillsBlankOrg() {
        assertTrue(FirestoreApplyPolicy.shouldKeepLocal(existingLastModified = 50L, remoteLastModified = 50L))
        assertTrue(FirestoreApplyPolicy.shouldKeepLocal(existingLastModified = 51L, remoteLastModified = 50L))
        assertFalse(FirestoreApplyPolicy.shouldKeepLocal(existingLastModified = 49L, remoteLastModified = 50L))
        assertTrue(FirestoreApplyPolicy.needsOrgBackfill("", "org-a"))
        assertFalse(FirestoreApplyPolicy.needsOrgBackfill("org-a", "org-a"))
        assertEquals("org-a", FirestoreApplyPolicy.orgIdToPersist("", "org-a"))
        assertEquals("org-a", FirestoreApplyPolicy.orgIdToPersist("org-a", "org-b"))
    }

    @Test
    fun persistDedupeGuests_doesNotDropUntaggedWhenTaggedRowsExist() {
        val taggedId = NanoIdGenerator.generateGuestId()
        val untaggedId = NanoIdGenerator.generateGuestId()
        val guests = listOf(
            Guest(id = 1, nanoId = untaggedId, name = "Local", invitations = 1, venueName = "Main", firebaseOrgId = ""),
            Guest(id = 2, nanoId = taggedId, name = "Remote", invitations = 1, venueName = "Main", firebaseOrgId = "org-a"),
        )
        val kept = PersistIdentityDedupe.guests(guests)
        assertEquals(2, kept.size)
        assertTrue(kept.any { it.nanoId == untaggedId && it.firebaseOrgId.isBlank() })
        assertTrue(kept.any { it.nanoId == taggedId && it.firebaseOrgId == "org-a" })
    }

    @Test
    fun persistDedupeVenues_collapsesUntaggedTwinOfTaggedName() {
        val venues = listOf(
            VenueEntity(id = 1, name = "Lieu 1", firebaseOrgId = "", isActive = true),
            VenueEntity(id = 2, name = "Lieu 1", firebaseOrgId = "org-a", isActive = true),
            VenueEntity(id = 3, name = "Lieu 2", firebaseOrgId = "", isActive = true),
            VenueEntity(id = 4, name = "Lieu 2", firebaseOrgId = "org-a", isActive = true),
        )
        val kept = PersistIdentityDedupe.venues(venues)
        assertEquals(listOf("Lieu 1", "Lieu 2"), kept.map { it.name }.sorted())
        assertTrue(kept.all { it.firebaseOrgId == "org-a" })
    }

    @Test
    fun persistDedupeVenues_keepsSameNameAcrossDifferentOrgs() {
        val venues = listOf(
            VenueEntity(id = 1, name = "Lieu 1", firebaseOrgId = "org-a", isActive = true),
            VenueEntity(id = 2, name = "Lieu 1", firebaseOrgId = "org-b", isActive = true),
        )
        val kept = PersistIdentityDedupe.venues(venues)
        assertEquals(2, kept.size)
        assertEquals(setOf("org-a", "org-b"), kept.map { it.firebaseOrgId }.toSet())
    }

    @Test
    fun persistDedupeVenues_collapsesSameOrgNameFromPullAndListener() {
        val venues = listOf(
            VenueEntity(id = 1, name = "Lieu 1", description = "local", firebaseOrgId = "org-a", isActive = true),
            VenueEntity(id = 2, name = "Lieu 1", description = "", firebaseOrgId = "org-a", isActive = true),
        )
        val kept = PersistIdentityDedupe.venues(venues)
        assertEquals(1, kept.size)
        assertEquals("org-a", kept.single().firebaseOrgId)
    }

    @Test
    fun persistDedupeVenues_keepsUntaggedWhenNoTaggedTwin() {
        val venues = listOf(
            VenueEntity(id = 1, name = "Legacy", firebaseOrgId = "", isActive = true),
            VenueEntity(id = 2, name = "Lieu 1", firebaseOrgId = "org-a", isActive = true),
        )
        val kept = PersistIdentityDedupe.venues(venues)
        assertEquals(2, kept.size)
        assertTrue(kept.any { it.name == "Legacy" && it.firebaseOrgId.isBlank() })
    }

    @Test
    fun persistDedupeGuests_collapsesSameNanoIdPreferringNewerTagged() {
        val nanoId = NanoIdGenerator.generateGuestId()
        val guests = listOf(
            Guest(
                id = 1,
                nanoId = nanoId,
                name = "Old",
                invitations = 1,
                venueName = "Main",
                lastModified = 10L,
                firebaseOrgId = "",
            ),
            Guest(
                id = 2,
                nanoId = nanoId,
                name = "New",
                invitations = 1,
                venueName = "Main",
                lastModified = 20L,
                firebaseOrgId = "org-a",
            ),
        )
        val kept = PersistIdentityDedupe.guests(guests)
        assertEquals(1, kept.size)
        assertEquals("New", kept.single().name)
        assertEquals("org-a", kept.single().firebaseOrgId)
    }

    @Test
    fun volunteerBenefitMerge_preservesNanoIdAndOrgOnUpdate() {
        val existing = Guest(
            id = 7,
            nanoId = "existing-nano-id-xxxxx",
            name = "Ada",
            invitations = 1,
            venueName = "BOTH",
            isVolunteerBenefit = true,
            volunteerId = "vol-1",
            firebaseOrgId = "org-a",
            lastModified = 1L,
        )
        val computed = Guest(
            name = "Ada L",
            invitations = 2,
            venueName = "BOTH",
            notes = "Volunteer benefit - SPECIAL",
            isVolunteerBenefit = true,
            volunteerId = "vol-1",
            firebaseOrgId = "",
        )
        val merged = VolunteerBenefitGuestMerge.prepareForUpdate(existing, computed)
        assertEquals(existing.id, merged.id)
        assertEquals(existing.nanoId, merged.nanoId)
        assertEquals("org-a", merged.firebaseOrgId)
        assertEquals(2, merged.invitations)
        assertEquals("Ada L", merged.name)
    }

    @Test
    fun volunteerBenefitDiff_deletesForkedRowsForSameVolunteer() {
        val computed = Guest(
            name = "Leonardo",
            lastNameAbbreviation = "Mondada",
            invitations = 2,
            venueName = "BOTH",
            notes = "Volunteer benefit - SPECIAL",
            isVolunteerBenefit = true,
            volunteerId = "vol-leo",
            firebaseOrgId = "org-a",
        )
        val keep = computed.copy(id = 1, nanoId = "aaaaaaaaaaaaaaaaaaaaa", lastModified = 30L)
        val fork1 = computed.copy(id = 2, nanoId = "bbbbbbbbbbbbbbbbbbbbb", lastModified = 10L, invitations = 1)
        val fork2 = computed.copy(id = 3, nanoId = "ccccccccccccccccccccc", lastModified = 20L, invitations = 0)
        val diff = VolunteerBenefitGuestMerge.diff(
            existing = listOf(keep, fork1, fork2),
            computed = listOf(computed),
            orgIdForInsert = "org-a",
        )
        assertEquals(0, diff.toInsert.size)
        assertEquals(setOf(2L, 3L), diff.toDelete.map { it.id }.toSet())
        assertTrue(diff.toUpdate.isEmpty() || diff.toUpdate.single().id == 1L)
        val collapsed = VolunteerBenefitGuestMerge.collapseDuplicates(listOf(keep, fork1, fork2))
        assertEquals(1, collapsed.size)
        assertEquals(1L, collapsed.single().id)
    }

    @Test
    fun volunteerBenefitDiff_doesNotInsertWhenVolunteerAlreadyHasARow() {
        val existing = Guest(
            id = 4,
            nanoId = "ddddddddddddddddddddd",
            name = "Leonardo",
            lastNameAbbreviation = "M",
            invitations = 1,
            venueName = "BOTH",
            notes = "Volunteer benefit - SPECIAL",
            isVolunteerBenefit = true,
            volunteerId = "vol-leo",
            firebaseOrgId = "org-a",
            lastModified = 5L,
        )
        val computed = existing.copy(id = 0, nanoId = "eeeeeeeeeeeeeeeeeeeee", invitations = 3, lastNameAbbreviation = "Mondada")
        val diff = VolunteerBenefitGuestMerge.diff(
            existing = listOf(existing),
            computed = listOf(computed),
            orgIdForInsert = "org-a",
        )
        assertEquals(0, diff.toInsert.size)
        assertEquals(0, diff.toDelete.size)
        assertEquals(1, diff.toUpdate.size)
        assertEquals(4L, diff.toUpdate.single().id)
        assertEquals(existing.nanoId, diff.toUpdate.single().nanoId)
        assertEquals(3, diff.toUpdate.single().invitations)
        assertEquals("Mondada", diff.toUpdate.single().lastNameAbbreviation)
    }

    @Test
    fun nextPeopleCounterTimestamp_isStrictlyMonotonic() {
        assertEquals(100L, FirestoreApplyPolicy.nextPeopleCounterTimestamp(previous = 50L, now = 100L))
        assertEquals(51L, FirestoreApplyPolicy.nextPeopleCounterTimestamp(previous = 50L, now = 50L))
        assertEquals(51L, FirestoreApplyPolicy.nextPeopleCounterTimestamp(previous = 50L, now = 40L))
    }

    @Test
    fun mergePeopleCounter_keepsNewerLocalWhenVenueSnapshotIsStale() {
        val local = PeopleCounterSnapshot(
            count = 42,
            writerDeviceId = "device-a",
            writerAccountEmail = "a@example.com",
            lastModified = 200L,
        )
        val merged = FirestoreApplyPolicy.mergePeopleCounter(
            local = local,
            remoteCount = 30,
            remoteWriterDeviceId = "device-a",
            remoteWriterAccountEmail = "a@example.com",
            remoteLastModified = 150L,
        )
        assertEquals(42, merged.count)
        assertEquals(200L, merged.lastModified)
    }

    @Test
    fun mergePeopleCounter_equalTimestampDoesNotRollBackLocalCount() {
        val local = PeopleCounterSnapshot(
            count = 18,
            writerDeviceId = "device-a",
            writerAccountEmail = "",
            lastModified = 90L,
        )
        val merged = FirestoreApplyPolicy.mergePeopleCounter(
            local = local,
            remoteCount = 11,
            remoteWriterDeviceId = "device-a",
            remoteWriterAccountEmail = "",
            remoteLastModified = 90L,
        )
        assertEquals(18, merged.count)
        assertEquals(90L, merged.lastModified)
    }

    @Test
    fun mergePeopleCounter_takesNewerRemoteCount() {
        val local = PeopleCounterSnapshot(
            count = 5,
            writerDeviceId = "device-a",
            writerAccountEmail = "",
            lastModified = 10L,
        )
        val merged = FirestoreApplyPolicy.mergePeopleCounter(
            local = local,
            remoteCount = 12,
            remoteWriterDeviceId = "device-b",
            remoteWriterAccountEmail = "b@example.com",
            remoteLastModified = 20L,
        )
        assertEquals(12, merged.count)
        assertEquals("device-b", merged.writerDeviceId)
        assertEquals("b@example.com", merged.writerAccountEmail)
        assertEquals(20L, merged.lastModified)
    }

    @Test
    fun mergePeopleCounter_insertUsesRemoteWhenNoLocal() {
        val merged = FirestoreApplyPolicy.mergePeopleCounter(
            local = null,
            remoteCount = 3,
            remoteWriterDeviceId = "device-a",
            remoteWriterAccountEmail = "a@example.com",
            remoteLastModified = 5L,
        )
        assertEquals(3, merged.count)
        assertEquals("device-a", merged.writerDeviceId)
        assertEquals(5L, merged.lastModified)
    }

    @Test
    fun peopleCounterWrite_skipsWhenRemoteTimestampIsNewer() {
        assertTrue(FirestoreApplyPolicy.shouldKeepLocal(existingLastModified = 200L, remoteLastModified = 150L))
        assertFalse(FirestoreApplyPolicy.shouldKeepLocal(existingLastModified = 150L, remoteLastModified = 200L))
    }

    @Test
    fun volunteerBenefitMerge_insertStampsOrg() {
        val computed = Guest(
            name = "Ada",
            invitations = 1,
            venueName = "BOTH",
            isVolunteerBenefit = true,
            volunteerId = "vol-1",
        )
        val inserted = VolunteerBenefitGuestMerge.prepareForInsert(computed, "org-a")
        assertEquals("org-a", inserted.firebaseOrgId)
        assertTrue(inserted.isVolunteerBenefit)
    }

    @Test
    fun guestAndVolunteerDefaultPhotoFieldsAreEmpty() {
        val guest = Guest(name = "Ada", invitations = 1, venueName = "Main")
        assertEquals("", guest.profilePhotoPath)
        assertEquals("", guest.profilePhotoUrl)
        val volunteer = com.eventmanager.app.data.models.Volunteer(
            name = "Bea",
            lastNameAbbreviation = "B",
            email = "b@x.com",
            phoneNumber = "1",
        )
        assertEquals("", volunteer.profilePhotoPath)
        assertEquals("", volunteer.profilePhotoUrl)
    }

    @Test
    fun firestoreMapsRoundTripUnencryptedProfilePhotoFields() {
        val gateway = GitLiveFirestoreGateway()
        val guest = Guest(
            name = "Ada",
            invitations = 1,
            venueName = "Main",
            firebaseOrgId = "org-a",
            profilePhotoPath = "orgs/org-a/profilePhotos/guests/gid.jpg",
            profilePhotoUrl = "https://example.com/g.jpg",
        )
        val guestMap = gateway.guestToMap(guest)
        assertEquals(guest.profilePhotoPath, guestMap["profilePhotoPath"])
        assertEquals(guest.profilePhotoUrl, guestMap["profilePhotoUrl"])
        val guestDecrypted = com.eventmanager.app.data.security.crypto.SensitiveFieldCodec.decryptGuestMap(guestMap, "org-a")
        assertEquals(guest.profilePhotoUrl, guestDecrypted["profilePhotoUrl"])
        assertEquals(guest.profilePhotoPath, guestDecrypted["profilePhotoPath"])

        val volunteer = com.eventmanager.app.data.models.Volunteer(
            name = "Bea",
            lastNameAbbreviation = "B",
            email = "b@x.com",
            phoneNumber = "1",
            firebaseOrgId = "org-a",
            profilePhotoPath = "orgs/org-a/profilePhotos/volunteers/vid.jpg",
            profilePhotoUrl = "https://example.com/v.jpg",
        )
        val volunteerMap = gateway.volunteerToMap(volunteer)
        assertEquals(volunteer.profilePhotoPath, volunteerMap["profilePhotoPath"])
        assertEquals(volunteer.profilePhotoUrl, volunteerMap["profilePhotoUrl"])
        val volunteerDecrypted = com.eventmanager.app.data.security.crypto.SensitiveFieldCodec.decryptVolunteerMap(volunteerMap, "org-a")
        assertEquals(volunteer.profilePhotoUrl, volunteerDecrypted["profilePhotoUrl"])
        assertEquals(volunteer.profilePhotoPath, volunteerDecrypted["profilePhotoPath"])
    }

    @Test
    fun volunteerMapCarriesLinkedGoogleAccountFields() {
        val gateway = GitLiveFirestoreGateway()
        val volunteer = com.eventmanager.app.data.models.Volunteer(
            name = "Bea",
            lastNameAbbreviation = "B",
            email = "b@x.com",
            phoneNumber = "1",
            firebaseOrgId = "org-a",
            portalGoogleEmail = "bea@assoc.org",
            portalGoogleUid = "google-uid-1",
            portalGoogleLinkedBy = com.eventmanager.app.data.models.Volunteer.GOOGLE_LINKED_BY_ADMIN,
        )
        val decrypted = com.eventmanager.app.data.security.crypto.SensitiveFieldCodec
            .decryptVolunteerMap(gateway.volunteerToMap(volunteer), "org-a")
        assertEquals("bea@assoc.org", decrypted["portalGoogleEmail"])
        assertEquals("google-uid-1", decrypted["portalGoogleUid"])
        assertEquals("ADMIN", decrypted["portalGoogleLinkedBy"])
        assertEquals("VOLUNTEER", com.eventmanager.app.data.models.Volunteer.GOOGLE_LINKED_BY_VOLUNTEER)
        val blank = com.eventmanager.app.data.models.Volunteer(
            name = "C", lastNameAbbreviation = "C", email = "c@x.com", phoneNumber = "1", firebaseOrgId = "org-a",
        )
        assertEquals("", blank.portalGoogleEmail)
        assertEquals("", blank.portalGoogleUid)
        assertEquals("", blank.portalGoogleLinkedBy)
    }

    @Test
    fun volunteerMapOmitsBlankPhotoFieldsSoMergeCannotWipe() {
        val gateway = GitLiveFirestoreGateway()
        val volunteer = com.eventmanager.app.data.models.Volunteer(
            name = "Bea",
            lastNameAbbreviation = "B",
            email = "b@x.com",
            phoneNumber = "1",
            firebaseOrgId = "org-a",
        )
        val map = gateway.volunteerToMap(volunteer)
        assertFalse(map.containsKey("profilePhotoPath"))
        assertFalse(map.containsKey("profilePhotoUrl"))
    }

    @Test
    fun mergeProfilePhotoFieldsPrefersRemoteThenKeepsLocal() {
        val fromRemote = FirestoreApplyPolicy.mergeProfilePhotoFields("", "", "p", "https://u")
        assertEquals("p", fromRemote.path)
        assertEquals("https://u", fromRemote.url)
        val keepLocal = FirestoreApplyPolicy.mergeProfilePhotoFields("p", "https://u", "", "")
        assertEquals("p", keepLocal.path)
        assertEquals("https://u", keepLocal.url)
    }

    @Test
    fun mergeProfilePhotoFieldsKeepsLocalClearAgainstStaleRemotePhoto() {
        val cleared = FirestoreApplyPolicy.mergeProfilePhotoFields(
            localPath = PROFILE_PHOTO_CLEARED_SENTINEL,
            localUrl = PROFILE_PHOTO_CLEARED_SENTINEL,
            remotePath = "orgs/org/profilePhotos/guests/g.jpg",
            remoteUrl = "https://example.com/old.jpg",
            localLastModified = 200L,
            remoteLastModified = 100L,
        )
        assertEquals(PROFILE_PHOTO_CLEARED_SENTINEL, cleared.path)
        assertEquals(PROFILE_PHOTO_CLEARED_SENTINEL, cleared.url)
    }

    @Test
    fun mergeProfilePhotoFieldsAppliesRemoteClear() {
        val cleared = FirestoreApplyPolicy.mergeProfilePhotoFields(
            localPath = "orgs/org/profilePhotos/guests/g.jpg",
            localUrl = "https://example.com/old.jpg",
            remotePath = PROFILE_PHOTO_CLEARED_SENTINEL,
            remoteUrl = PROFILE_PHOTO_CLEARED_SENTINEL,
            localLastModified = 100L,
            remoteLastModified = 200L,
        )
        assertEquals(PROFILE_PHOTO_CLEARED_SENTINEL, cleared.path)
        assertEquals(PROFILE_PHOTO_CLEARED_SENTINEL, cleared.url)
    }

    @Test
    fun guestToMapWritesClearedPhotoSentinel() {
        val gateway = GitLiveFirestoreGateway()
        val guest = Guest(
            name = "Ada",
            invitations = 1,
            venueName = "Main",
            firebaseOrgId = "org-a",
            profilePhotoPath = PROFILE_PHOTO_CLEARED_SENTINEL,
            profilePhotoUrl = PROFILE_PHOTO_CLEARED_SENTINEL,
        )
        val map = gateway.guestToMap(guest)
        assertEquals(PROFILE_PHOTO_CLEARED_SENTINEL, map["profilePhotoPath"])
        assertEquals(PROFILE_PHOTO_CLEARED_SENTINEL, map["profilePhotoUrl"])
    }

    @Test
    fun sheetsHeadersNeverIncludeProfilePhotoColumns() {
        listOf(
            com.eventmanager.app.data.sync.SheetsColumnContract.GUEST_LIST,
            com.eventmanager.app.data.sync.SheetsColumnContract.VOLUNTEER_GUEST_LIST,
            com.eventmanager.app.data.sync.SheetsColumnContract.VOLUNTEERS,
        ).forEach { headers ->
            headers.forEach { header ->
                assertFalse(header.contains("photo", ignoreCase = true), header)
            }
        }
    }

    @Test
    fun guestPortalFieldsRoundTrip() {
        val gateway = GitLiveFirestoreGateway()
        val guest = Guest(
            name = "Ada",
            invitations = 1,
            venueName = "Main",
            firebaseOrgId = "org-a",
            portalEnabled = true,
            portalUid = "portal-uid-1",
            portalGoogleEmail = "ada@assoc.org",
            portalGoogleUid = "google-uid-1",
            portalGoogleLinkedBy = "ADMIN",
            temporaryPortalEmail = "artist@example.org",
            temporaryPortalCreated = true,
        )
        val decrypted = com.eventmanager.app.data.security.crypto.SensitiveFieldCodec
            .decryptGuestMap(gateway.guestToMap(guest), "org-a")
        assertEquals("artist@example.org", decrypted["temporaryPortalEmail"])
        assertEquals(true, decrypted["temporaryPortalCreated"])
        assertEquals(false, Guest(name = "B", invitations = 1, venueName = "Main").temporaryPortalCreated)
        assertEquals("", Guest(name = "B", invitations = 1, venueName = "Main").temporaryPortalEmail)
        assertEquals(true, decrypted["portalEnabled"])
        assertEquals("portal-uid-1", decrypted["portalUid"])
        assertEquals("ada@assoc.org", decrypted["portalGoogleEmail"])
        assertEquals("google-uid-1", decrypted["portalGoogleUid"])
        assertEquals("ADMIN", decrypted["portalGoogleLinkedBy"])
        val blank = Guest(name = "B", invitations = 1, venueName = "Main")
        assertEquals(false, blank.portalEnabled)
        assertEquals("", blank.portalUid)
        assertEquals("", blank.portalGoogleEmail)
        assertEquals("", blank.portalGoogleUid)
        assertEquals("", blank.portalGoogleLinkedBy)
    }

    @Test
    fun guestPortalFieldsNeverInSheetsRow() {
        // The Sheets guest row is exactly SheetsColumnContract.GUEST_LIST wide and is built without
        // any portal field, so no portal column may ever appear in the contract.
        listOf(
            com.eventmanager.app.data.sync.SheetsColumnContract.GUEST_LIST,
            com.eventmanager.app.data.sync.SheetsColumnContract.VOLUNTEER_GUEST_LIST,
        ).forEach { headers ->
            headers.forEach { header ->
                assertFalse(header.contains("portal", ignoreCase = true), header)
                assertFalse(header.contains("google", ignoreCase = true), header)
            }
        }
    }
}
