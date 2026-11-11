package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Volunteer

/** What to do with one Google claim. */
sealed class GoogleLinkDecision {
    /** Write the alias and the three fields on [volunteer]. */
    data class Link(val volunteer: Volunteer, val linkedBy: String) : GoogleLinkDecision()
    /** The alias already points at a volunteer carrying this Google account: nothing to write. */
    data object AlreadyLinked : GoogleLinkDecision()
    /** [reason]: DOMAIN, NO_MATCH, AMBIGUOUS, CODE, ALREADY_LINKED or TAKEN. */
    data class Reject(val reason: String) : GoogleLinkDecision()
}

/**
 * Decides Google account links without any I/O. Same answers as `webform/functions/src/google.ts`.
 * E-mail addresses compare lowercase and trimmed only (no Gmail dot or plus rules).
 */
object PortalGoogleLinkProcessor {
    /** A link code is accepted this long after the GOOGLE_LINK request's `requestedAt`. */
    const val LINK_CODE_VALIDITY_MS = 10L * 60 * 1000

    /** The volunteer stored the address an admin typed; the Google account with that address claims it. */
    fun adminMatch(
        claim: PortalGoogleClaim,
        volunteers: List<Volunteer>,
        domains: List<String>,
        aliasTarget: String?,
    ): GoogleLinkDecision {
        if (!domainAllowed(claim.email, domains)) return GoogleLinkDecision.Reject("DOMAIN")
        if (isAliasValid(claim.googleUid, aliasTarget, volunteers)) return GoogleLinkDecision.AlreadyLinked
        val candidates = volunteers.filter {
            it.portalEnabled && it.portalUid.isNotBlank() && sameAddress(it.portalGoogleEmail, claim.email)
        }
        return when (candidates.size) {
            0 -> GoogleLinkDecision.Reject("NO_MATCH")
            // A match the volunteer linked earlier keeps VOLUNTEER, so they can still remove it.
            1 -> candidates.single().let { match ->
                val linkedBy = if (match.portalGoogleLinkedBy == Volunteer.GOOGLE_LINKED_BY_VOLUNTEER) {
                    Volunteer.GOOGLE_LINKED_BY_VOLUNTEER
                } else {
                    Volunteer.GOOGLE_LINKED_BY_ADMIN
                }
                GoogleLinkDecision.Link(match, linkedBy)
            }
            else -> GoogleLinkDecision.Reject("AMBIGUOUS")
        }
    }

    /**
     * A signed-in volunteer asked to link (GOOGLE_LINK [request] with the code hash), then signed in
     * with Google and handed over the code in [claim]. [requestedAtMillis] is the request's `requestedAt`.
     */
    fun linkCode(
        claim: PortalGoogleClaim,
        request: PortalRequest?,
        requestedAtMillis: Long?,
        now: Long,
        volunteers: List<Volunteer>,
        domains: List<String>,
        aliasTarget: String?,
    ): GoogleLinkDecision {
        val codeOk = request != null &&
            request.type == PortalRequestType.GOOGLE_LINK &&
            request.status == PortalRequest.STATUS_PENDING &&
            claim.code.isNotEmpty() &&
            request.codeHash == PortalProjectionBuilder.sha256(claim.code) &&
            requestedAtMillis != null &&
            now - requestedAtMillis < LINK_CODE_VALIDITY_MS
        if (!codeOk) return GoogleLinkDecision.Reject("CODE")
        val owner = volunteers.firstOrNull { it.portalEnabled && it.portalUid.isNotBlank() && it.portalUid == request!!.uid }
            ?: return GoogleLinkDecision.Reject("NO_MATCH")
        // Retry after the link was already written (device stopped before answering).
        if (aliasTarget == owner.portalUid && owner.portalGoogleUid == claim.googleUid) return GoogleLinkDecision.AlreadyLinked
        if (owner.portalGoogleEmail.isNotBlank()) return GoogleLinkDecision.Reject("ALREADY_LINKED")
        if (!domainAllowed(claim.email, domains)) return GoogleLinkDecision.Reject("DOMAIN")
        val others = volunteers.filter { it.id != owner.id }
        if (others.any { it.portalGoogleUid == claim.googleUid }) return GoogleLinkDecision.Reject("TAKEN")
        if (others.any { sameAddress(it.portalGoogleEmail, claim.email) }) return GoogleLinkDecision.Reject("TAKEN")
        return GoogleLinkDecision.Link(owner, Volunteer.GOOGLE_LINKED_BY_VOLUNTEER)
    }

    /** GOOGLE_UNLINK from the portal: null when allowed, else NO_ACCESS or ADMIN_LINK. */
    fun canUnlink(owner: Volunteer?): String? = when {
        owner == null -> "NO_ACCESS"
        owner.portalGoogleLinkedBy == Volunteer.GOOGLE_LINKED_BY_ADMIN -> "ADMIN_LINK"
        else -> null
    }

    /** True when the alias target is a volunteer that still carries [googleUid]; anything else is stale. */
    fun isAliasValid(googleUid: String, aliasTarget: String?, volunteers: List<Volunteer>): Boolean =
        !aliasTarget.isNullOrBlank() &&
            volunteers.any { it.portalUid == aliasTarget && it.portalGoogleUid == googleUid }

    /** Consumer Google addresses, never an association domain (rules and Cloud Functions agree). */
    private val CONSUMER_DOMAINS = setOf("gmail.com", "googlemail.com")

    /** [domains] without gmail.com and googlemail.com. */
    fun usableDomains(domains: List<String>): List<String> =
        domains.filter { it.trim().lowercase() !in CONSUMER_DOMAINS }

    fun domainAllowed(email: String, domains: List<String>): Boolean {
        val domain = email.trim().lowercase().substringAfterLast('@', "")
        return domain.isNotEmpty() && domain !in CONSUMER_DOMAINS && domains.any { it.trim().lowercase() == domain }
    }

    fun sameAddress(a: String, b: String): Boolean {
        val left = a.trim().lowercase()
        return left.isNotEmpty() && left == b.trim().lowercase()
    }
}
