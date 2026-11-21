package com.eventmanager.app.portal

import com.eventmanager.app.data.models.AccountHolderType
import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.GuestForm
import com.eventmanager.app.data.models.GuestFormRequestKind
import com.eventmanager.app.data.models.GuestFormStatus
import com.eventmanager.app.data.models.GuestFormSubmissionCodec
import com.eventmanager.app.data.models.isPortalInvite
import com.eventmanager.app.data.models.offeredAccesses
import com.eventmanager.app.data.models.removeGuestKeyList
import com.eventmanager.app.data.models.requestKindValue
import com.eventmanager.app.data.models.statusValue
import com.eventmanager.app.data.models.temporaryBatchKey
import com.eventmanager.app.data.models.temporaryEntryValidated
import com.eventmanager.app.data.utils.DateTimeUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    /** The account was created by a device for a list: an unverified session may read the page (rules v18). */
    val deviceCreated: Boolean = true,
    /** Venue access id to its name; ids never reach the page. */
    val accessNames: Map<String, String> = emptyMap(),
    /** Invite templates bound to this uid and their answers (spec 2026-11-21 §8). */
    val inviteForms: List<GuestForm> = emptyList(),
)

object TemporaryGuestPortalProjectionBuilder {
    const val COLLECTION = "temporaryGuestPortal"
    const val SCHEMA = 2

    /** Midnight of the event day (Geneva) to 8:00 the next morning: a night event still shows at 1:00. */
    private const val VISIBLE_MILLIS = 32 * 3_600_000L

    /** Fixed 8 h past the event day, whatever the app's own day-change setting says. */
    fun visibleUntil(eventDate: Long): Long =
        DateTimeUtils.getStartOfDayWithOffset(eventDate, 0) + VISIBLE_MILLIS

    /** The one definition of what the page shows: dated temporary guests of lists still visible, by event date. */
    internal fun runningBatches(guests: List<Guest>, now: Long): List<Pair<List<Guest>, Long>> =
        guests
            .filter { it.isTemporaryGuest && it.temporaryEventDate != null }
            .groupBy { it.temporaryBatchKey() }
            .values
            .map { members -> members to visibleUntil(members.first().temporaryEventDate!!) }
            .filter { (_, until) -> until > now }
            .sortedWith(compareBy({ it.first.first().temporaryEventDate }, { it.first.first().temporaryArtistName.lowercase() }))

    private fun templateKey(form: GuestForm): String =
        listOf(form.artistName.trim().lowercase(), form.eventDateMillis.toString(), form.venueName.trim().lowercase())
            .joinToString("|")

    private fun isOpenTemplate(form: GuestForm, now: Long): Boolean =
        form.isPortalInvite && form.parentFormId.isBlank() && form.allowMultipleResponses &&
            form.eventDateMillis > 0 && form.statusValue == GuestFormStatus.OPEN &&
            (form.expiresAtMillis <= 0 || now < form.expiresAtMillis)

    private class ListView(
        val eventDate: Long,
        val venueName: String,
        val artistName: String,
        val members: List<Guest>,
        val template: GuestForm?,
        val responses: List<GuestForm>,
    ) {
        val until = visibleUntil(eventDate)
    }

    /** Lists still visible: dated temporary guests and open invite templates, merged by list key. */
    private fun visibleLists(input: TemporaryGuestPortalInput): List<ListView> {
        val members = input.guests
            .filter { it.isTemporaryGuest && it.temporaryEventDate != null }
            .groupBy { it.temporaryBatchKey() }
        val templates = input.inviteForms.filter { it.isPortalInvite && it.parentFormId.isBlank() }
        val templatesByKey = templates.groupBy { templateKey(it) }
        val keyOfTemplate = templates.associate { it.formId to templateKey(it) }
        val responsesByKey = input.inviteForms
            .filter { it.parentFormId.isNotBlank() }
            .groupBy { keyOfTemplate[it.parentFormId] ?: templateKey(it) }
        val openKeys = templates.filter { isOpenTemplate(it, input.now) }.map { templateKey(it) }
        return (members.keys + openKeys).distinct().mapNotNull { key ->
            val group = members[key].orEmpty()
            val open = templatesByKey[key].orEmpty().firstOrNull { isOpenTemplate(it, input.now) }
            val first = group.firstOrNull()
            val view = if (first != null) {
                ListView(
                    first.temporaryEventDate!!, first.temporaryVenueName.ifBlank { first.venueName }, first.temporaryArtistName,
                    group, open, responsesByKey[key].orEmpty(),
                )
            } else {
                ListView(open!!.eventDateMillis, open.venueName, open.artistName, group, open, responsesByKey[key].orEmpty())
            }
            view.takeIf { it.until > input.now }
        }.sortedWith(compareBy({ it.eventDate }, { it.artistName.lowercase() }))
    }

