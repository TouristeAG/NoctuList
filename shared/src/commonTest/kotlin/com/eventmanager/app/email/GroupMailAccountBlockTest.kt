package com.eventmanager.app.email

import com.eventmanager.app.data.models.Guest
import com.eventmanager.app.portal.temporaryGroupMailAccount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val URL = "https://forms.example.org/v/org-a"

class GroupMailAccountBlockTest {
    private val labels = PortalAccountLabels(
        title = "Online space",
        address = "Address",
        login = "Sign-in e-mail",
        password = "Password",
        usualPassword = "your usual password",
    )

    private fun options(account: PortalAccountBlock?) = QrEmailHtmlOptions(
        holderName = "Band",
        contentBefore = "Hello",
        contentAfter = "See you",
        signature = "Team",
        includeQr = true,
        headerText = "Guest list",
        footerText = "Footer",
        qrAttachmentText = "QR",
        qrAttachmentNote = "Note",
        includeDigitalWalletPass = false,
        digitalWalletPassTitle = "",
        digitalWalletPassDescription = "",
        digitalWalletPassCompatibility = "",
        includeLogo = false,
        useContentId = true,
        theme = QrEmailTheme.TempGuest,
        portalAccount = account,
        portalAccountLabels = labels,
    )

    private fun temp(nanoId: String, artist: String, contact: String, openedFor: String, open: Boolean) = Guest(
        nanoId = nanoId,
        name = nanoId,
        invitations = 1,
        venueName = "Club",
        isTemporaryGuest = true,
        temporaryArtistName = artist,
        temporaryEventDate = 1_800_000_000_000L,
        temporaryVenueName = "Club",
        temporaryContactEmail = contact,
        temporaryPortalEmail = openedFor,
        portalEnabled = open,
        portalUid = if (open) "uid-$artist" else "",
    )

    @Test
    fun groupMailContainsAccountBlockWithPassword() {
        val block = PortalAccountBlock(URL, "manager@example.org", "Xy7-secret_Pw")
        val html = QrEmailHtmlBuilder.build(options(block))
        val text = QrEmailHtmlBuilder.buildPlainText("Hello", "See you", "Team", true, false, block, labels)
        for (out in listOf(html, text)) {
            assertTrue(URL in out)
            assertTrue("manager@example.org" in out)
            assertTrue("Xy7-secret_Pw" in out)
            assertTrue("Online space" in out)
            assertFalse("your usual password" in out)
        }
    }

    @Test
    fun groupMailContainsAccountBlockWithoutPassword() {
        val block = PortalAccountBlock(URL, "manager@example.org", null)
        val html = QrEmailHtmlBuilder.build(options(block))
        val text = QrEmailHtmlBuilder.buildPlainText("Hello", "See you", "Team", true, false, block, labels)
        for (out in listOf(html, text)) {
            assertTrue("manager@example.org" in out)
            assertTrue("your usual password" in out)
        }
    }

    @Test
    fun groupMailWithoutBlockHasNoAccountSection() {
        val html = QrEmailHtmlBuilder.build(options(null))
        val text = QrEmailHtmlBuilder.buildPlainText("Hello", "See you", "Team", true, false, null, labels)
        for (out in listOf(html, text)) {
            assertFalse("Online space" in out)
            assertFalse("Sign-in e-mail" in out)
        }
    }

    @Test
    fun accountValuesAreEscapedInHtml() {
        val html = QrEmailHtmlBuilder.build(options(PortalAccountBlock(URL, "a<b>@example.org", "p&<q")))
        assertFalse("a<b>@example.org" in html)
        assertTrue("a&lt;b&gt;@example.org" in html)
        assertTrue("p&amp;&lt;q" in html)
    }

    @Test
    fun groupMailAccountFollowsTheOpenedAddressOfThisGroupOnly() {
        val groupA = listOf(temp("A1", "Alpha", "alpha@example.org", "alpha@example.org", true))
        val groupB = listOf(temp("B1", "Beta", "beta@example.org", "beta@example.org", true))
        val forA = assertNotNull(temporaryGroupMailAccount(groupA, "Alpha@example.org", URL, password = null, spaceOn = true))
        assertEquals("alpha@example.org", forA.loginEmail)
        assertNull(forA.password)
        val html = QrEmailHtmlBuilder.build(options(forA))
        assertFalse("beta@example.org" in html)
        assertNotNull(temporaryGroupMailAccount(groupB, "beta@example.org", URL, null, true))
    }

    @Test
    fun groupMailAccountCarriesThePasswordOnlyWhenGiven() {
        val group = listOf(temp("A1", "Alpha", "alpha@example.org", "alpha@example.org", true))
        assertEquals("pw-once", temporaryGroupMailAccount(group, "alpha@example.org", URL, "pw-once", true)?.password)
    }

    @Test
    fun noAccountBlockWhenSpaceClosedOffOrOtherRecipient() {
        val closed = listOf(temp("A1", "Alpha", "alpha@example.org", "alpha@example.org", false))
        assertNull(temporaryGroupMailAccount(closed, "alpha@example.org", URL, null, true))
        val open = listOf(temp("A1", "Alpha", "alpha@example.org", "alpha@example.org", true))
        assertNull(temporaryGroupMailAccount(open, "alpha@example.org", URL, null, spaceOn = false))
        // Space opened for an old address: a mail to the new contact must not hand out that account.
        assertNull(temporaryGroupMailAccount(open, "new@example.org", URL, null, true))
    }
}
