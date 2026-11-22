package com.eventmanager.app.portal.certificate

import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.portal.PortalInputValidator
import com.eventmanager.app.portal.PortalRequest
import com.eventmanager.app.portal.PortalRequestType
import com.eventmanager.app.portal.firestoreMillisOf

/** A certificate request waiting on a device: to review, or approved and not sent yet. */
data class CertificateReviewRequest(
    val requestId: String,
    /** The portal account, never the volunteer id. */
    val portalUid: String,
    val email: String,
    val fromDay: Int,
    val toDay: Int,
    val jobTypes: List<String>,
    val requestedAt: Long,
    val status: String,
    /** APPROVED or PROCESSING for more than [CertificateReview.STUCK_MILLIS]: the send never finished. */
    val stuck: Boolean,
    /** Why the server sent the request back for review (SEND_FAILED, INVALID, NO_ACCESS, NO_EMAIL), or "". */
    val lastReason: String,
)

object CertificateReview {
    const val STATUS_PENDING_REVIEW = "PENDING_REVIEW"
    const val STATUS_APPROVED = "APPROVED"
    const val STATUS_PROCESSING = "PROCESSING"
    const val REASON_REFUSED = "REFUSED"
    const val REASON_SEND_FAILED = "SEND_FAILED"
    const val MAX_NOTE = 300
    const val MAX_TEXT_JSON = 200_000
    const val MAX_SIGNER_IMAGE = 200_000

    /** A send that did not finish within this long is retried or shown as failed. */
    const val STUCK_MILLIS: Long = 5L * 60 * 1000

    /** "2026-03-05" -> 20260305. */
    fun dayOfIso(iso: String): Int = iso.replace("-", "").toIntOrNull() ?: 0
}

/** Name, last name and gender of [volunteer] as the certificate reads them. */
fun certificatePerson(volunteer: Volunteer): CertPerson =
    CertPerson(volunteer.name, volunteer.lastNameAbbreviation, volunteer.gender?.name.orEmpty())

fun certificateJobTypes(configs: List<JobTypeConfig>): List<CertJobType> =
    configs.map { CertJobType(it.name.trim(), certificateCategoryOf(it), it.description.trim()) }

fun certificateJobs(jobs: List<Job>): List<CertJob> =
    jobs.map { CertJob(it.jobTypeName.trim(), CertificateDates.dayOf(it.date)) }

/** Requests that still need a device, oldest first. Anything unreadable, or still with the server before review, is left out. */
fun certificateReviewRequests(docs: List<Pair<String, Map<String, Any?>>>, now: Long): List<CertificateReviewRequest> =
    docs.mapNotNull { (id, data) ->
        val status = data["status"]?.toString().orEmpty()
        if (status != CertificateReview.STATUS_PENDING_REVIEW &&
            status != CertificateReview.STATUS_APPROVED &&
            status != CertificateReview.STATUS_PROCESSING
        ) return@mapNotNull null
        // The server's own claim (PENDING to PROCESSING, before any review) carries no approvalId: it is not a send.
        if (status == CertificateReview.STATUS_PROCESSING && data["approvalId"]?.toString().orEmpty().isBlank()) return@mapNotNull null
        val request = PortalInputValidator.parseRequest(id, data) ?: return@mapNotNull null
        if (request.type != PortalRequestType.CERTIFICATE_EMAIL) return@mapNotNull null
        val stuck = when (status) {
            CertificateReview.STATUS_APPROVED -> isOld(firestoreMillisOf(data["approvedAt"]), now)
            CertificateReview.STATUS_PROCESSING -> isOld(firestoreMillisOf(data["processingAt"]), now)
            else -> false
        }
        CertificateReviewRequest(
            requestId = id,
            portalUid = request.uid,
            email = request.email,
            fromDay = CertificateReview.dayOfIso(request.from),
            toDay = CertificateReview.dayOfIso(request.to),
            jobTypes = request.jobTypes,
            requestedAt = firestoreMillisOf(data["requestedAt"]) ?: 0L,
            status = status,
            stuck = stuck,
            lastReason = data["reason"]?.toString().orEmpty(),
        )
    }.sortedBy { it.requestedAt }

private fun isOld(at: Long?, now: Long): Boolean = at == null || now - at > CertificateReview.STUCK_MILLIS

/** The text proposed for a request: its period and job types, on that volunteer's jobs. Null when nothing is left to certify. */
fun certificateDraft(
    template: CertificateTemplate,
    association: String,
    volunteer: Volunteer,
    jobs: List<Job>,
    configs: List<JobTypeConfig>,
    request: CertificateReviewRequest,
    now: Long,
): CertAssembly? = directDraft(
    template, association, volunteer, jobs, configs,
    fromDay = request.fromDay, toDay = request.toDay, jobTypes = request.jobTypes.toSet(), now = now,
)

/** The text proposed for a certificate generated from the volunteer's panel. */
fun directDraft(
    template: CertificateTemplate,
    association: String,
    volunteer: Volunteer,
    jobs: List<Job>,
    configs: List<JobTypeConfig>,
    fromDay: Int,
    toDay: Int,
    jobTypes: Set<String>,
    now: Long,
): CertAssembly? = CertificateAssembler.assemble(
    template,
    association,
    certificatePerson(volunteer),
    certificateJobTypes(configs),
    certificateJobs(jobs.filter { it.volunteerId == volunteer.id }),
    CertSelection(fromDay, toDay, CertificateDates.dayOf(now), jobTypes.map { it.trim() }.toSet()),
)
