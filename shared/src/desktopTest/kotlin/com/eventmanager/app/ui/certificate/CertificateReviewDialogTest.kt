package com.eventmanager.app.ui.certificate

import androidx.compose.ui.text.TextRange
import com.eventmanager.app.data.models.Gender
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobType
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.NovaJobType
import com.eventmanager.app.data.models.ShiftTime
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.portal.ApproveResult
import com.eventmanager.app.portal.DirectSendStatus
import com.eventmanager.app.portal.certificate.CertSigner
import com.eventmanager.app.portal.certificate.CertificateDefaults
import com.eventmanager.app.portal.certificate.CertificateReviewRequest
import com.eventmanager.app.portal.certificate.ResolvedParagraph
import com.eventmanager.app.portal.certificate.directDraft
import com.eventmanager.app.ui.components.settings.certificate.CertificateDocHtml
import com.eventmanager.app.ui.components.settings.certificate.CertificateResolvedText
import com.eventmanager.app.ui.components.settings.certificate.CertificateReviewActions
import com.eventmanager.app.ui.components.settings.certificate.CertificateReviewModel
import com.eventmanager.app.ui.components.settings.certificate.CertificateReviewTarget
import com.eventmanager.app.ui.components.settings.certificate.CertificateTextActions
import com.eventmanager.app.ui.components.settings.certificate.newCertificateRequests
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking

class CertificateReviewDialogTest {
    private val zone = ZoneId.of("Europe/Zurich")
    private fun millis(day: LocalDate) = day.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private val nowMs = millis(LocalDate.of(2026, 6, 15))
    private val template = CertificateDefaults.template("fr", "Noctua").copy(place = "Genève", signer = CertSigner("Jean Dupont", "Président"))
    private val configs = listOf(
        JobTypeConfig(name = "Bar", description = ""),
        JobTypeConfig(name = "Comité", description = "", novaJobType = NovaJobType.MEETING),
    )
    private val lea = Volunteer(
        id = "V1", name = "Léa", lastNameAbbreviation = "Martin", email = "lea@example.org",
        phoneNumber = "", gender = Gender.FEMALE, portalUid = "PortalUid000000000000001",
    )

    private fun job(type: String, day: LocalDate, volunteerId: String = "V1") = Job(
        volunteerId = volunteerId, jobType = JobType.BAR, jobTypeName = type, venueName = "V",
        date = millis(day), shiftTime = ShiftTime.BEFORE_MIDNIGHT,
    )

    private val jobs = listOf(
        job("Bar", LocalDate.of(2025, 3, 8)),
        job("Bar", LocalDate.of(2026, 2, 1)),
        job("Comité", LocalDate.of(2026, 4, 2)),
        job("Bar", LocalDate.of(2026, 9, 1)), // not yet
    )

    private val request = CertificateReviewRequest(
        requestId = "PortalUid000000000000001_CERTIFICATE_EMAIL",
        portalUid = lea.portalUid,
        email = "lea@example.org",
        fromDay = 20250101,
        toDay = 20260615,
        jobTypes = listOf("Bar"),
        requestedAt = nowMs - 3_600_000,
        status = "PENDING_REVIEW",
        stuck = false,
        lastReason = "",
    )

    private class FakeActions : CertificateReviewActions {
        val approved = mutableListOf<Pair<String, List<ResolvedParagraph>>>()
        val refused = mutableListOf<Pair<String, String>>()
        val direct = mutableListOf<Volunteer>()

        override suspend fun approve(requestId: String, body: List<ResolvedParagraph>, footer: List<ResolvedParagraph>, title: String, signer: CertSigner): ApproveResult {
            approved += requestId to body
            return ApproveResult.SENT_TO_SERVER
        }

        override suspend fun refuse(requestId: String, note: String): Boolean {
            refused += requestId to note
            return true
        }

        override suspend fun sendDirect(volunteer: Volunteer, body: List<ResolvedParagraph>, footer: List<ResolvedParagraph>, title: String, signer: CertSigner): String? {
            direct += volunteer
            return "approval-1"
        }

