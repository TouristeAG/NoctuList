package com.eventmanager.app.ui.components.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SyncSettingsModelTest {
    @Test
    fun exactMinutesMapToTheirOption() {
        assertEquals(MirrorFrequency.HOURLY, MirrorFrequency.fromMinutes(60))
        assertEquals(MirrorFrequency.DAILY, MirrorFrequency.fromMinutes(1440))
        assertEquals(MirrorFrequency.MANUAL, MirrorFrequency.fromMinutes(0))
    }

    @Test
    fun negativeMinutesMeanManual() {
        assertEquals(MirrorFrequency.MANUAL, MirrorFrequency.fromMinutes(-5))
    }

    @Test
    fun largeIntervalShowsAsDaily() {
        assertEquals(MirrorFrequency.DAILY, MirrorFrequency.fromMinutes(2000))
    }

    @Test
    fun unknownIntervalKeepsStoredValue() {
        // The page reads the stored value through this pure mapping and only writes
        // when the user picks an option, so an odd stored value is displayed as its
        // nearest option without being rewritten.
        val stored = 45
        val shown = MirrorFrequency.fromMinutes(stored)
        assertEquals(MirrorFrequency.HOURLY, shown)
        assertEquals(MirrorFrequency.HOURLY, MirrorFrequency.fromMinutes(stored))
        assertEquals(45, stored)
    }

    @Test
    fun sheetsSyncMinutesMenu() {
        assertEquals(listOf(1, 5, 15, 30, 60), SHEETS_SYNC_MINUTES)
    }

    @Test
    fun connectionStateReadyWhenEverythingIsSet() {
        assertEquals(SyncConnectionState.READY, syncConnectionState(orgReady = true, projectReady = true, signedIn = true))
    }

    @Test
    fun connectionStateNeedsSignIn() {
        assertEquals(SyncConnectionState.NEEDS_SIGN_IN, syncConnectionState(orgReady = true, projectReady = true, signedIn = false))
    }

    @Test
    fun connectionStateNeedsConfigWhenOrgOrProjectMissing() {
        assertEquals(SyncConnectionState.NEEDS_CONFIG, syncConnectionState(orgReady = false, projectReady = true, signedIn = true))
        assertEquals(SyncConnectionState.NEEDS_CONFIG, syncConnectionState(orgReady = true, projectReady = false, signedIn = true))
        assertEquals(SyncConnectionState.NEEDS_CONFIG, syncConnectionState(orgReady = false, projectReady = false, signedIn = false))
    }

    @Test
    fun nonAdminSeesNoMemberActions() {
        assertFalse(memberActionsAllowed(isOrgAdmin = false, memberEmail = "jean@asso.ch", selfEmail = "admin@asso.ch"))
        assertFalse(memberActionsAllowed(isOrgAdmin = false, memberEmail = "jean@asso.ch", selfEmail = null))
    }

    @Test
    fun ownRowHasNoActions() {
        assertFalse(memberActionsAllowed(isOrgAdmin = true, memberEmail = "admin@asso.ch", selfEmail = "admin@asso.ch"))
        // Same address written differently is still one's own row.
        assertFalse(memberActionsAllowed(isOrgAdmin = true, memberEmail = " Admin@Asso.CH ", selfEmail = "admin@asso.ch"))
    }

    @Test
    fun blankMemberEmailHasNoActions() {
        assertFalse(memberActionsAllowed(isOrgAdmin = true, memberEmail = null, selfEmail = "admin@asso.ch"))
        assertFalse(memberActionsAllowed(isOrgAdmin = true, memberEmail = "  ", selfEmail = "admin@asso.ch"))
    }

    @Test
    fun adminCanActOnAnotherMember() {
        assertTrue(memberActionsAllowed(isOrgAdmin = true, memberEmail = "jean@asso.ch", selfEmail = "admin@asso.ch"))
    }

    @Test
    fun orgWithHashedCodeHasInvitationCode() {
        assertTrue(orgConfigHasInvitationCode(mapOf("bootstrapCodeHash" to "abc123")))
    }

    @Test
    fun orgWithLegacyCodeHasInvitationCode() {
        assertTrue(orgConfigHasInvitationCode(mapOf("bootstrapCode" to "abc123")))
    }

    @Test
    fun orgWithoutCodeHasNoInvitationCode() {
        assertFalse(orgConfigHasInvitationCode(mapOf("backendType" to "FIREBASE")))
        assertFalse(orgConfigHasInvitationCode(mapOf("bootstrapCodeHash" to "", "bootstrapCode" to null)))
    }

    @Test
    fun teamDomainsAreSuggestedWhenNoneIsVerified() {
        assertEquals(
            listOf("asso.ch", "club.org"),
            portalGoogleDomainSuggestions(listOf("@Asso.CH", "club.org", "asso.ch"), emptyList()),
        )
    }

    @Test
    fun verifiedTeamDomainsAreNotSuggestedAgain() {
        assertEquals(
            listOf("club.org"),
            portalGoogleDomainSuggestions(listOf("asso.ch", "club.org"), listOf("ASSO.ch")),
        )
        assertEquals(emptyList<String>(), portalGoogleDomainSuggestions(listOf("asso.ch"), listOf("asso.ch")))
    }

    @Test
    fun blankOrInvalidTeamDomainsAreNotSuggested() {
        assertEquals(emptyList<String>(), portalGoogleDomainSuggestions(listOf("", " ", "localhost", "@"), emptyList()))
        assertEquals(emptyList<String>(), portalGoogleDomainSuggestions(emptyList(), emptyList()))
    }

    @Test
    fun exportAgeUsesTheCoarsestUnit() {
        val now = 10_000_000_000L
        assertEquals(ExportAge(ExportAgeUnit.NOW, 0), exportAge(now - 30_000L, now))
        assertEquals(ExportAge(ExportAgeUnit.NOW, 0), exportAge(now + 5_000L, now))
        assertEquals(ExportAge(ExportAgeUnit.MINUTES, 5), exportAge(now - 5 * 60_000L, now))
        assertEquals(ExportAge(ExportAgeUnit.HOURS, 1), exportAge(now - 60 * 60_000L, now))
        assertEquals(ExportAge(ExportAgeUnit.HOURS, 23), exportAge(now - 23 * 3_600_000L - 59 * 60_000L, now))
        assertEquals(ExportAge(ExportAgeUnit.DAYS, 2), exportAge(now - 49 * 3_600_000L, now))
    }

    @Test
    fun exportAgeBoundaries() {
        val now = 10_000_000_000L
        assertEquals(ExportAge(ExportAgeUnit.NOW, 0), exportAge(now - 59_999L, now))
        assertEquals(ExportAge(ExportAgeUnit.MINUTES, 1), exportAge(now - 60_000L, now))
        assertEquals(ExportAge(ExportAgeUnit.MINUTES, 59), exportAge(now - 59 * 60_000L, now))
        assertEquals(ExportAge(ExportAgeUnit.HOURS, 1), exportAge(now - 60 * 60_000L, now))
        assertEquals(ExportAge(ExportAgeUnit.HOURS, 23), exportAge(now - 1439 * 60_000L, now))
        assertEquals(ExportAge(ExportAgeUnit.DAYS, 1), exportAge(now - 1440 * 60_000L, now))
    }

    @Test
    fun sheetsSyncIntervalShowsNearestOption() {
        assertEquals(1, nearestSheetsSyncMinutes(1))
        assertEquals(15, nearestSheetsSyncMinutes(15))
        assertEquals(1, nearestSheetsSyncMinutes(0))
        assertEquals(5, nearestSheetsSyncMinutes(10))
        assertEquals(30, nearestSheetsSyncMinutes(45))
        assertEquals(60, nearestSheetsSyncMinutes(90))
    }
}
