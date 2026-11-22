package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.data.models.GuestForm
import com.eventmanager.app.data.models.GuestFormRequestKind
import com.eventmanager.app.data.models.GuestFormSubmissionCodec
import com.eventmanager.app.data.models.awaitsReview
import com.eventmanager.app.data.models.isPortalInvite
import com.eventmanager.app.data.models.listVenueLabel
import com.eventmanager.app.data.models.removeGuestKeyList
import com.eventmanager.app.data.models.requestKindValue
import com.eventmanager.app.data.utils.DateTimeUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pending guest requests shown on `guestListPortal/{uid}` (spec 2026-11-22 guest list review §5.2).
 * Never a formId: a request is named by [reviewKey].
 */
object GuestListReviews {
    private fun day(millis: Long) = DateTimeUtils.getStartOfDayWithOffset(millis, 0)

    fun reviewKey(form: GuestForm): String = PortalProjectionBuilder.sha256(form.formId).take(16)

    /** Pending requests [grant] covers; an empty [orgId] applies no organization filter. */
    fun covered(grant: GuestListGrant, forms: List<GuestForm>, orgId: String): List<GuestForm> =
        forms.filter { form ->
            form.awaitsReview &&
                (orgId.isEmpty() || form.firebaseOrgId.isBlank() || form.firebaseOrgId == orgId) &&
                day(form.eventDateMillis) == day(grant.eventDateMillis) &&
                (grant.venueName.isBlank() || listVenueLabel(form.venueName).let { it.isEmpty() || it.equals(grant.venueName.trim(), ignoreCase = true) })
        }.sortedBy { it.submittedAt }

    fun toJson(form: GuestForm, forms: List<GuestForm>, guests: List<Guest>, holderNames: Map<String, String>): JsonObject {
        val label = PortalProjectionBuilder.MAX_LABEL
        val kind = form.requestKindValue
        val submission = GuestFormSubmissionCodec.decode(form.submissionJson)
        val template = if (form.isPortalInvite && form.parentFormId.isNotBlank()) forms.firstOrNull { it.formId == form.parentFormId } else null
        val email = submission?.contactEmail.orEmpty().ifBlank { template?.prefillEmail.orEmpty() }.trim()
        val phone = submission?.emergencyPhone.orEmpty().ifBlank { template?.prefillPhone.orEmpty() }.trim()
        return buildJsonObject {
            put("reviewKey", reviewKey(form))
            put("source", if (form.isPortalInvite) "PORTAL" else "FORM")
            put("kind", kind.name)
            put("listName", form.eventName.trim().take(label))
            put("venueName", listVenueLabel(form.venueName).take(label))
            put("eventDate", form.eventDateMillis)
            put("submittedAt", form.submittedAt)
            put("contactEmail", email)
            put("emergencyPhone", phone)
            put("notes", submission?.notes.orEmpty())
            if (kind == GuestFormRequestKind.REMOVE) {
                val keys = form.removeGuestKeyList().toSet()
                put("removeGuests", JsonArray(
                    guests.filter { TemporaryGuestPortalQrDocument.guestKey(it) in keys }.map { g ->
                        buildJsonObject {
                            put("guestKey", TemporaryGuestPortalQrDocument.guestKey(g))
                            put("name", "${g.name} ${g.lastNameAbbreviation}".trim())
                        }
                    },
                ))
            } else {
                put("people", JsonArray(submission?.people.orEmpty().map { p ->
                    buildJsonObject {
                        put("name", p.name)
                        put("accessIds", JsonArray(p.requestedAccessIds.sorted().map { JsonPrimitive(it) }))
                    }
                }))
            }
            put("requestedBy", holderNames[form.portalUid].orEmpty())
        }
    }
}
