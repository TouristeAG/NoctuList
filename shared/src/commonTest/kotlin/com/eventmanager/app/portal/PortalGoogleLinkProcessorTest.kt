package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Volunteer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PortalGoogleLinkProcessorTest {
    private val googleUid = "GoogleUid000000000000001"
    private val domains = listOf("asso.ch")
    private val now = 1_800_000_000_000L
    private val code = "abcdefghijklmnopqrstuvwxyz0123456789_-AB"

    private fun volunteer(
        id: String,
        uid: String = "PortalUid$id",
        googleEmail: String = "",
        googleUid: String = "",
        linkedBy: String = "",
        enabled: Boolean = true,
    ) = Volunteer(
        id = id,
        name = "Léa",
        lastNameAbbreviation = "MARTIN",
        email = "$id@example.org",
        phoneNumber = "+41 79 000 00 00",
        portalEnabled = enabled,
        portalUid = uid,
        portalGoogleEmail = googleEmail,
        portalGoogleUid = googleUid,
        portalGoogleLinkedBy = linkedBy,
    )

    private fun adminClaim(email: String = "jean@asso.ch") =
        PortalGoogleClaim(googleUid, PortalGoogleClaimType.ADMIN_MATCH, "PENDING", email)

    private fun codeClaim(email: String = "jean@asso.ch", value: String = code) =
        PortalGoogleClaim(googleUid, PortalGoogleClaimType.LINK_CODE, "PENDING", email, value)

    private fun linkRequest(uid: String, hash: String = PortalProjectionBuilder.sha256(code)) = PortalRequest(
        requestId = "${uid}_GOOGLE_LINK",
        uid = uid,
        type = PortalRequestType.GOOGLE_LINK,
        status = "PENDING",
        email = "lea@example.org",
        codeHash = hash,
    )

    @Test
    fun adminMatchLinksTheOnlyVolunteerWithThatAddress() {
        val v = volunteer("V1", googleEmail = " Jean@Asso.CH ", linkedBy = Volunteer.GOOGLE_LINKED_BY_ADMIN)
        val decision = PortalGoogleLinkProcessor.adminMatch(adminClaim(), listOf(v, volunteer("V2")), domains, null)
        assertEquals(GoogleLinkDecision.Link(v, "ADMIN"), decision)
    }

    @Test
    fun adminMatchNeedsOpenPortalAccess() {
        val noUid = volunteer("V1", uid = "", googleEmail = "jean@asso.ch")
        val closed = volunteer("V2", enabled = false, googleEmail = "jean@asso.ch")
        assertEquals(
            GoogleLinkDecision.Reject("NO_MATCH"),
            PortalGoogleLinkProcessor.adminMatch(adminClaim(), listOf(noUid, closed), domains, null),
        )
    }

    @Test
    fun ambiguousAdminMatchIsRejected() {
        val volunteers = listOf(volunteer("V1", googleEmail = "jean@asso.ch"), volunteer("V2", googleEmail = "JEAN@asso.ch"))
        assertEquals(
            GoogleLinkDecision.Reject("AMBIGUOUS"),
            PortalGoogleLinkProcessor.adminMatch(adminClaim(), volunteers, domains, null),
        )
    }

    @Test
    fun domainOutsideListIsRejected() {
        val v = volunteer("V1", googleEmail = "jean@other.ch")
        assertEquals(
            GoogleLinkDecision.Reject("DOMAIN"),
            PortalGoogleLinkProcessor.adminMatch(adminClaim("jean@other.ch"), listOf(v), domains, null),
        )
        assertEquals(
            GoogleLinkDecision.Reject("DOMAIN"),
            PortalGoogleLinkProcessor.linkCode(
                codeClaim("jean@other.ch"), linkRequest("PortalUidV1"), now - 1_000, now, listOf(volunteer("V1")), domains, null,
            ),
        )
    }

    @Test
    fun staleAliasIsReplaced() {
        val v = volunteer("V1", googleEmail = "jean@asso.ch")
        val decision = PortalGoogleLinkProcessor.adminMatch(adminClaim(), listOf(v), domains, "PortalUidGone")
        assertEquals(GoogleLinkDecision.Link(v, "ADMIN"), decision)
        // Alias to a volunteer who no longer carries this Google account is stale too.
        assertEquals(false, PortalGoogleLinkProcessor.isAliasValid(googleUid, "PortalUidV1", listOf(v)))
    }

    @Test
    fun existingAliasAnswersAlreadyLinked() {
        val v = volunteer("V1", googleEmail = "jean@asso.ch", googleUid = googleUid, linkedBy = "ADMIN")
        assertEquals(
            GoogleLinkDecision.AlreadyLinked,
            PortalGoogleLinkProcessor.adminMatch(adminClaim(), listOf(v), domains, "PortalUidV1"),
        )
        assertEquals(true, PortalGoogleLinkProcessor.isAliasValid(googleUid, "PortalUidV1", listOf(v)))
    }

    @Test
    fun linkCodeLinksTheRequester() {
        val v = volunteer("V1")
        val decision = PortalGoogleLinkProcessor.linkCode(
            codeClaim(), linkRequest("PortalUidV1"), now - 60_000, now, listOf(v, volunteer("V2")), domains, null,
        )
        assertEquals(GoogleLinkDecision.Link(v, "VOLUNTEER"), decision)
    }

    @Test
    fun linkCodeRetryAfterTheLinkWasWrittenAnswersAlreadyLinked() {
        val v = volunteer("V1", googleEmail = "jean@asso.ch", googleUid = googleUid, linkedBy = "VOLUNTEER")
        assertEquals(
            GoogleLinkDecision.AlreadyLinked,
            PortalGoogleLinkProcessor.linkCode(codeClaim(), linkRequest("PortalUidV1"), now - 60_000, now, listOf(v), domains, "PortalUidV1"),
        )
    }

    @Test
    fun wrongCodeIsRejected() {
        val volunteers = listOf(volunteer("V1"))
        val other = "zzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzzz"
        assertEquals(
            GoogleLinkDecision.Reject("CODE"),
            PortalGoogleLinkProcessor.linkCode(codeClaim(value = other), linkRequest("PortalUidV1"), now - 1_000, now, volunteers, domains, null),
        )
        assertEquals(
            GoogleLinkDecision.Reject("CODE"),
            PortalGoogleLinkProcessor.linkCode(codeClaim(), null, null, now, volunteers, domains, null),
        )
        assertEquals(
            GoogleLinkDecision.Reject("CODE"),
            PortalGoogleLinkProcessor.linkCode(
                codeClaim(), linkRequest("PortalUidV1").copy(status = "DONE"), now - 1_000, now, volunteers, domains, null,
            ),
        )
    }

    @Test
    fun expiredCodeIsRejected() {
        val volunteers = listOf(volunteer("V1"))
        val validity = PortalGoogleLinkProcessor.LINK_CODE_VALIDITY_MS
        assertEquals(10L * 60 * 1000, validity)
        assertEquals(
            GoogleLinkDecision.Reject("CODE"),
            PortalGoogleLinkProcessor.linkCode(codeClaim(), linkRequest("PortalUidV1"), now - validity - 1, now, volunteers, domains, null),
        )
        assertEquals(
            GoogleLinkDecision.Reject("CODE"),
            PortalGoogleLinkProcessor.linkCode(codeClaim(), linkRequest("PortalUidV1"), null, now, volunteers, domains, null),
        )
    }

    @Test
    fun volunteerWithAdminAddressCannotLinkAnother() {
        val v = volunteer("V1", googleEmail = "boss@asso.ch", linkedBy = Volunteer.GOOGLE_LINKED_BY_ADMIN)
        assertEquals(
            GoogleLinkDecision.Reject("ALREADY_LINKED"),
            PortalGoogleLinkProcessor.linkCode(codeClaim(), linkRequest("PortalUidV1"), now - 1_000, now, listOf(v), domains, null),
        )
    }

    @Test
    fun googleAccountOfAnotherVolunteerIsTaken() {
        val other = volunteer("V2", googleEmail = "old@asso.ch", googleUid = googleUid, linkedBy = "VOLUNTEER")
        assertEquals(
            GoogleLinkDecision.Reject("TAKEN"),
            PortalGoogleLinkProcessor.linkCode(
                codeClaim(), linkRequest("PortalUidV1"), now - 1_000, now, listOf(volunteer("V1"), other), domains, "PortalUidV2",
            ),
        )
    }

    @Test
    fun addressUsedByAnotherVolunteerIsTaken() {
        val other = volunteer("V2", googleEmail = "JEAN@asso.ch", linkedBy = "ADMIN")
        assertEquals(
            GoogleLinkDecision.Reject("TAKEN"),
            PortalGoogleLinkProcessor.linkCode(codeClaim(), linkRequest("PortalUidV1"), now - 1_000, now, listOf(volunteer("V1"), other), domains, null),
        )
    }

    @Test
    fun linkCodeForAVolunteerWithoutAccessFindsNoMatch() {
        assertEquals(
            GoogleLinkDecision.Reject("NO_MATCH"),
            PortalGoogleLinkProcessor.linkCode(
                codeClaim(), linkRequest("PortalUidV1"), now - 1_000, now, listOf(volunteer("V1", enabled = false)), domains, null,
            ),
        )
    }

    @Test
    fun canUnlinkOnlyVolunteerLinks() {
        assertNull(PortalGoogleLinkProcessor.canUnlink(volunteer("V1", googleEmail = "a@asso.ch", linkedBy = "VOLUNTEER")))
        assertEquals("ADMIN_LINK", PortalGoogleLinkProcessor.canUnlink(volunteer("V1", googleEmail = "a@asso.ch", linkedBy = "ADMIN")))
        assertEquals("NO_ACCESS", PortalGoogleLinkProcessor.canUnlink(null))
    }
}
