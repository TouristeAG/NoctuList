package com.eventmanager.app.portal

import com.eventmanager.app.data.models.AccountHolderType
import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.temporaryBatchKey
import com.eventmanager.app.data.models.temporaryEntryValidated
import com.eventmanager.app.data.utils.DateTimeUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Everything the manager page of one artist contact may show: temporary guests linked to one uid. */
data class TemporaryGuestPortalInput(
    val guests: List<Guest>,
    val transfers: List<AccountTransfer>,
    val creditsEnabled: Boolean,
    val currencyCode: String,
    val associationName: String,
    val now: Long,
    val offsetHours: Int,
)

object TemporaryGuestPortalProjectionBuilder {
    const val COLLECTION = "temporaryGuestPortal"
    const val SCHEMA = 1
    private const val GROUP_LIFETIME_MILLIS = 3 * 86_400_000L

    fun endsAt(eventDate: Long, offsetHours: Int): Long =
        DateTimeUtils.getStartOfDayWithOffset(eventDate, offsetHours) + GROUP_LIFETIME_MILLIS

    /** The one definition of what the page shows: dated temporary guests of groups not yet ended, by event date. */
    internal fun runningBatches(guests: List<Guest>, now: Long, offsetHours: Int): List<Pair<List<Guest>, Long>> =
        guests
            .filter { it.isTemporaryGuest && it.temporaryEventDate != null }
            .groupBy { it.temporaryBatchKey() }
            .values
            .map { members -> members to endsAt(members.first().temporaryEventDate!!, offsetHours) }
            .filter { (_, endsAt) -> endsAt > now }
            .sortedWith(compareBy({ it.first.first().temporaryEventDate }, { it.first.first().temporaryArtistName.lowercase() }))

    /** Null when no group is still running. */
    fun build(input: TemporaryGuestPortalInput): PortalProjection? {
        val batches = runningBatches(input.guests, input.now, input.offsetHours)
        if (batches.isEmpty()) return null

        val label = PortalProjectionBuilder.MAX_LABEL
        val contactEmail = batches.last().first
            .map { it.temporaryContactEmail.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?: batches.asReversed().flatMap { it.first }.map { it.temporaryContactEmail.trim() }.firstOrNull { it.isNotEmpty() }
            ?: ""

        val payload = buildJsonObject {
            put("association", input.associationName.trim().take(label))
            put("contactEmail", contactEmail)
            put("groups", JsonArray(batches.map { (members, endsAt) ->
                val first = members.first()
                buildJsonObject {
                    put("eventDate", first.temporaryEventDate!!)
                    put("venueName", first.temporaryVenueName.ifBlank { first.venueName }.trim().take(label))
                    put("artistName", first.temporaryArtistName.trim().take(label))
                    put("endsAt", endsAt)
                    put("guests", JsonArray(
                        members.sortedWith(compareBy({ it.name.trim().lowercase() }, { it.lastNameAbbreviation.trim().lowercase() }))
                            .map { guestJson(it, input) }
                    ))
                }
            }))
        }.toString()
        return PortalProjection(
            payload = payload,
            contentHash = PortalProjectionBuilder.sha256(payload),
            schema = SCHEMA,
            extraFields = mapOf("expiresAtMillis" to batches.maxOf { it.second }),
        )
    }

    private fun guestJson(guest: Guest, input: TemporaryGuestPortalInput): JsonObject = buildJsonObject {
        put("guestKey", TemporaryGuestPortalQrDocument.guestKey(guest))
        put("firstName", guest.name.trim())
        put("lastName", guest.lastNameAbbreviation.trim())
        put("emergencyPhone", guest.temporaryContactPhone.trim())
        put("entryUsed", guest.temporaryEntryValidated)
        put("qr", TemporaryGuestPortalQrDocument.hasQr(guest))
        put("wallet", walletOrNull(guest, input))
    }

    private fun walletOrNull(guest: Guest, input: TemporaryGuestPortalInput): JsonElement {
        if (!input.creditsEnabled) return JsonNull
        val wallet = PortalProjectionBuilder
            .walletJson(input.transfers, AccountHolderType.GUEST, guest.nanoId, input.currencyCode)
            .jsonObject
        val balance = wallet.getValue("balance").jsonPrimitive.double
        if (balance == 0.0) return JsonNull
        return buildJsonObject {
            put("balance", balance)
            put("currency", wallet.getValue("currency"))
        }
    }
}

/**
 * Temporary guests' QR codes, published apart from the manager page so the page never holds a guest
 * id. [items] is keyed by the same guestKey the page shows.
 */
object TemporaryGuestPortalQrDocument {
    const val COLLECTION = "temporaryGuestPortalQr"
    const val SCHEMA = 1

    fun guestKey(guest: Guest): String = PortalProjectionBuilder.sha256(guest.nanoId).take(16)

    internal fun hasQr(guest: Guest): Boolean =
        !guest.isAdmin && !guest.temporaryEntryValidated && guest.nanoId.isNotBlank()

    fun build(guests: List<Guest>, enabled: Boolean, now: Long, offsetHours: Int): Map<String, Any>? {
        if (!enabled) return null
        val items = TemporaryGuestPortalProjectionBuilder.runningBatches(guests, now, offsetHours)
            .flatMap { it.first }
            .filter { hasQr(it) }
            .associate { guest ->
                guestKey(guest) to mapOf(
                    "payload" to guest.nanoId,
                    "name" to "${guest.name} ${guest.lastNameAbbreviation}".trim(),
                )
            }
        if (items.isEmpty()) return null
        return mapOf("schema" to SCHEMA, "items" to items)
    }
}
