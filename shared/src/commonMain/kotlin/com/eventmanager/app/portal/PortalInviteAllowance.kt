package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.GuestForm
import com.eventmanager.app.data.models.GuestFormRequestKind
import com.eventmanager.app.data.models.GuestFormStatus
import com.eventmanager.app.data.models.GuestFormSubmissionCodec
import com.eventmanager.app.data.models.offeredAccesses
import com.eventmanager.app.data.models.removeGuestKeyList
import com.eventmanager.app.data.models.requestKindValue
import com.eventmanager.app.data.models.statusValue

/**
 * Places left on a portal invite template for its whole event (spec 2026-11-21 §8.1).
 * [perAccess] maps an offered access id to (max, remaining); a max of 0 means no access limit,
 * and its remaining is then the overall [remaining].
 */
data class InviteAllowance(
    val maxGuests: Int,
    val remaining: Int,
    val perAccess: Map<String, Pair<Int, Int>>,
) {
    /** True when adding [people] (name, access ids) would go past a limit of the event. */
    fun exceededBy(people: List<Pair<String, Set<String>>>): Boolean {
        if (people.size > remaining) return true
        return perAccess.any { (id, limit) ->
            val (max, left) = limit
            max > 0 && people.count { id in it.second } > left
        }
    }
}

object PortalInviteAllowance {

    /** Guests of [batch] count when accepted; [responses] count while PENDING_REVIEW. */
    fun of(template: GuestForm, batch: List<Guest>, responses: List<GuestForm>): InviteAllowance {
        val pending = responses.filter { it.parentFormId == template.formId && it.statusValue == GuestFormStatus.PENDING_REVIEW }
        val removing = pending.filter { it.requestKindValue == GuestFormRequestKind.REMOVE }
            .flatMap { it.removeGuestKeyList() }.toSet()
        val kept = batch.filter { TemporaryGuestPortalQrDocument.guestKey(it) !in removing }
        val adds = pending.filter { it.requestKindValue == GuestFormRequestKind.ADD }
            .flatMap { GuestFormSubmissionCodec.decode(it.submissionJson)?.people.orEmpty() }

        val used = kept.size + adds.size
        val remaining = (template.maxGuests - used).coerceAtLeast(0)
        val perAccess = template.offeredAccesses().associate { access ->
            val taken = kept.count { access.id in accessIds(it) } + adds.count { access.id in it.requestedAccessIds }
            val left = if (access.maxRequests > 0) (access.maxRequests - taken).coerceAtLeast(0) else remaining
            access.id to (access.maxRequests to minOf(left, remaining))
        }
        return InviteAllowance(template.maxGuests, remaining, perAccess)
    }

    internal fun accessIdList(guest: Guest): List<String> =
        guest.temporaryAccessIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    internal fun accessIds(guest: Guest): Set<String> = accessIdList(guest).toSet()
}
