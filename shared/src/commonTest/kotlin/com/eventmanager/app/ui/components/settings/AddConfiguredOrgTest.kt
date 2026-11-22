package com.eventmanager.app.ui.components.settings

import com.eventmanager.app.data.remote.FirebaseConfiguredOrg
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AddConfiguredOrgTest {
    private val current = listOf(FirebaseConfiguredOrg("club-a", 0xFFE53935L))

    @Test
    fun onThisDeviceOnlyAddsALocalOrg() {
        val added = addConfiguredOrg(current, " club-b ", shareWithOrg = false).last()
        assertEquals("club-b", added.orgId)
        assertTrue(added.localOnly)
    }

    @Test
    fun onEveryDeviceAddsASharedOrgWithAFreeColour() {
        val updated = addConfiguredOrg(current, "club-b", shareWithOrg = true)
        assertFalse(updated.last().localOnly)
        assertTrue(updated.last().colorArgb != updated.first().colorArgb)
    }

    @Test
    fun anOrgAlreadyInTheListIsNotAddedTwice() {
        assertEquals(current, addConfiguredOrg(current, "club-a", shareWithOrg = true))
    }
}
