package com.eventmanager.app.ui.components.settings.certificate

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.portal.ApproveResult
import com.eventmanager.app.portal.DirectSendStatus
import com.eventmanager.app.portal.certificate.CertAssembly
import com.eventmanager.app.portal.certificate.CertParagraph
import com.eventmanager.app.portal.certificate.CertRun
import com.eventmanager.app.portal.certificate.CertSigner
import com.eventmanager.app.portal.certificate.CertSize
import com.eventmanager.app.portal.certificate.CertificateCategory
import com.eventmanager.app.portal.certificate.CertificateDates
import com.eventmanager.app.portal.certificate.CertificateReviewRequest
import com.eventmanager.app.portal.certificate.CertificateTemplate
import com.eventmanager.app.portal.certificate.ResolvedParagraph
import com.eventmanager.app.portal.certificate.ResolvedRun
import com.eventmanager.app.portal.certificate.certificateCategoryOf
import com.eventmanager.app.portal.certificate.certificateDraft
import com.eventmanager.app.portal.certificate.directDraft
import com.eventmanager.app.utils.ValidationUtils
import com.mohamedrejeb.richeditor.model.RichTextState
import kotlinx.coroutines.flow.Flow

/** What the review window works on: a request from the portal, or a certificate generated from a volunteer's panel. */
sealed interface CertificateReviewTarget {
    /** [volunteer] is null when no profile has the request's portal account. */
    data class Request(val request: CertificateReviewRequest, val volunteer: Volunteer?) : CertificateReviewTarget
    data class Direct(val volunteer: Volunteer) : CertificateReviewTarget
}

/**
 * What the review window may do. There is deliberately no way to save the template here: a
 * correction made in this window is for this certificate only.
 */
interface CertificateReviewActions {
    suspend fun approve(requestId: String, body: List<ResolvedParagraph>, footer: List<ResolvedParagraph>, title: String, signer: CertSigner): ApproveResult
    suspend fun refuse(requestId: String, note: String): Boolean
    suspend fun sendDirect(volunteer: Volunteer, body: List<ResolvedParagraph>, footer: List<ResolvedParagraph>, title: String, signer: CertSigner): String?
    fun directStatus(approvalId: String): Flow<DirectSendStatus>

    /** Forgets a failed direct send (deletes its approval). */
    suspend fun dismissDirect(approvalId: String)
}

/** Resolved paragraphs to editor paragraphs (text runs only) and back. */
internal object CertificateResolvedText {
    fun toParagraphs(resolved: List<ResolvedParagraph>): List<CertParagraph> = resolved.map { p ->
        CertParagraph(p.align, p.size, p.bullet, p.runs.map { CertRun(text = it.text, b = it.b, i = it.i, u = it.u) })
    }

    /** Variables and agreements cannot appear in a resolved text: anything but plain text is left out. */
    fun fromParagraphs(paragraphs: List<CertParagraph>): List<ResolvedParagraph> = paragraphs.mapNotNull { p ->
        val runs = p.runs
            .filter { it.variable.isEmpty() && it.gender.isEmpty() && it.text.isNotEmpty() }
            .map { ResolvedRun(it.text, it.b, it.i, it.u) }
        if (runs.all { it.text.isBlank() }) return@mapNotNull null
        val merged = mutableListOf<ResolvedRun>()
        for (run in runs) {
            val prev = merged.lastOrNull()
            if (prev != null && prev.b == run.b && prev.i == run.i && prev.u == run.u) {
                merged[merged.lastIndex] = prev.copy(text = prev.text + run.text)
            } else {
                merged += run
            }
        }
        ResolvedParagraph(p.align, p.size, p.bullet, merged)
    }

    fun load(state: RichTextState, paragraphs: List<ResolvedParagraph>) {
        state.setHtml(CertificateDocHtml.toHtml(toParagraphs(paragraphs)))
    }

    fun read(state: RichTextState): List<ResolvedParagraph> = fromParagraphs(CertificateDocHtml.fromHtml(state.toHtml()))
}

/**
 * State of the review window: the resolved text of this one certificate in two rich editors (body
 * and footer), and in direct mode the period and job types it covers. Never touches the template.
 */
