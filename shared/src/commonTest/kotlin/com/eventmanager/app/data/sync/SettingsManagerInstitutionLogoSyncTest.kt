package com.eventmanager.app.data.sync

import com.eventmanager.app.platform.AppStorage
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The association logo set on one device must survive until it reaches Firestore: a pull or a
 * listener snapshot that still holds the older server value must not erase it.
 */
class SettingsManagerInstitutionLogoSyncTest {
    private val key = InstitutionSettingsKeys.INSTITUTION_LOGO_PNG
    private var disk: ByteArray? = null
    private val logo = InstitutionLogoStore.encode(byteArrayOf(1, 2, 3, 4, 5))

    @BeforeTest
    fun installFakeDisk() {
        disk = null
        InstitutionLogoStore.installDiskWriter { disk = it }
        InstitutionLogoStore.installDiskReader { disk }
    }

    @AfterTest
    fun removeFakeDisk() {
        InstitutionLogoStore.installDiskWriter { }
        InstitutionLogoStore.installDiskReader { null }
    }

    private fun device(): SettingsManager = SettingsManager(LogoMemoryStorage()).apply {
        getInstitutionSettingRowsPendingRemotePush().forEach { markInstitutionSettingRowPushed(it) }
    }

    private fun SettingsManager.isLogoPending(): Boolean =
        getInstitutionSettingRowsPendingRemotePush().any { it.key == key }

    @Test
    fun aStaleEmptyServerValueDoesNotEraseALogoNotPushedYet() {
        val admin = device()
        admin.saveInstitutionLogoPng(logo)
        val savedAt = admin.getInstitutionSettingLastModified(key)

        // The pull that runs before the push lands still sees the old, empty server document.
        admin.applyInstitutionSettingFromRemote(key, "", savedAt - 60_000L)

        assertEquals(logo, admin.getInstitutionLogoPng())
        assertTrue(disk != null, "the logo file on disk must not be deleted")
        assertTrue(admin.isLogoPending(), "the logo must still be waiting to be pushed")
    }

    @Test
    fun aNewerServerValueStillReplacesALocalLogoNotPushedYet() {
        val admin = device()
        admin.saveInstitutionLogoPng(logo)
        val savedAt = admin.getInstitutionSettingLastModified(key)
        val other = InstitutionLogoStore.encode(byteArrayOf(9, 9, 9))

        admin.applyInstitutionSettingFromRemote(key, other, savedAt + 60_000L)

        assertEquals(other, admin.getInstitutionLogoPng())
        assertTrue(!admin.isLogoPending())
    }

    @Test
    fun aPushedLogoFollowsTheServer() {
        val member = device()
        member.saveInstitutionLogoPng(logo)
        member.getInstitutionSettingRowsPendingRemotePush().forEach { member.markInstitutionSettingRowPushed(it) }
        val savedAt = member.getInstitutionSettingLastModified(key)

        // Another admin removed the logo; the server is the reference once nothing is pending here.
        member.applyInstitutionSettingFromRemote(key, "", savedAt - 1L)

        assertEquals("", member.getInstitutionLogoPng())
    }
}

private class LogoMemoryStorage : AppStorage {
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
