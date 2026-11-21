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

    fun plan(volunteers: List<Volunteer>, guests: List<Guest>, guestSpaceOn: Boolean, tempSpaceOn: Boolean): PortalMergePlan {
        fun eligible(g: Guest) = when {
            g.isVolunteerBenefit -> false
            g.isTemporaryGuest -> tempSpaceOn
            else -> guestSpaceOn && !g.portalRevoked
        }
        val vHolders = volunteers.map { Holder(addressesOf(it), it.portalUid.trim(), !it.portalRevoked) }
        val gHolders = guests.map { Holder(addressesOf(it), it.portalUid.trim(), eligible(it)) }
        val all = vHolders + gHolders

        // A holder sharing its uid with others but no address with any of them changed its address.
        val detached = all.filter { h ->
            if (h.uid.isEmpty()) return@filter false
            val mates = all.filter { it !== h && it.uid == h.uid }
            mates.isNotEmpty() && mates.none { m -> m.addresses.any { it in h.addresses } }
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
        val gOut = guests.zip(gHolders).mapNotNull { (g, h) ->
            val t = target(h)
            when {
                // A list the admin closed keeps its uid and stays closed: it never reaches here.
                t != null -> g.copy(portalUid = t, portalEnabled = true)
                h in detached -> g.copy(portalUid = "")
                else -> null
            }
        }
        return PortalMergePlan(vOut, gOut, conflicts)
    }
}
