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

class OrgShareNoticeTest {
    private val texts = OrgShareTexts(
        sent = "Every device of %1\$s will be invited to join %2\$s.",
        noAccess = "No access to %1\$s.",
        failed = "%1\$s: %2\$s",
    )

    @Test
    fun sentNamesBothOrgs() {
        assertEquals(
            "Every device of Club A will be invited to join club-b.",
            orgShareNotice(com.eventmanager.app.data.remote.FirebaseOrgInvitationActions.ShareResult.Sent, texts, "Club A", "club-b"),
        )
    }

    @Test
    fun failureKeepsTheReason() {
        assertEquals(
            "club-b: offline",
            orgShareNotice(com.eventmanager.app.data.remote.FirebaseOrgInvitationActions.ShareResult.Failed("offline"), texts, "A", "club-b"),
        )
    }
}