        override fun directStatus(approvalId: String): Flow<DirectSendStatus> = flowOf(DirectSendStatus.Pending)
        override suspend fun dismissDirect(approvalId: String) = Unit
    }

    private fun reviewModel(volunteer: Volunteer? = lea) =
        CertificateReviewModel(CertificateReviewTarget.Request(request, volunteer), template, "Noctua", jobs, configs) { nowMs }

    private fun directModel(volunteer: Volunteer = lea) =
        CertificateReviewModel(CertificateReviewTarget.Direct(volunteer), template, "Noctua", jobs, configs) { nowMs }

    private fun text(paragraphs: List<ResolvedParagraph>) = paragraphs.joinToString("\n") { p -> p.runs.joinToString("") { it.text } }

    @Test
    fun cannotApproveAnEmptyText() = runBlocking {
        val model = reviewModel()
        assertTrue(model.canApprove())
        model.body.setHtml("")
        assertFalse(model.canApprove())
        val actions = FakeActions()
        assertNull(model.approve(actions))
        assertTrue(actions.approved.isEmpty())
    }

    @Test
    fun editedTextIsWhatIsSent() = runBlocking {
        val model = reviewModel()
        val before = text(model.bodyParagraphs())
        assertTrue("2025" in before, before)
        assertTrue("Comité" !in before, "only the requested types: $before")
        assertFalse(model.isEdited())

        model.body.setHtml(model.body.toHtml() + "<p>Merci pour le coup de main au vestiaire.</p>")
        val at = model.body.annotatedString.text.indexOf("vestiaire")
        model.body.selection = TextRange(at, at + "vestiaire".length)
        CertificateTextActions.toggleBold(model.body)
        assertTrue(model.isEdited())

        val actions = FakeActions()
        assertEquals(ApproveResult.SENT_TO_SERVER, model.approve(actions))
        val (id, sent) = actions.approved.single()
        assertEquals(request.requestId, id)
        val expected = CertificateResolvedText.fromParagraphs(CertificateDocHtml.fromHtml(model.body.toHtml()))
        assertEquals(expected, sent)
        val last = sent.last()
        assertTrue(last.runs.any { it.b && it.text == "vestiaire" }, last.toString())
        assertTrue(text(sent).endsWith("Merci pour le coup de main au vestiaire."), text(sent))
    }

    @Test
    fun unknownVolunteerOnlyAllowsRefusal() = runBlocking {
        val model = reviewModel(volunteer = null)
        assertTrue(model.onlyRefusal)
        assertFalse(model.canApprove())
        assertTrue(model.bodyParagraphs().isEmpty())
        val actions = FakeActions()
        assertNull(model.approve(actions))
        assertTrue(actions.approved.isEmpty())
        assertTrue(model.refuse(actions, "  Pas de fiche à ce nom.  " + "x".repeat(400)))
        val (id, note) = actions.refused.single()
        assertEquals(request.requestId, id)
        assertEquals(300, note.length)
        assertTrue(note.startsWith("Pas de fiche à ce nom."))
    }

    @Test
    fun directModeReassemblesOnPeriodChange() {
        val model = directModel()
        // Default: first job to today, meetings left out, future jobs never offered.
        assertEquals(20250308, model.fromDay)
        assertEquals(20260615, model.toDay)
        assertEquals(setOf("Bar"), model.selectedTypes)
        assertEquals(listOf("Bar", "Comité"), model.typeChoices.flatMap { it.second })
        val full = text(model.bodyParagraphs())
        assertTrue("2025" in full, full)

        model.body.setHtml(model.body.toHtml() + "<p>Ajout à la main.</p>")
        assertTrue(model.isEdited())
        model.select(20260101, 20260615, setOf("Bar", "Comité"))
        val narrowed = model.bodyParagraphs()
        assertNotEquals(full, text(narrowed))
        assertTrue("Ajout à la main" !in text(narrowed), "hand edits are replaced")
        assertFalse(model.isEdited())
        val expected = directDraft(template, "Noctua", lea, jobs, configs, 20260101, 20260615, setOf("Bar", "Comité"), nowMs)!!
        assertEquals(text(expected.body), text(narrowed))

        model.select(20260101, 20260615, emptySet())
        assertFalse(model.hasDraft)
        assertFalse(model.canSend())
    }

