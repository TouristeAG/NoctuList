package com.eventmanager.app.data.sync

import com.eventmanager.app.platform.AppStorage
import com.eventmanager.app.portal.certificate.CertMail
import com.eventmanager.app.portal.certificate.CertificateTemplate
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CertificateSettingsTest {
    private val values = mutableMapOf<String, Any>()
    private var disk: String? = null

    @BeforeTest
    fun installFakeDisk() {
        disk = null
        CertificateTemplateStore.installDiskWriter { disk = it }
        CertificateTemplateStore.installDiskReader { disk }
    }

    private fun settings() = SettingsManager(CertMemoryStorage(values))

    private fun SettingsManager.rowValue(key: String): String? =
        getInstitutionSettingRows().firstOrNull { it.key == key }?.value

    private val sample = CertificateTemplate(
        language = "de",
        place = "Lausanne",
        mail = CertMail(subject = "Votre attestation"),
    )

    @Test
    fun certificateOffByDefault() {
        val s = settings()
        assertFalse(s.isVolunteerPortalCertificateEnabled())
        assertNull(s.volunteerPortalCertificateTemplate())
    }

    @Test
    fun switchRoundTripsAndSyncs() {
        val s = settings()
        s.setVolunteerPortalCertificateEnabled(true)
        assertTrue(s.isVolunteerPortalCertificateEnabled())
        assertEquals("true", s.rowValue(InstitutionSettingsKeys.VOLUNTEER_PORTAL_CERTIFICATE_ENABLED))
        s.applyInstitutionSettingFromRemote(
            InstitutionSettingsKeys.VOLUNTEER_PORTAL_CERTIFICATE_ENABLED, "false", Long.MAX_VALUE,
        )
        assertFalse(s.isVolunteerPortalCertificateEnabled())
    }

    @Test
    fun templateRoundTrip() {
        val s = settings()
        s.saveVolunteerPortalCertificateTemplate(sample)
        assertEquals(sample, s.volunteerPortalCertificateTemplate())
    }

    @Test
    fun prefsKeepOnlyAMarker() {
        val s = settings()
        s.saveVolunteerPortalCertificateTemplate(sample.copy(place = "x".repeat(20_000)))
        val stored = values.values.filterIsInstance<String>()
        assertTrue(stored.all { it.length < 64 }, "prefs must not hold the JSON")
        assertNotNull(s.volunteerPortalCertificateTemplate())
    }

    @Test
    fun savingTouchesLastModifiedSoSyncSendsIt() {
        val s = settings()
        s.saveVolunteerPortalCertificateTemplate(sample)
        val row = s.getInstitutionSettingRows()
            .first { it.key == InstitutionSettingsKeys.VOLUNTEER_PORTAL_CERTIFICATE_TEMPLATE }
        assertTrue(row.lastModified > 0L)
    }

    @Test
    fun syncValueIsTheJson() {
        val s = settings()
        s.saveVolunteerPortalCertificateTemplate(sample)
        val exported = s.rowValue(InstitutionSettingsKeys.VOLUNTEER_PORTAL_CERTIFICATE_TEMPLATE)
        assertNotNull(exported)
        assertEquals(sample, CertificateTemplate.parse(exported))
    }

    @Test
    fun templateFromSyncIsApplied() {
        val s = settings()
        s.applyInstitutionSettingFromRemote(
            InstitutionSettingsKeys.VOLUNTEER_PORTAL_CERTIFICATE_TEMPLATE, sample.toJson(), Long.MAX_VALUE,
        )
        assertEquals(sample, s.volunteerPortalCertificateTemplate())
    }

    @Test
    fun garbageFromSyncIsIgnored() {
        val s = settings()
        s.saveVolunteerPortalCertificateTemplate(sample)
        s.applyInstitutionSettingFromRemote(
            InstitutionSettingsKeys.VOLUNTEER_PORTAL_CERTIFICATE_TEMPLATE, "{", Long.MAX_VALUE,
        )
        assertEquals(sample, s.volunteerPortalCertificateTemplate())
    }

    @Test
    fun certificateKeysAreFirebaseOnly() {
        assertEquals("volunteer_portal_certificate_enabled", InstitutionSettingsKeys.VOLUNTEER_PORTAL_CERTIFICATE_ENABLED)
        assertEquals("volunteer_portal_certificate_template", InstitutionSettingsKeys.VOLUNTEER_PORTAL_CERTIFICATE_TEMPLATE)
        listOf(
            InstitutionSettingsKeys.VOLUNTEER_PORTAL_CERTIFICATE_ENABLED,
            InstitutionSettingsKeys.VOLUNTEER_PORTAL_CERTIFICATE_TEMPLATE,
        ).forEach {
            assertTrue(it in InstitutionSettingsKeys.ALL)
            assertFalse(InstitutionSettingsKeys.isSyncedToSheets(it))
        }
    }
}

private class CertMemoryStorage(private val values: MutableMap<String, Any>) : AppStorage {
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
    override fun clear() { values.clear() }
}
