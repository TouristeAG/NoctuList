package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.Volunteer

/**
 * Profiles to save so one address means one portal account (spec 2026-11-21 §5). Pure: the caller
 * bumps lastModified and saves. [conflicts] holds addresses already linked to two accounts.
 */
data class PortalMergePlan(
    val volunteers: List<Volunteer> = emptyList(),
    val guests: List<Guest> = emptyList(),
    val conflicts: Set<String> = emptySet(),
)

object PortalAccountMerger {

    private fun key(raw: String): String = raw.trim().lowercase()

    fun addressesOf(v: Volunteer): Set<String> = setOf(key(v.email), key(v.portalGoogleEmail)) - ""

    fun addressesOf(g: Guest): Set<String> =
        // A list also answers to the address its account was opened for, so editing the contact
        // keeps the opened account (shown as a mismatch in the space window, not split off).
        if (g.isTemporaryGuest) setOf(key(g.temporaryContactEmail), key(g.temporaryPortalEmail)) - ""
        else setOf(key(g.email), key(g.portalGoogleEmail)) - ""

    fun addressesOf(h: PortalHolder): Set<String> = setOf(key(h.email), key(h.portalGoogleEmail)) - ""

    fun conflictFor(addresses: Set<String>, plan: PortalMergePlan): Boolean =
        addresses.any { it in plan.conflicts }

    private class Holder(val addresses: Set<String>, val uid: String, val eligible: Boolean)

    fun plan(volunteers: List<Volunteer>, guests: List<Guest>, guestSpaceOn: Boolean, tempSpaceOn: Boolean, now: Long, offsetHours: Int): PortalMergePlan {
        fun eligible(g: Guest) = when {
            g.isVolunteerBenefit -> false
            // Past lists are never touched: joining them would rewrite history for nothing.
            g.isTemporaryGuest -> tempSpaceOn && (g.temporaryEventDate ?: 0L).let {
                it > 0L && TemporaryGuestPortalProjectionBuilder.visibleUntil(it, offsetHours) > now
            }
            else -> guestSpaceOn && !g.portalRevoked
        }
        val vHolders = volunteers.map { Holder(addressesOf(it), it.portalUid.trim(), !it.portalRevoked) }
        val gHolders = guests.map { Holder(addressesOf(it), it.portalUid.trim(), eligible(it)) }
        val all = vHolders + gHolders

        // A holder whose address changed leaves its account, but only when the rest of the account
        // still agrees on an address: with two disjoint profiles nothing says which one changed,
        // so neither leaves (an account is never emptied by this rule).
        fun shares(a: Holder, b: Holder) = a.addresses.any { it in b.addresses }
        val detached = all.filter { h ->
            if (h.uid.isEmpty()) return@filter false
            val mates = all.filter { it !== h && it.uid == h.uid }
            mates.none { shares(it, h) } &&
                mates.any { m -> mates.any { o -> o !== m && shares(m, o) } }
        }.toSet()
        fun uidOf(h: Holder) = if (h in detached) "" else h.uid

        val uidsByAddress = mutableMapOf<String, MutableSet<String>>()
        for (h in all) {
            val uid = uidOf(h)
            if (uid.isEmpty()) continue
            for (a in h.addresses) uidsByAddress.getOrPut(a) { mutableSetOf() } += uid
        }
        val conflicts = uidsByAddress.filterValues { it.size > 1 }.keys

        /** The single account this holder should join, or null. */
        fun target(h: Holder): String? {
            if (!h.eligible || uidOf(h).isNotEmpty()) return null
            if (h.addresses.any { it in conflicts }) return null
            val uids = h.addresses.flatMap { uidsByAddress[it].orEmpty() }.toSet()
            return uids.singleOrNull()
        }

        val vOut = volunteers.zip(vHolders).mapNotNull { (v, h) ->
            val t = target(h)
            when {
                t != null -> v.copy(portalUid = t, portalEnabled = true)
                h in detached -> v.copy(portalUid = "")
                else -> null
            }
        }
        /**
         * A list row joining account [uid] takes its list mates' state: device-created only when
         * every list row of the account is and no volunteer or permanent guest shares it, so the
         * page keeps (or loses) its unverified read exactly as before; same opened address.
         */
        fun joinedListRow(g: Guest, uid: String): Guest {
            val mates = guests.filter { it.isTemporaryGuest && it.portalUid == uid }
            val holder = volunteers.any { it.portalUid == uid } || guests.any { !it.isTemporaryGuest && it.portalUid == uid }
            val created = mates.isNotEmpty() && mates.all { it.temporaryPortalCreated } && !holder
            val opened = mates.map { it.temporaryPortalEmail.trim() }.firstOrNull { it.isNotEmpty() } ?: g.temporaryPortalEmail
            return g.copy(portalUid = uid, portalEnabled = true, temporaryPortalCreated = created, temporaryPortalEmail = opened)
        }

        val gOut = guests.zip(gHolders).mapNotNull { (g, h) ->
            val t = target(h)
            when {
                // A list the admin closed keeps its uid and stays closed: it never reaches here.
                t != null && g.isTemporaryGuest -> joinedListRow(g, t)
                t != null -> g.copy(portalUid = t, portalEnabled = true)
                h in detached -> g.copy(portalUid = "")
                else -> null
            }
        }
        return PortalMergePlan(vOut, gOut, conflicts)
    }
}