    @Test
    fun directModeNeedsAValidAddress() = runBlocking {
        val actions = FakeActions()
        val noMail = directModel(lea.copy(email = "lea@"))
        assertFalse(noMail.recipientValid)
        assertFalse(noMail.canSend())
        assertNull(noMail.sendDirect(actions))
        assertTrue(actions.direct.isEmpty())

        val model = directModel()
        assertTrue(model.canSend())
        assertEquals("approval-1", model.sendDirect(actions))
        assertEquals(listOf(lea), actions.direct)

        // An impossible period blocks the send too.
        model.select(20260615, 20260101, model.selectedTypes)
        assertFalse(model.canSend())
    }

    @Test
    fun templateIsNeverSavedFromTheReviewDialog() {
        val dir = File("src/commonMain/kotlin/com/eventmanager/app/ui/components/settings/certificate")
        val files = dir.listFiles()!!.filter {
            it.name.startsWith("CertificateReview") || it.name == "CertificateRequestToastHost.kt"
        }
        assertTrue(files.size >= 5, files.toString())
        for (file in files) {
            val source = file.readText()
            assertFalse("saveVolunteerPortalCertificateTemplate" in source, file.name)
            assertFalse("CertificateEditorHost" in source, file.name)
        }
        // The window only gets these actions: none of them writes the template.
        val methods = CertificateReviewActions::class.java.methods.map { it.name.lowercase() }
        assertTrue(methods.none { "save" in it || "template" in it }, methods.toString())
        // And the template given to the model stays as it was after edits and sends.
        val copy = template.copy()
        val model = directModel()
        model.body.setHtml("<p>Autre texte</p>")
        runBlocking { model.sendDirect(FakeActions()) }
        assertEquals(copy, template)
    }

    @Test
    fun toastHostIsOnlyMountedInAdmin() {
        val calls = File("src").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && !it.path.contains("Test") }
            .flatMap { file -> file.readLines().mapIndexedNotNull { i, line -> if ("CertificateRequestToastHost(viewModel" in line && "fun " !in line) file to i else null } }
            .toList()
        assertEquals(setOf("AppRootContent.desktop.kt", "AppRootContent.kt"), calls.map { it.first.name }.toSet(), calls.toString())
        assertEquals(2, calls.size, calls.toString())

        for ((file, index) in calls) {
            val lines = file.readLines()
            val before = lines.subList(0, index).joinToString("\n")
            if (file.name == "AppRootContent.desktop.kt") {
                // Inside the admin shell's content, after the ticketing and POS branches.
                val shell = before.lastIndexOf("DesktopAdminShell(")
                assertTrue(shell > before.lastIndexOf("DesktopBilleterieFlow("), "after ticketing")
                assertTrue(shell > before.lastIndexOf("showAdminAuth ->"), "after admin authentication")
                assertTrue(before.lastIndexOf("FloatingActionButton(") > shell, "in the admin content box")
            } else {
                // In the admin branch: after the POS and ticketing branches, next to the admin sync widget.
                val adminBranch = before.lastIndexOf("} else if (showTicketCheck) {")
                assertTrue(adminBranch > 0)
                assertTrue(before.lastIndexOf("SyncStatusWidget(") > adminBranch, "next to the admin sync widget")
                val after = lines.subList(index, lines.size).joinToString("\n")
                assertTrue(after.indexOf("BackendMigrationUiHost(") > 0, "before the global hosts")
                assertTrue(after.indexOf("BackendMigrationUiHost(") < after.indexOf("GuestFormArrivalToastHost("))
            }
        }
    }

    @Test
    fun toastOnlyForNewRequestsWaitingForReview() {
        val known = setOf(request.requestId)
        val other = request.copy(requestId = "B_CERTIFICATE_EMAIL")
        val sending = request.copy(requestId = "C_CERTIFICATE_EMAIL", status = "APPROVED")
        assertEquals(listOf(other), newCertificateRequests(known, listOf(request, other, sending)))
    }
}
