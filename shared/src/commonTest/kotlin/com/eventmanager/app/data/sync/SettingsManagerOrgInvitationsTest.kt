package com.eventmanager.app.data.sync

import com.eventmanager.app.data.remote.FirebaseConfiguredOrg
import com.eventmanager.app.data.remote.FirebaseOrgInvitation
import com.eventmanager.app.data.remote.FirebaseOrgInvitationActions
import com.eventmanager.app.data.remote.FirebaseOrgInvitationCodec
import com.eventmanager.app.platform.AppStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An admin device of club-a invites every club-a device to club-b; a member device receives it. */
class SettingsManagerOrgInvitationsTest {
    private val key = InstitutionSettingsKeys.FIREBASE_ORG_INVITATIONS

    private fun device(vararg orgIds: String): SettingsManager =
        SettingsManager(InvitationMemoryStorage()).apply {
            setFirebaseConfiguredOrgs(orgIds.mapIndexed { i, id -> FirebaseConfiguredOrg(id, 0xFF000000L + i + 1) })
            setFirebaseOrgId(orgIds.first())
            getInstitutionSettingRowsPendingRemotePush().forEach { markInstitutionSettingRowPushed(it) }
        }

    private fun invitation(sentAt: Long = 10L, message: String = "Ouvrez B pour le festival") = FirebaseOrgInvitation(
        orgId = "club-b",
        sourceOrgId = "club-a",
        joinSecret = "SECRET",
        message = message,
        sentAt = sentAt,
        sentBy = "admin@asso.ch",
    )

    private fun SettingsManager.pushInvitationsTo(to: SettingsManager, fromOrg: String) {
        val row = getInstitutionSettingRowsPendingRemotePush().first { it.key == key }
        to.applyInstitutionSettingFromRemote(row.key, row.value, row.lastModified, sourceOrgId = fromOrg)
        markInstitutionSettingRowPushed(row)
    }

    @Test
    fun invitationWithTheAdminMessageReachesTheMembersOfTheOrg() {
        val admin = device("club-a", "club-b")
        val member = device("club-a")

        admin.addFirebaseOrgInvitation(invitation())
        admin.pushInvitationsTo(member, fromOrg = "club-a")

        val pending = member.getPendingFirebaseOrgInvitations().single()
        assertEquals("club-b", pending.orgId)
        assertEquals("Ouvrez B pour le festival", pending.message)
        assertEquals("SECRET", pending.joinSecret)
    }

    @Test
    fun answeredInvitationsAreNotOfferedAgainButADeclinedOneCanStillBeJoined() {
        val member = device("club-a", "club-b")
        member.applyInstitutionSettingFromRemote(
            key, FirebaseOrgInvitationCodec.encode(listOf(invitation())), 1L, sourceOrgId = "club-a",
        )

        member.markFirebaseOrgInvitationDeclined(invitation())
        assertTrue(member.getPendingFirebaseOrgInvitations().isEmpty())
        assertEquals("club-b", member.getDeclinedFirebaseOrgInvitation("club-b")?.orgId)

        member.markFirebaseOrgInvitationJoined(invitation())
        assertNull(member.getDeclinedFirebaseOrgInvitation("club-b"))
    }

    @Test
    fun aNewerInvitationToTheSameOrgIsOfferedAgain() {
        val member = device("club-a")
        member.applyInstitutionSettingFromRemote(key, FirebaseOrgInvitationCodec.encode(listOf(invitation())), 1L, "club-a")
        member.markFirebaseOrgInvitationDeclined(invitation())

        member.applyInstitutionSettingFromRemote(
            key, FirebaseOrgInvitationCodec.encode(listOf(invitation(sentAt = 20L))), 2L, "club-a",
        )

        assertEquals(20L, member.getPendingFirebaseOrgInvitations().single().sentAt)
    }

