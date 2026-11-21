package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.VenueAccess
import com.eventmanager.app.data.models.temporaryEntryValidated
import com.eventmanager.app.data.utils.DateTimeUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * `guestListPortal/{uid}`: the whole temporary guest list of each date (and venue) an account was
 * given (spec 2026-11-21 guest list access §5.2). Never a nanoId, never a QR code.
 */
object GuestListPortalProjectionBuilder {
    const val COLLECTION = "guestListPortal"
    const val SCHEMA = 1

    private fun day(millis: Long) = DateTimeUtils.getStartOfDayWithOffset(millis, 0)

    internal fun venueOf(g: Guest): String = g.temporaryVenueName.ifBlank { g.venueName }.trim()

    /** Whether [g] is on the list [grant] covers. */
    fun covers(grant: GuestListGrant, g: Guest): Boolean =
        g.isTemporaryGuest && g.temporaryEventDate != null && day(g.temporaryEventDate!!) == day(grant.eventDateMillis) &&
            (grant.venueName.isBlank() || venueOf(g).equals(grant.venueName.trim(), ignoreCase = true))

    /** Active grants, one per date and venue; an "every venue" grant absorbs the venue ones of its date. */
    fun effective(grants: List<GuestListGrant>, now: Long): List<GuestListGrant> =
        grants.filter { it.isActive(now) }
            .groupBy { day(it.eventDateMillis) }
            .flatMap { (_, sameDay) ->
                val all = sameDay.firstOrNull { it.venueName.isBlank() }
                all?.let { listOf(it) } ?: sameDay.distinctBy { it.venueName.trim().lowercase() }
            }
            .sortedWith(compareBy({ it.eventDateMillis }, { it.venueName.lowercase() }))

    fun build(
        grants: List<GuestListGrant>,
        guests: List<Guest>,
        accesses: List<VenueAccess>,
        associationName: String,
        now: Long,
    ): PortalProjection? {
        val active = effective(grants, now)
        if (active.isEmpty()) return null
        val label = PortalProjectionBuilder.MAX_LABEL
        val accessNames = accesses.associate { it.id to it.name }
        val payload = buildJsonObject {
            put("association", associationName.trim().take(label))
            put("grants", JsonArray(active.map { grant ->
                val members = guests.filter { covers(grant, it) }
                    .sortedWith(compareBy({ it.temporaryArtistName.trim().lowercase() }, { it.name.trim().lowercase() }, { it.lastNameAbbreviation.lowercase() }))
                buildJsonObject {
                    put("grantId", grant.id)
                    put("eventDate", grant.eventDateMillis)
                    put("venueName", grant.venueName.trim())
                    put("visibleUntil", TemporaryGuestPortalProjectionBuilder.visibleUntil(grant.eventDateMillis))
                    put("lists", JsonArray(
                        members.map { it.temporaryArtistName.trim() to venueOf(it) }.distinct().map { (name, venue) ->
                            buildJsonObject { put("name", name.take(label)); put("venueName", venue.take(label)) }
                        },
                    ))
                    put("accesses", JsonArray(
                        accesses.filter { grant.venueName.isBlank() || it.venueName.trim().equals(grant.venueName.trim(), ignoreCase = true) }
                            .map { a -> buildJsonObject { put("id", a.id); put("name", a.name); put("venueName", a.venueName) } },
                    ))
                    put("guests", JsonArray(members.map { g ->
                        val ids = PortalInviteAllowance.accessIdList(g)
                        buildJsonObject {
                            put("guestKey", TemporaryGuestPortalQrDocument.guestKey(g))
                            put("firstName", g.name.trim())
                            put("lastName", g.lastNameAbbreviation.trim())
                            put("listName", g.temporaryArtistName.trim().take(label))
                            put("venueName", venueOf(g).take(label))
                            put("accessIds", JsonArray(ids.map { JsonPrimitive(it) }))
                            put("accessNames", JsonArray(ids.mapNotNull { accessNames[it] }.map { JsonPrimitive(it) }))
                            put("entryUsed", g.temporaryEntryValidated)
                            put("emergencyPhone", g.temporaryContactPhone.trim())
                        }
                    }))
                }
            }))
        }.toString()
        return PortalProjection(
            payload = payload,
            contentHash = PortalProjectionBuilder.sha256(payload),
            schema = SCHEMA,
            extraFields = mapOf(
                "expiresAtMillis" to active.maxOf { TemporaryGuestPortalProjectionBuilder.visibleUntil(it.eventDateMillis) },
                "grantDates" to active.map { it.eventDateMillis },
            ),
        )
    }
}
