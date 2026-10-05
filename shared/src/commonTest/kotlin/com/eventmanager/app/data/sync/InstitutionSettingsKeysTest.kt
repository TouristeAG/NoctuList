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
    fun everyOtherKeyStillReachesSheets() {
        val notSynced = InstitutionSettingsKeys.ALL.filterNot { InstitutionSettingsKeys.isSyncedToSheets(it) }
        assertTrue(
            notSynced.toSet() == InstitutionSettingsKeys.FIREBASE_ONLY_KEYS,
            "unexpected keys withheld from Sheets: $notSynced",
        )
    }
}
