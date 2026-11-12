package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Volunteer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PortalQrDocumentTest {
    private fun volunteer(id: String, uid: String = "", admin: Boolean = false) = Volunteer(
        id = id,
        name = "Léa",
        lastNameAbbreviation = "MARTIN",
        email = "lea@example.org",
        phoneNumber = "+41 79 000 00 00",
        portalUid = uid,
        isAdmin = admin,
    )

    @Test
    fun buildsForNonAdmin() {
        assertEquals(
            mapOf("schema" to 1, "payload" to "VOL123", "name" to "Léa MARTIN"),
            PortalQrDocument.build(volunteer("VOL123", uid = "u1"), enabled = true),
        )
    }

    @Test
    fun nullForAdmin() = assertNull(PortalQrDocument.build(volunteer("A", uid = "u1", admin = true), true))

    @Test
    fun nullWhenDisabled() = assertNull(PortalQrDocument.build(volunteer("A", uid = "u1"), false))

    @Test
    fun nullWithoutPortalUid() = assertNull(PortalQrDocument.build(volunteer("A"), true))
}
