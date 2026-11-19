package com.eventmanager.app.data.sync

import com.eventmanager.app.platform.AppStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsManagerPortalQrTest {
    private fun settings() = SettingsManager(MemoryStorage())

    private fun SettingsManager.rowValue(key: String): String? =
        getInstitutionSettingRows().firstOrNull { it.key == key }?.value

    @Test
    fun qrEnabledByDefault() = assertTrue(settings().isVolunteerPortalQrEnabled())

    @Test
    fun qrSettingRoundTripsAsInstitutionSetting() {
        val s = settings()
        s.setVolunteerPortalQrEnabled(false)
        assertFalse(s.isVolunteerPortalQrEnabled())
        assertEquals("false", s.rowValue(InstitutionSettingsKeys.VOLUNTEER_PORTAL_QR_ENABLED))
        assertFalse(InstitutionSettingsKeys.isSyncedToSheets(InstitutionSettingsKeys.VOLUNTEER_PORTAL_QR_ENABLED))
    }

    @Test
    fun automaticAccessAndGoogleRuleAreOffByDefault() {
        val s = settings()
        assertFalse(s.isVolunteerPortalAutoAccess())
        assertFalse(s.isVolunteerPortalGoogleAuto())
        assertEquals("", s.volunteerPortalGoogleTemplate())
    }

    @Test
    fun automaticSettingsRoundTripAsFirebaseOnlyInstitutionSettings() {
        val s = settings()
        s.setVolunteerPortalAutoAccess(true)
        s.setVolunteerPortalGoogleAuto(true)
        s.setVolunteerPortalGoogleTemplate("  {prénom}.{nom}@asso.ch ")
        assertEquals("true", s.rowValue(InstitutionSettingsKeys.VOLUNTEER_PORTAL_AUTO_ACCESS))
        assertEquals("true", s.rowValue(InstitutionSettingsKeys.VOLUNTEER_PORTAL_GOOGLE_AUTO))
        assertEquals("{prénom}.{nom}@asso.ch", s.rowValue(InstitutionSettingsKeys.VOLUNTEER_PORTAL_GOOGLE_TEMPLATE))
        listOf(
            InstitutionSettingsKeys.VOLUNTEER_PORTAL_AUTO_ACCESS,
            InstitutionSettingsKeys.VOLUNTEER_PORTAL_GOOGLE_AUTO,
            InstitutionSettingsKeys.VOLUNTEER_PORTAL_GOOGLE_TEMPLATE,
        ).forEach {
            assertTrue(it in InstitutionSettingsKeys.ALL)
            assertFalse(InstitutionSettingsKeys.isSyncedToSheets(it))
        }
    }

    @Test
    fun automaticSettingsApplyFromAnotherDevice() {
        val s = settings()
        s.applyInstitutionSettingFromRemote(InstitutionSettingsKeys.VOLUNTEER_PORTAL_AUTO_ACCESS, "true", 10L)
        s.applyInstitutionSettingFromRemote(InstitutionSettingsKeys.VOLUNTEER_PORTAL_GOOGLE_AUTO, "TRUE", 10L)
        s.applyInstitutionSettingFromRemote(InstitutionSettingsKeys.VOLUNTEER_PORTAL_GOOGLE_TEMPLATE, "{p}.{nom}@asso.ch", 10L)
        assertTrue(s.isVolunteerPortalAutoAccess())
        assertTrue(s.isVolunteerPortalGoogleAuto())
        assertEquals("{p}.{nom}@asso.ch", s.volunteerPortalGoogleTemplate())
        s.applyInstitutionSettingFromRemote(InstitutionSettingsKeys.VOLUNTEER_PORTAL_AUTO_ACCESS, "false", 11L)
        assertFalse(s.isVolunteerPortalAutoAccess())
    }
}

private class MemoryStorage : AppStorage {
    private val values = mutableMapOf<String, Any>()

    override fun getString(key: String, default: String): String =
        values[key] as? String ?: default

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun getBoolean(key: String, default: Boolean): Boolean =
        values[key] as? Boolean ?: default

    override fun putBoolean(key: String, value: Boolean) {
        values[key] = value
    }

    override fun getInt(key: String, default: Int): Int = values[key] as? Int ?: default

    override fun putInt(key: String, value: Int) {
        values[key] = value
    }

    override fun getLong(key: String, default: Long): Long = values[key] as? Long ?: default

    override fun putLong(key: String, value: Long) {
        values[key] = value
    }

    override fun getFloat(key: String, default: Float): Float = values[key] as? Float ?: default

    override fun putFloat(key: String, value: Float) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    override fun contains(key: String): Boolean = values.containsKey(key)

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String, default: Set<String>): Set<String> =
        values[key] as? Set<String> ?: default

    override fun putStringSet(key: String, value: Set<String>) {
        values[key] = value
    }

    override fun clear() {
        values.clear()
    }
}
