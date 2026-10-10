package com.eventmanager.app.data.sync

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstitutionSettingsKeysTest {
    @Test
    fun profilePhotosEnabledIsSyncedAcrossOrgDevices() {
        assertTrue(InstitutionSettingsKeys.PROFILE_PHOTOS_ENABLED in InstitutionSettingsKeys.ALL)
    }

    @Test
    fun announcementsBilleterieSendIsSyncedAcrossOrgDevices() {
        assertTrue(InstitutionSettingsKeys.ANNOUNCEMENTS_NON_ADMIN_SEND_ENABLED in InstitutionSettingsKeys.ALL)
    }

    @Test
    fun guestFormBilleterieValidateSyncsOverFirebaseButNotSheets() {
        assertTrue(InstitutionSettingsKeys.GUEST_FORMS_BILLETERIE_VALIDATE_ENABLED in InstitutionSettingsKeys.ALL)
        assertFalse(
            InstitutionSettingsKeys.isSyncedToSheets(
                InstitutionSettingsKeys.GUEST_FORMS_BILLETERIE_VALIDATE_ENABLED,
            ),
        )
    }

    /** Still pushed to Firebase devices, but a Sheets round-trip must not blank the catalogue. */
    @Test
    fun posSubcategoriesSyncOverFirebaseButNotSheets() {
        assertTrue(InstitutionSettingsKeys.POS_SUBCATEGORIES in InstitutionSettingsKeys.ALL)
        assertFalse(InstitutionSettingsKeys.isSyncedToSheets(InstitutionSettingsKeys.POS_SUBCATEGORIES))
    }

    @Test
    fun emailTitlesSyncAcrossOrgDevices() {
        assertTrue(InstitutionSettingsKeys.EMAIL_QR_HEADER in InstitutionSettingsKeys.ALL)
        assertTrue(InstitutionSettingsKeys.GUEST_EMAIL_HEADER in InstitutionSettingsKeys.ALL)
        assertTrue(InstitutionSettingsKeys.isSyncedToSheets(InstitutionSettingsKeys.EMAIL_QR_HEADER))
        assertTrue(InstitutionSettingsKeys.isSyncedToSheets(InstitutionSettingsKeys.GUEST_EMAIL_HEADER))
        assertTrue(InstitutionSettingsKeys.TEMP_GUEST_EMAIL_HEADER in InstitutionSettingsKeys.ALL)
        assertFalse(InstitutionSettingsKeys.isSyncedToSheets(InstitutionSettingsKeys.TEMP_GUEST_EMAIL_HEADER))
    }

    @Test
    fun everyOtherKeyStillReachesSheets() {
        val notSynced = InstitutionSettingsKeys.ALL.filterNot { InstitutionSettingsKeys.isSyncedToSheets(it) }
        assertTrue(
            notSynced.toSet() == InstitutionSettingsKeys.FIREBASE_ONLY_KEYS,
            "unexpected keys withheld from Sheets: $notSynced",
        )
    }
}
