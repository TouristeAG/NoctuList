package com.eventmanager.app.data.remote

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InstitutionBackendGuardTest {

    @Test
    fun alreadyOnFirebase_doesNotPromptForLeftoverMigrationId() {
        val announcement = announcement(BackendType.FIREBASE, migrationId = "mig-from-years-ago")
        assertFalse(shouldPromptInstitutionBackendFollow(announcement, BackendType.FIREBASE))
    }

    @Test
    fun alreadyOnFirebase_doesNotPromptWhenFollowedIdWasNeverStored() {
        val announcement = announcement(BackendType.FIREBASE, migrationId = "mig-1")
        assertFalse(shouldPromptInstitutionBackendFollow(announcement, BackendType.FIREBASE))
    }

    @Test
    fun sheetsDevice_promptsWhenInstitutionMovedToFirebase() {
        val announcement = announcement(BackendType.FIREBASE, migrationId = "mig-1")
        assertTrue(shouldPromptInstitutionBackendFollow(announcement, BackendType.SHEETS))
    }

    @Test
    fun firebaseDevice_promptsWhenInstitutionMovedBackToSheets() {
        val announcement = announcement(BackendType.SHEETS, migrationId = "mig-2")
        assertTrue(shouldPromptInstitutionBackendFollow(announcement, BackendType.FIREBASE))
    }

    @Test
    fun alreadyOnSheets_doesNotPromptForSheetsAnnouncement() {
        val announcement = announcement(BackendType.SHEETS, migrationId = "mig-sheets")
        assertFalse(shouldPromptInstitutionBackendFollow(announcement, BackendType.SHEETS))
    }

    private fun announcement(type: BackendType, migrationId: String) = InstitutionBackendAnnouncement(
        backendType = type,
        migrationId = migrationId,
        migratedAt = 1L,
        firebaseOrgId = "org-a",
    )
}