    /** Null when no list is still visible. */
    fun build(input: TemporaryGuestPortalInput): PortalProjection? {
        val lists = visibleLists(input)
        if (lists.isEmpty()) return null

        val label = PortalProjectionBuilder.MAX_LABEL
        val contactEmail = lists.asReversed().flatMap { it.members }
            .map { it.temporaryContactEmail.trim() }.firstOrNull { it.isNotEmpty() }
            ?: lists.mapNotNull { it.template?.prefillEmail?.trim() }.firstOrNull { it.isNotEmpty() }
            ?: ""

        val payload = buildJsonObject {
            put("association", input.associationName.trim().take(label))
            put("contactEmail", contactEmail)
            put("deviceCreated", input.deviceCreated)
            put("groups", JsonArray(lists.map { list ->
                buildJsonObject {
                    put("eventDate", list.eventDate)
                    put("venueName", list.venueName.trim().take(label))
                    put("artistName", list.artistName.trim().take(label))
                    put("visibleUntil", list.until)
                    put("guests", JsonArray(
                        list.members.sortedWith(compareBy({ it.name.trim().lowercase() }, { it.lastNameAbbreviation.trim().lowercase() }))
                            .map { guestJson(it, input) }
                    ))
                    put("invite", list.template?.let { inviteJson(it, list, input) } ?: JsonNull)
                    put("pending", JsonArray(pendingJson(list)))
                }
            }))
        }.toString()
        return PortalProjection(
            payload = payload,
            contentHash = PortalProjectionBuilder.sha256(payload),
            schema = SCHEMA,
            extraFields = mapOf(
                "expiresAtMillis" to lists.maxOf { it.until },
                "deviceCreated" to input.deviceCreated,
            ),
        )
    }

    private fun inviteJson(template: GuestForm, list: ListView, input: TemporaryGuestPortalInput): JsonObject {
        val allowance = PortalInviteAllowance.of(template, list.members, list.responses)
        return buildJsonObject {
            put("formId", template.formId)
            put("maxGuests", allowance.maxGuests)
            put("remaining", allowance.remaining)
            put("accesses", JsonArray(template.offeredAccesses().map { access ->
                val (max, left) = allowance.perAccess[access.id] ?: (0 to allowance.remaining)
                buildJsonObject {
                    put("id", access.id)
                    put("name", (input.accessNames[access.id] ?: access.name).trim().take(PortalProjectionBuilder.MAX_LABEL))
                    put("max", max)
                    put("remaining", left)
                }
            }))
        }
    }

    private fun pendingJson(list: ListView): List<JsonObject> {
        val byKey = list.members.associateBy { TemporaryGuestPortalQrDocument.guestKey(it) }
        return list.responses
            .filter { it.statusValue == GuestFormStatus.PENDING_REVIEW || it.statusValue == GuestFormStatus.REJECTED }
            .sortedWith(compareBy({ it.submittedAt }, { it.formId }))
            .map { response ->
                val remove = response.requestKindValue == GuestFormRequestKind.REMOVE
                val keys = if (remove) response.removeGuestKeyList().filter { it in byKey } else emptyList()
                val names = if (remove) {
                    keys.map { k -> byKey.getValue(k).let { "${it.name.trim()} ${it.lastNameAbbreviation.trim()}".trim() } }
                } else {
                    GuestFormSubmissionCodec.decode(response.submissionJson)?.people.orEmpty().map { it.name.trim() }
                }
                buildJsonObject {
                    put("responseId", response.formId)
                    put("kind", if (remove) GuestFormRequestKind.REMOVE.name else GuestFormRequestKind.ADD.name)
                    put("names", JsonArray(names.map { JsonPrimitive(it.take(PortalProjectionBuilder.MAX_LABEL)) }))
                    put("guestKeys", JsonArray(keys.map { JsonPrimitive(it) }))
                    put("status", response.statusValue.name)
                }
            }
    }

    private fun guestJson(guest: Guest, input: TemporaryGuestPortalInput): JsonObject = buildJsonObject {
        put("guestKey", TemporaryGuestPortalQrDocument.guestKey(guest))
        put("firstName", guest.name.trim())
        put("lastName", guest.lastNameAbbreviation.trim())
        put("emergencyPhone", guest.temporaryContactPhone.trim())
        put("accessNames", JsonArray(
            PortalInviteAllowance.accessIdList(guest).mapNotNull { input.accessNames[it] }.map { JsonPrimitive(it.trim()) },
        ))
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

    fun build(guests: List<Guest>, enabled: Boolean, now: Long): Map<String, Any>? {
        if (!enabled) return null
        val items = TemporaryGuestPortalProjectionBuilder.runningBatches(guests, now)
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
