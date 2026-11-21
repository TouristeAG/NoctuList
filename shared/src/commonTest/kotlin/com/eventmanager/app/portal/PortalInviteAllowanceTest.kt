package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.GuestForm
import com.eventmanager.app.data.models.GuestFormOfferedAccess
import com.eventmanager.app.data.models.GuestFormPerson
import com.eventmanager.app.data.models.GuestFormSubmission
import com.eventmanager.app.data.models.GuestFormSubmissionCodec
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals

class PortalInviteAllowanceTest {
    private val event = 1_800_000_000_000L

    private fun template(max: Int = 5, accesses: List<GuestFormOfferedAccess> = emptyList()) = GuestForm(
        formId = "TPL",
        portalUid = "U1",
        allowMultipleResponses = true,
        eventDateMillis = event,
        venueName = "Club",
        artistName = "Friends",
        maxGuests = max,
        offeredAccessIds = Json.encodeToString(ListSerializer(GuestFormOfferedAccess.serializer()), accesses),
    )

    private fun accepted(nano: String, access: String = "") = Guest(
        nanoId = nano, name = nano, invitations = 0, venueName = "Club", isTemporaryGuest = true,
        temporaryEventDate = event, temporaryVenueName = "Club", temporaryArtistName = "Friends",
        temporaryAccessIds = access, portalUid = "U1", portalEnabled = true,
    )

    private fun add(id: String, vararg people: Pair<String, Set<String>>, status: String = "PENDING_REVIEW") = GuestForm(
        formId = id, parentFormId = "TPL", portalUid = "U1", status = status, requestKind = "ADD",
        submissionJson = GuestFormSubmissionCodec.encode(GuestFormSubmission(people = people.map { GuestFormPerson(it.first, it.second) })),
    )

    private fun remove(id: String, vararg guests: Guest, status: String = "PENDING_REVIEW") = GuestForm(
        formId = id, parentFormId = "TPL", portalUid = "U1", status = status, requestKind = "REMOVE",
        removeGuestKeys = guests.joinToString(",") { TemporaryGuestPortalQrDocument.guestKey(it) },
    )

    @Test
    fun countsAcceptedAndPending() {
        val a = PortalInviteAllowance.of(template(max = 5), listOf(accepted("A"), accepted("B")), listOf(add("R1", "Cy" to emptySet())))
        assertEquals(5, a.maxGuests)
        assertEquals(2, a.remaining)
    }

    @Test
    fun decidedAnswersDoNotCount() {
        val a = PortalInviteAllowance.of(
            template(max = 5), listOf(accepted("A")),
            listOf(add("R1", "Cy" to emptySet(), status = "REJECTED"), add("R2", "Di" to emptySet(), status = "ACCEPTED")),
        )
        assertEquals(4, a.remaining)
    }

    @Test
    fun pendingRemoveFreesAPlace() {
        val b = accepted("B")
        val a = PortalInviteAllowance.of(template(max = 2), listOf(accepted("A"), b), listOf(remove("R1", b)))
        assertEquals(1, a.remaining)
    }

    @Test
    fun perAccessLimit() {
        val t = template(max = 10, accesses = listOf(GuestFormOfferedAccess("bs", "Backstage", 2), GuestFormOfferedAccess("vip", "VIP", 0)))
        val a = PortalInviteAllowance.of(t, listOf(accepted("A", "bs")), listOf(add("R1", "Cy" to setOf("bs", "vip"))))
        assertEquals(2 to 0, a.perAccess["bs"])
    }

    @Test
    fun zeroMeansNoAccessLimit() {
        val t = template(max = 10, accesses = listOf(GuestFormOfferedAccess("vip", "VIP", 0)))
        val a = PortalInviteAllowance.of(t, listOf(accepted("A", "vip")), emptyList())
        assertEquals(0 to 9, a.perAccess["vip"])
    }

    @Test
    fun neverNegative() {
        val a = PortalInviteAllowance.of(template(max = 1), listOf(accepted("A"), accepted("B")), listOf(add("R1", "Cy" to emptySet())))
        assertEquals(0, a.remaining)
        assertEquals(true, a.exceededBy(listOf("Di" to emptySet())))
        assertEquals(false, PortalInviteAllowance.of(template(max = 3), listOf(accepted("A")), emptyList()).exceededBy(listOf("Di" to emptySet(), "Ed" to emptySet())))
    }
}