class CertificateReviewModel(
    val target: CertificateReviewTarget,
    private val template: CertificateTemplate,
    private val association: String,
    jobs: List<Job>,
    private val configs: List<JobTypeConfig>,
    private val now: () -> Long = System::currentTimeMillis,
) {
    val volunteer: Volunteer? = when (target) {
        is CertificateReviewTarget.Request -> target.volunteer
        is CertificateReviewTarget.Direct -> target.volunteer
    }
    val isDirect: Boolean get() = target is CertificateReviewTarget.Direct

    /** A request with no matching profile can only be refused. */
    val onlyRefusal: Boolean = target is CertificateReviewTarget.Request && target.volunteer == null

    val signer: CertSigner get() = template.signer

    val body = RichTextState()
    val footer = RichTextState()

    /** The editor the toolbar acts on. */
    var active by mutableStateOf<RichTextState?>(null)

    val todayDay: Int get() = CertificateDates.dayOf(now())

    private val pastJobs: List<Job> = volunteer?.let { v ->
        jobs.filter { it.volunteerId == v.id && CertificateDates.dayOf(it.date) <= CertificateDates.dayOf(now()) }
    }.orEmpty()

    /** The job types this volunteer did, grouped by category in the order of the certificate. */
    val typeChoices: List<Pair<CertificateCategory, List<String>>> = run {
        val byName = configs.associateBy { it.name.trim() }
        pastJobs.map { it.jobTypeName.trim() }.filter { it.isNotEmpty() }.distinct()
            .groupBy { certificateCategoryOf(byName[it]) }
            .toSortedMap(compareBy { it.ordinal })
            .map { (category, names) -> category to names.sorted() }
    }

    var fromDay by mutableIntStateOf(0)
        private set
    var toDay by mutableIntStateOf(0)
        private set
    var selectedTypes by mutableStateOf<Set<String>>(emptySet())
        private set

    /** False when nothing is left to certify for the current choice. */
    var hasDraft by mutableStateOf(false)
        private set

    private var baseline: Pair<List<ResolvedParagraph>, List<ResolvedParagraph>> = emptyList<ResolvedParagraph>() to emptyList()

    init {
        when (target) {
            is CertificateReviewTarget.Request -> {
                fromDay = target.request.fromDay
                toDay = target.request.toDay
                selectedTypes = target.request.jobTypes.map { it.trim() }.toSet()
            }
            is CertificateReviewTarget.Direct -> {
                fromDay = pastJobs.minOfOrNull { CertificateDates.dayOf(it.date) } ?: todayDay
                toDay = todayDay
                selectedTypes = typeChoices.filter { it.first != CertificateCategory.MEETING }.flatMap { it.second }.toSet()
            }
        }
        reassemble()
    }

    private fun draft(): CertAssembly? {
        val person = volunteer ?: return null
        return when (target) {
            is CertificateReviewTarget.Request ->
                certificateDraft(template, association, person, pastJobs, configs, target.request, now())
            is CertificateReviewTarget.Direct ->
                if (!periodValid(fromDay, toDay)) null
                else directDraft(template, association, person, pastJobs, configs, fromDay, toDay, selectedTypes, now())
        }
    }

    /** Puts the proposed text back in the editors; hand corrections are lost. */
    fun reassemble() {
        val assembly = draft()
        hasDraft = assembly != null
        CertificateResolvedText.load(body, assembly?.body.orEmpty())
        CertificateResolvedText.load(footer, assembly?.footer.orEmpty())
        baseline = current()
    }

    /** Direct mode: a new period or set of types. The caller asks first when [isEdited]. */
    fun select(from: Int, to: Int, types: Set<String>) {
        if (!isDirect) return
        fromDay = from
        toDay = to
        selectedTypes = types
        reassemble()
    }

    fun periodValid(from: Int = fromDay, to: Int = toDay): Boolean = from in 20000101..to && to <= todayDay

    fun bodyParagraphs(): List<ResolvedParagraph> = CertificateResolvedText.read(body)
    fun footerParagraphs(): List<ResolvedParagraph> = CertificateResolvedText.read(footer)

    private fun current() = bodyParagraphs() to footerParagraphs()

    /** The text differs from the one proposed. */
    fun isEdited(): Boolean = current() != baseline

    fun preview(): CertAssembly? = bodyParagraphs().takeIf { it.isNotEmpty() }?.let { CertAssembly(it, footerParagraphs()) }

    /** Where the certificate goes: the request's address, or the profile's in direct mode. */
    val recipient: String = when (target) {
        is CertificateReviewTarget.Request -> target.request.email.trim()
        is CertificateReviewTarget.Direct -> target.volunteer.email.trim()
    }

    val recipientValid: Boolean get() = ValidationUtils.isValidEmail(recipient)

    fun canApprove(): Boolean = target is CertificateReviewTarget.Request && !onlyRefusal && bodyParagraphs().isNotEmpty()

    fun canSend(): Boolean = isDirect && recipientValid && periodValid() && bodyParagraphs().isNotEmpty()

    /** The document title: the first title-sized paragraph, else the first one. */
    fun title(): String {
        val paragraphs = bodyParagraphs()
        val p = paragraphs.firstOrNull { it.size == CertSize.TITLE } ?: paragraphs.firstOrNull()
        return p?.runs?.joinToString("") { it.text }?.trim()?.take(200).orEmpty()
    }

    /** Sends the text as it stands in the editors. Null when there is nothing to approve (nothing is called). */
    suspend fun approve(actions: CertificateReviewActions): ApproveResult? {
        val request = (target as? CertificateReviewTarget.Request)?.request ?: return null
        if (!canApprove()) return null
        return actions.approve(request.requestId, bodyParagraphs(), footerParagraphs(), title(), signer)
    }

    suspend fun refuse(actions: CertificateReviewActions, note: String): Boolean {
        val request = (target as? CertificateReviewTarget.Request)?.request ?: return false
        return actions.refuse(request.requestId, note.trim().take(MAX_NOTE))
    }

    /** Direct mode: writes the approval and returns its id, or null when nothing was written. */
    suspend fun sendDirect(actions: CertificateReviewActions): String? {
        val person = (target as? CertificateReviewTarget.Direct)?.volunteer ?: return null
        if (!canSend()) return null
        return actions.sendDirect(person, bodyParagraphs(), footerParagraphs(), title(), signer)
    }

    companion object {
        const val MAX_NOTE = com.eventmanager.app.portal.certificate.CertificateReview.MAX_NOTE

        /** The panel button shows only for a volunteer with a job that already took place. */
        fun hasPastJob(volunteer: Volunteer, jobs: List<Job>, now: Long = System.currentTimeMillis()): Boolean {
            val today = CertificateDates.dayOf(now)
            return jobs.any { it.volunteerId == volunteer.id && CertificateDates.dayOf(it.date) <= today }
        }
    }
}
