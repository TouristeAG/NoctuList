package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuestPortalAccessTest {
    private val guest = Guest(name = "Max", email = " max@example.org ", invitations = 0, venueName = "Bar")

    @Test
    fun guestQrMailGoesToTheProfileAddressExceptForAdmins() {
        assertEquals("max@example.org", guestQrMailRecipient(guest))
        assertNull(guestQrMailRecipient(guest.copy(isAdmin = true)))
        assertNull(guestQrMailRecipient(guest.copy(isTemporaryGuest = true)))
        assertNull(guestQrMailRecipient(guest.copy(isVolunteerBenefit = true)))
        assertNull(guestQrMailRecipient(guest.copy(email = "")))
        assertNull(guestQrMailRecipient(guest.copy(email = "not an address")))
    }

    @Test
    fun autoAccessOnlyForNewPermanentGuestsWithAnAddressAndBothSwitchesOn() {
        assertTrue(guestAutoAccessApplies(guest, portalOn = true, autoAccess = true, guestSpaceOn = true))
        assertFalse(guestAutoAccessApplies(guest, portalOn = false, autoAccess = true, guestSpaceOn = true))
        assertFalse(guestAutoAccessApplies(guest, portalOn = true, autoAccess = false, guestSpaceOn = true))
        assertFalse(guestAutoAccessApplies(guest, portalOn = true, autoAccess = true, guestSpaceOn = false))
        assertFalse(guestAutoAccessApplies(guest.copy(email = ""), portalOn = true, autoAccess = true, guestSpaceOn = true))
        assertFalse(guestAutoAccessApplies(guest.copy(isTemporaryGuest = true), portalOn = true, autoAccess = true, guestSpaceOn = true))
        assertFalse(guestAutoAccessApplies(guest.copy(isVolunteerBenefit = true), portalOn = true, autoAccess = true, guestSpaceOn = true))
        assertFalse(guestAutoAccessApplies(guest.copy(portalEnabled = true), portalOn = true, autoAccess = true, guestSpaceOn = true))
    }
}
