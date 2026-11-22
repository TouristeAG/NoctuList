package com.eventmanager.app.data.sync

import com.eventmanager.app.data.remote.FirebaseConfiguredOrg
import com.eventmanager.app.data.remote.FirebaseConfiguredOrgCodec
import com.eventmanager.app.platform.AppStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Two devices of the same org, wired by hand: what device A would push to
 * `orgs/{org}/institutionSettings/firebase_configured_orgs` is applied on device B the way the
 * Firestore listener and the pulls do.
 */
class SettingsManagerConfiguredOrgsSyncTest {
    private val key = InstitutionSettingsKeys.FIREBASE_CONFIGURED_ORGS
    private val red = 0xFFE53935L
    private val blue = 0xFF1E88E5L
    private val green = 0xFF43A047L

    private fun device(vararg orgs: FirebaseConfiguredOrg): SettingsManager =
        SettingsManager(OrgSyncMemoryStorage()).apply {
            setFirebaseConfiguredOrgs(orgs.toList())
            // Start from a synced state: nothing waits to be pushed.
            getInstitutionSettingRowsPendingRemotePush().forEach { markInstitutionSettingRowPushed(it) }
        }

    private fun SettingsManager.pendingOrgListRow(): InstitutionSettingRow? =
        getInstitutionSettingRowsPendingRemotePush().firstOrNull { it.key == key }

    /** What the admin device pushes, delivered to [to] as read from `orgs/{fromOrg}`. */
    private fun SettingsManager.pushTo(to: SettingsManager, fromOrg: String) {
        val row = pendingOrgListRow() ?: error("nothing to push")
        to.applyInstitutionSettingFromRemote(row.key, row.value, row.lastModified, sourceOrgId = fromOrg)
        markInstitutionSettingRowPushed(row)
    }

    @Test
    fun renameReachesTheOtherDevicesAndKeepsTheOrgId() {
        val admin = device(FirebaseConfiguredOrg("club-a", red))
        val other = device(FirebaseConfiguredOrg("club-a", red))

        assertTrue(admin.renameFirebaseConfiguredOrg("club-a", "  Club A   Lausanne "))
        admin.pushTo(other, fromOrg = "club-a")

        val received = other.getFirebaseConfiguredOrgs().single()
        assertEquals("club-a", received.orgId)
        assertEquals("Club A Lausanne", received.label)
        assertEquals("Club A Lausanne", other.getFirebaseOrgLabel("club-a"))
    }

    @Test
    fun blankNameGoesBackToTheOrgIdOnEveryDevice() {
        val admin = device(FirebaseConfiguredOrg("club-a", red, displayName = "Club A"))
        val other = device(FirebaseConfiguredOrg("club-a", red, displayName = "Club A"))

        admin.renameFirebaseConfiguredOrg("club-a", "   ")
        admin.pushTo(other, fromOrg = "club-a")

        assertEquals("club-a", other.getFirebaseConfiguredOrgs().single().label)
    }

    @Test
    fun renamingALocalOnlyOrgIsNotPushed() {
        val admin = device(
            FirebaseConfiguredOrg("club-a", red),
            FirebaseConfiguredOrg("perso", blue, localOnly = true),
        )

        assertFalse(admin.renameFirebaseConfiguredOrg("perso", "Mon club"))
        assertEquals("Mon club", admin.getFirebaseOrgLabel("perso"))
        assertNull(admin.pendingOrgListRow())
    }

    @Test
    fun orgAddedForTheWholeOrgIsMergedIntoTheOtherDevices() {
        val admin = device(FirebaseConfiguredOrg("club-a", red))
        val other = device(
            FirebaseConfiguredOrg("club-a", red),
            FirebaseConfiguredOrg("perso", green, localOnly = true),
        )

        admin.setFirebaseConfiguredOrgs(admin.getFirebaseConfiguredOrgs() + FirebaseConfiguredOrg("club-b", blue))
        admin.pushTo(other, fromOrg = "club-a")

        val orgs = other.getFirebaseConfiguredOrgs()
        assertEquals(listOf("club-a", "club-b", "perso"), orgs.map { it.orgId })
        assertFalse(orgs.first { it.orgId == "club-b" }.localOnly)
        // The other device's own local org survives the pushed list.
        assertTrue(orgs.first { it.orgId == "perso" }.localOnly)
    }

    @Test
    fun orgAddedOnThisDeviceOnlyStaysLocal() {
        val admin = device(FirebaseConfiguredOrg("club-a", red))

        admin.setFirebaseConfiguredOrgs(
            admin.getFirebaseConfiguredOrgs() + FirebaseConfiguredOrg("club-b", blue, localOnly = true),
        )

        // Nothing to push, and the value that would be pushed leaves club-b out.
        assertNull(admin.pendingOrgListRow())
        val published = admin.getInstitutionSettingRows().first { it.key == key }.value
        assertEquals(listOf("club-a"), FirebaseConfiguredOrgCodec.decode(published).map { it.orgId })
        assertEquals(listOf("club-a", "club-b"), admin.getFirebaseConfiguredOrgs().map { it.orgId })
    }

    @Test
    fun localOnlyOrgSurvivesALaterPushFromTheOrg() {
        val admin = device(FirebaseConfiguredOrg("club-a", red))
        val member = device(
            FirebaseConfiguredOrg("club-a", red),
            FirebaseConfiguredOrg("perso", blue, localOnly = true),
        )

        admin.renameFirebaseConfiguredOrg("club-a", "Club A")
        admin.pushTo(member, fromOrg = "club-a")

        assertEquals(listOf("club-a", "perso"), member.getFirebaseConfiguredOrgs().map { it.orgId })
    }

    @Test
    fun listReadFromAnOrgKeptOnThisDeviceOnlyIsIgnored() {
        val device = device(
            FirebaseConfiguredOrg("club-a", red),
            FirebaseConfiguredOrg("club-b", blue, localOnly = true),
        )
        val clubBList = FirebaseConfiguredOrgCodec.encode(
            listOf(FirebaseConfiguredOrg("club-b", blue), FirebaseConfiguredOrg("club-x", green)),
        )

        device.applyInstitutionSettingFromRemote(key, clubBList, 1L, sourceOrgId = "club-b")

        assertEquals(listOf("club-a", "club-b"), device.getFirebaseConfiguredOrgs().map { it.orgId })
    }

    @Test
    fun listThatLeavesOutItsOwnOrgIsIgnored() {
        val device = device(FirebaseConfiguredOrg("club-a", red))
        val foreign = FirebaseConfiguredOrgCodec.encode(listOf(FirebaseConfiguredOrg("club-x", green)))

        device.applyInstitutionSettingFromRemote(key, foreign, 1L, sourceOrgId = "club-a")

        assertEquals(listOf("club-a"), device.getFirebaseConfiguredOrgs().map { it.orgId })
    }

    @Test
    fun listFromAnOlderAppVersionStillApplies() {
        val device = device(FirebaseConfiguredOrg("club-a", red, displayName = "Club A"))
        // Older versions only write orgId and colorArgb.
        val legacy = """[{"orgId":"club-a","colorArgb":${blue}},{"orgId":"club-b","colorArgb":${green}}]"""

        device.applyInstitutionSettingFromRemote(key, legacy, 1L, sourceOrgId = "club-a")

        val orgs = device.getFirebaseConfiguredOrgs()
        assertEquals(listOf("club-a", "club-b"), orgs.map { it.orgId })
        assertEquals(blue, orgs.first().colorArgb)
    }
}

private class OrgSyncMemoryStorage : AppStorage {
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
