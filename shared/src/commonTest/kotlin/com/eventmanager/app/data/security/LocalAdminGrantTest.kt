package com.eventmanager.app.data.security

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.remote.BackendType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LocalAdminGrantTest {

    @Test
    fun firebaseRoleAcceptsAdminAndLegacyDoorIsNotAdmin() {
        assertTrue(firebaseRoleIsOrgAdmin("admin"))
        assertTrue(firebaseRoleIsOrgAdmin("ADMIN"))
        assertFalse(firebaseRoleIsOrgAdmin("member"))
        assertFalse(firebaseRoleIsOrgAdmin("door"))
        assertFalse(firebaseRoleIsOrgAdmin("pos"))
        assertFalse(firebaseRoleIsOrgAdmin(null))
    }

    @Test
    fun projectSecretsNeedFirebaseOrgAdminOnceConfigured() {
        assertTrue(canRevealFirebaseProjectSecrets(isFirebaseOrgAdmin = true, projectConfigured = true))
        assertTrue(canRevealFirebaseProjectSecrets(isFirebaseOrgAdmin = false, projectConfigured = false))
        assertFalse(canRevealFirebaseProjectSecrets(isFirebaseOrgAdmin = false, projectConfigured = true))
        assertTrue(
            canRevealFirebaseProjectSecrets(
                isFirebaseOrgAdmin = false,
                projectConfigured = true,
                oauthCredentialsReady = false,
            )
        )
        assertTrue(firebaseOAuthCredentialsReady("client.apps.googleusercontent.com", "secret"))
        assertFalse(firebaseOAuthCredentialsReady("client.apps.googleusercontent.com", ""))
    }

    @Test
    fun syncedInstitutionSettingsEditableOnSheetsOrFirebaseAdmin() {
        assertTrue(canEditSyncedInstitutionSettings(BackendType.SHEETS, isFirebaseOrgAdmin = false))
        assertTrue(canEditSyncedInstitutionSettings(BackendType.FIREBASE, isFirebaseOrgAdmin = true))
        assertFalse(canEditSyncedInstitutionSettings(BackendType.FIREBASE, isFirebaseOrgAdmin = false))
    }

    @Test
    fun billeterieAnnouncementSendEditableOnlyByFirebaseOrgAdmin() {
        assertFalse(canEditAnnouncementsNonAdminSendSetting(BackendType.SHEETS, isFirebaseOrgAdmin = true))
        assertFalse(canEditAnnouncementsNonAdminSendSetting(BackendType.SHEETS, isFirebaseOrgAdmin = false))
        assertTrue(canEditAnnouncementsNonAdminSendSetting(BackendType.FIREBASE, isFirebaseOrgAdmin = true))
        assertFalse(canEditAnnouncementsNonAdminSendSetting(BackendType.FIREBASE, isFirebaseOrgAdmin = false))
    }

    @Test
    fun sectionHiddenOnSheetsAndReadOnly() {
        val guest = Guest(name = "Ada", invitations = 1, venueName = "Groove")
        assertFalse(
            shouldShowLocalAdminRightsSection(
                backendType = BackendType.SHEETS,
                readOnly = false,
                guest = guest,
            )
        )
        assertFalse(
            shouldShowLocalAdminRightsSection(
                backendType = BackendType.FIREBASE,
                readOnly = true,
                guest = guest,
            )
        )
        assertTrue(
            shouldShowLocalAdminRightsSection(
                backendType = BackendType.FIREBASE,
                readOnly = false,
                guest = guest,
            )
        )
    }

    @Test
    fun tempAndBenefitGuestsAreNotEligible() {
        val temp = Guest(name = "Temp", invitations = 1, venueName = "Groove", isTemporaryGuest = true)
        val benefit = Guest(name = "Ben", invitations = 1, venueName = "Groove", isVolunteerBenefit = true)
        assertFalse(guestEligibleForLocalAdminRights(temp))
        assertFalse(guestEligibleForLocalAdminRights(benefit))
        assertFalse(
            shouldShowLocalAdminRightsSection(
                backendType = BackendType.FIREBASE,
                readOnly = false,
                guest = temp,
            )
        )
    }

    @Test
    fun lastLocalAdminCannotBeRevoked() {
        val admin = Guest(name = "Ada", invitations = 1, venueName = "Groove", isAdmin = true, firebaseOrgId = "org-a")
        val member = Volunteer(name = "Bob", lastNameAbbreviation = "B", email = "", phoneNumber = "", firebaseOrgId = "org-a")
        assertTrue(
            wouldRemoveLastLocalAdmin(
                makeAdmin = false,
                targetCurrentlyAdmin = true,
                guests = listOf(admin),
                volunteers = listOf(member),
                orgId = "org-a",
            )
        )
        val second = Volunteer(
            name = "Cara",
            lastNameAbbreviation = "C",
            email = "",
            phoneNumber = "",
            isAdmin = true,
            firebaseOrgId = "org-a",
        )
        assertFalse(
            wouldRemoveLastLocalAdmin(
                makeAdmin = false,
                targetCurrentlyAdmin = true,
                guests = listOf(admin),
                volunteers = listOf(second),
                orgId = "org-a",
            )
        )
        assertEquals(2, countLocalAdminsInOrg(listOf(admin), listOf(second), "org-a"))
    }

    @Test
    fun profileBelongsToAdminOrgStrictMultiOrgRequiresExactMatch() {
        assertTrue(profileBelongsToAdminOrg("org-a", "org-a", strictMultiOrg = true))
        assertFalse(profileBelongsToAdminOrg("", "org-a", strictMultiOrg = true))
        assertFalse(profileBelongsToAdminOrg("org-b", "org-a", strictMultiOrg = true))
    }

    @Test
    fun profileBelongsToAdminOrgNonStrictAllowsBlankProfileOrg() {
        assertTrue(profileBelongsToAdminOrg("", "org-a", strictMultiOrg = false))
        assertTrue(profileBelongsToAdminOrg("org-a", "org-a", strictMultiOrg = false))
    }

    @Test
    fun institutionHasLocalAdminInOrgCountsOnlyMatchingOrg() {
        val adminA = Guest(name = "Ada", invitations = 1, venueName = "Groove", isAdmin = true, firebaseOrgId = "org-a")
        val adminB = Volunteer(
            name = "Bob",
            lastNameAbbreviation = "B",
            email = "",
            phoneNumber = "",
            isAdmin = true,
            firebaseOrgId = "org-b",
        )
        assertTrue(institutionHasLocalAdminInOrg(listOf(adminA), listOf(adminB), "org-a", strictMultiOrg = true))
        assertFalse(institutionHasLocalAdminInOrg(listOf(adminA), emptyList(), "org-b", strictMultiOrg = true))
        assertTrue(institutionHasLocalAdminInOrg(listOf(adminA), listOf(adminB), "org-b", strictMultiOrg = true))
    }

    @Test
    fun shouldOfferFirstAdminSetupOnlyForEmptyRosterAfterSync() {
        assertTrue(shouldOfferFirstAdminSetupAfterSync(syncSucceeded = true, hasLocalAdmin = false, memberCount = 0))
        assertFalse(shouldOfferFirstAdminSetupAfterSync(syncSucceeded = true, hasLocalAdmin = false, memberCount = 3))
        assertFalse(shouldOfferFirstAdminSetupAfterSync(syncSucceeded = true, hasLocalAdmin = true, memberCount = 0))
        assertFalse(shouldOfferFirstAdminSetupAfterSync(syncSucceeded = false, hasLocalAdmin = false, memberCount = 0))
        assertFalse(shouldOfferFirstAdminAfterSkippedStartupSync())
    }

    @Test
    fun suspiciousMissingAdminWhenMembersPresentWithoutAdmin() {
        assertTrue(isSuspiciousMissingAdminAfterSync(syncSucceeded = true, hasLocalAdmin = false, memberCount = 2))
        assertFalse(isSuspiciousMissingAdminAfterSync(syncSucceeded = true, hasLocalAdmin = true, memberCount = 2))
        assertFalse(isSuspiciousMissingAdminAfterSync(syncSucceeded = true, hasLocalAdmin = false, memberCount = 0))
    }
}
