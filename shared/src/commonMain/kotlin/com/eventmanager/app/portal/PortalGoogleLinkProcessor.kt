package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Volunteer

/** What to do with one Google claim. */
sealed class GoogleLinkDecision {
    /** Write the alias and the three fields on [holder] and every holder sharing its portal uid. */
    data class Link(val holder: PortalHolder, val linkedBy: String) : GoogleLinkDecision()
    /** The alias already points at a holder carrying this Google account: nothing to write. */
    data object AlreadyLinked : GoogleLinkDecision()
    /** [reason]: DOMAIN, NO_MATCH, AMBIGUOUS, CODE, ALREADY_LINKED or TAKEN. */
    data class Reject(val reason: String) : GoogleLinkDecision()
}

/**
 * Decides Google account links without any I/O. Same answers as `webform/functions/src/google.ts`.
 * E-mail addresses compare lowercase and trimmed only (no Gmail dot or plus rules).
 * Holders are volunteers and permanent guests; holders sharing a non-blank portal uid are one
 * person and get the same answer as if there was only one of them.
 */
object PortalGoogleLinkProcessor {
    /** A link code is accepted this long after the GOOGLE_LINK request's `requestedAt`. */
    const val LINK_CODE_VALIDITY_MS = 10L * 60 * 1000

    /**
     * The holder stored the address an admin typed; the Google account with that address claims it.
     * [anyAccount]: every Google account is accepted, [domains] is ignored.
     */
    fun adminMatch(
        claim: PortalGoogleClaim,
        holders: List<PortalHolder>,
        domains: List<String>,
        aliasTarget: String?,
        anyAccount: Boolean = false,
    ): GoogleLinkDecision {
        if (!accountAllowed(claim.email, domains, anyAccount)) return GoogleLinkDecision.Reject("DOMAIN")
        if (isAliasValid(claim.googleUid, aliasTarget, holders)) return GoogleLinkDecision.AlreadyLinked
        val candidates = holders.filter {
            it.portalEnabled && it.portalUid.isNotBlank() && sameAddress(it.portalGoogleEmail, claim.email)
        }
        // One person per portal account; the volunteer answers for it when there is one.
        val people = candidates.groupBy { it.portalUid }.values.map { primary(it) }
        return when (people.size) {
            0 -> GoogleLinkDecision.Reject("NO_MATCH")
            // A match the holder linked earlier keeps VOLUNTEER, so they can still remove it.
            1 -> people.single().let { match ->
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
     * A signed-in holder asked to link (GOOGLE_LINK [request] with the code hash), then signed in
     * with Google and handed over the code in [claim]. [requestedAtMillis] is the request's `requestedAt`.
     */
    fun linkCode(
        claim: PortalGoogleClaim,
        request: PortalRequest?,
        requestedAtMillis: Long?,
        now: Long,
        holders: List<PortalHolder>,
        domains: List<String>,
        aliasTarget: String?,
        anyAccount: Boolean = false,
    ): GoogleLinkDecision {
        val codeOk = request != null &&
            request.type == PortalRequestType.GOOGLE_LINK &&
            request.status == PortalRequest.STATUS_PENDING &&
            claim.code.isNotEmpty() &&
            request.codeHash == PortalProjectionBuilder.sha256(claim.code) &&
            requestedAtMillis != null &&
            now - requestedAtMillis < LINK_CODE_VALIDITY_MS
        if (!codeOk) return GoogleLinkDecision.Reject("CODE")
        val account = accountOf(request!!.uid, holders)
        if (account.isEmpty()) return GoogleLinkDecision.Reject("NO_MATCH")
        val owner = primary(account)
        // Retry after the link was already written (device stopped before answering).
        if (aliasTarget == owner.portalUid && account.any { it.portalGoogleUid == claim.googleUid }) return GoogleLinkDecision.AlreadyLinked
        if (account.any { it.portalGoogleEmail.isNotBlank() }) return GoogleLinkDecision.Reject("ALREADY_LINKED")
        if (!accountAllowed(claim.email, domains, anyAccount)) return GoogleLinkDecision.Reject("DOMAIN")
        val others = holders.filter { other -> account.none { it.kind == other.kind && it.id == other.id } }
        if (others.any { it.portalGoogleUid == claim.googleUid }) return GoogleLinkDecision.Reject("TAKEN")
        if (others.any { sameAddress(it.portalGoogleEmail, claim.email) }) return GoogleLinkDecision.Reject("TAKEN")
        return GoogleLinkDecision.Link(owner, Volunteer.GOOGLE_LINKED_BY_VOLUNTEER)
    }

    /** Holders with open access on the portal account [uid]; empty for a blank uid. */
    fun accountOf(uid: String, holders: List<PortalHolder>): List<PortalHolder> =
        if (uid.isBlank()) emptyList() else holders.filter { it.portalEnabled && it.portalUid == uid }

    /** The holder that answers for one account: the volunteer when there is one, else the first. */
    fun primary(account: List<PortalHolder>): PortalHolder =
        account.firstOrNull { it.kind == PortalHolderKind.VOLUNTEER } ?: account.first()

    /** GOOGLE_UNLINK from the portal: null when allowed, else NO_ACCESS or ADMIN_LINK. */
    fun canUnlink(owner: PortalHolder?): String? = when {
        owner == null -> "NO_ACCESS"
        owner.portalGoogleLinkedBy == Volunteer.GOOGLE_LINKED_BY_ADMIN -> "ADMIN_LINK"
        else -> null
    }

    /** True when the alias target is a holder that still carries [googleUid]; anything else is stale. */
    fun isAliasValid(googleUid: String, aliasTarget: String?, holders: List<PortalHolder>): Boolean =
        !aliasTarget.isNullOrBlank() &&
            holders.any { it.portalUid == aliasTarget && it.portalGoogleUid == googleUid }

    /** Consumer Google addresses, never an association domain (rules and Cloud Functions agree). */
    private val CONSUMER_DOMAINS = setOf("gmail.com", "googlemail.com")

    /** [domains] without gmail.com and googlemail.com. */
    fun usableDomains(domains: List<String>): List<String> =
        domains.filter { it.trim().lowercase() !in CONSUMER_DOMAINS }

    /** Any address with a domain when [anyAccount], else [domainAllowed]. */
    fun accountAllowed(email: String, domains: List<String>, anyAccount: Boolean): Boolean =
        if (anyAccount) email.trim().substringAfterLast('@', "").isNotEmpty() else domainAllowed(email, domains)

    /** True when Google sign-in is usable: switched on and either open to all accounts or with a domain left. */
    fun loginOn(enabled: Boolean, anyAccount: Boolean, domains: List<String>): Boolean =
        enabled && (anyAccount || usableDomains(domains).isNotEmpty())

    fun domainAllowed(email: String, domains: List<String>): Boolean {
        val domain = email.trim().lowercase().substringAfterLast('@', "")
        return domain.isNotEmpty() && domain !in CONSUMER_DOMAINS && domains.any { it.trim().lowercase() == domain }
    }

    fun sameAddress(a: String, b: String): Boolean {
        val left = a.trim().lowercase()
        return left.isNotEmpty() && left == b.trim().lowercase()
    }
}