    @Test
    fun anOrgOnlyPublishesItsOwnInvitations() {
        val admin = device("club-a", "club-c")
        admin.addFirebaseOrgInvitation(invitation())
        admin.addFirebaseOrgInvitation(invitation().copy(orgId = "club-x", sourceOrgId = "club-c"))

        val published = admin.getInstitutionSettingRows().first { it.key == key }.value

        assertEquals(listOf("club-b"), FirebaseOrgInvitationCodec.decode(published).map { it.orgId })
    }

    @Test
    fun invitationsReadFromAnotherOrgLeaveThisOrgsOnesAlone() {
        val member = device("club-a", "club-c")
        member.applyInstitutionSettingFromRemote(key, FirebaseOrgInvitationCodec.encode(listOf(invitation())), 1L, "club-a")

        // club-c's list, plus an entry pretending to come from club-a: only club-c's own is taken.
        val fromC = listOf(
            invitation().copy(orgId = "club-y", sourceOrgId = "club-c"),
            invitation().copy(orgId = "club-z", sourceOrgId = "club-a"),
        )
        member.applyInstitutionSettingFromRemote(key, FirebaseOrgInvitationCodec.encode(fromC), 2L, "club-c")

        assertEquals(
            setOf("club-b", "club-y"),
            member.getPendingFirebaseOrgInvitations().map { it.orgId }.toSet(),
        )
    }

    @Test
    fun invitationsFromAnOrgThisDeviceDoesNotUseAreNotOffered() {
        val member = device("club-q")
        member.applyInstitutionSettingFromRemote(key, FirebaseOrgInvitationCodec.encode(listOf(invitation())), 1L, "")
        assertTrue(member.getPendingFirebaseOrgInvitations().isEmpty())
    }

    @Test
    fun invitationsNeverGoToTheSpreadsheet() {
        assertFalse(InstitutionSettingsKeys.isSyncedToSheets(key))
    }

    @Test
    fun joinSecretIsWhatTheRulesCompareAgainst() {
        assertEquals("H", FirebaseOrgInvitationActions.joinSecretFromConfig(mapOf("bootstrapCodeHash" to "H", "bootstrapCode" to "L")))
        assertEquals("L", FirebaseOrgInvitationActions.joinSecretFromConfig(mapOf("bootstrapCode" to "L")))
        assertNull(FirebaseOrgInvitationActions.joinSecretFromConfig(mapOf("bootstrapCodeHash" to " ")))
        assertNull(FirebaseOrgInvitationActions.joinSecretFromConfig(null))
    }

    @Test
    fun messageIsTrimmedAndCapped() {
        assertEquals("ok", FirebaseOrgInvitationCodec.cleanMessage("  ok \n"))
        assertEquals(
            FirebaseOrgInvitationCodec.MESSAGE_MAX_LENGTH,
            FirebaseOrgInvitationCodec.cleanMessage("x".repeat(5000)).length,
        )
    }
}

private class InvitationMemoryStorage : AppStorage {
    private val values = mutableMapOf<String, Any>()

    override fun getString(key: String, default: String): String = values[key] as? String ?: default
    override fun putString(key: String, value: String) { values[key] = value }
    override fun getBoolean(key: String, default: Boolean): Boolean = values[key] as? Boolean ?: default
    override fun putBoolean(key: String, value: Boolean) { values[key] = value }
    override fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default
    override fun putInt(key: String, value: Int) { values[key] = value }
    override fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default
    override fun putLong(key: String, value: Long) { values[key] = value }
    override fun getFloat(key: String, default: Float): Float = values[key] as? Float ?: default
    override fun putFloat(key: String, value: Float) { values[key] = value }
    override fun remove(key: String) { values.remove(key) }
    override fun contains(key: String): Boolean = values.containsKey(key)

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, default: Set<String>): Set<String> =
        values[key] as? Set<String> ?: default

    override fun putStringSet(key: String, value: Set<String>) { values[key] = value }
    override fun clear() = values.clear()
}
