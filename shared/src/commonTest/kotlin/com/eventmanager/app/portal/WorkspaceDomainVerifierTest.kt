package com.eventmanager.app.portal

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WorkspaceDomainVerifierTest {
    private fun mx(vararg data: String): String =
        """{"Status":0,"Answer":[${data.joinToString(",") { """{"type":15,"data":"$it"}""" }}]}"""

    @Test
    fun parseRecognisesGoogleMxRecords() {
        assertEquals(DomainCheck.GOOGLE, WorkspaceDomainVerifier.parse(mx("1 smtp.google.com.")))
        assertEquals(DomainCheck.GOOGLE, WorkspaceDomainVerifier.parse(mx("10 alt1.aspmx.l.google.com.")))
        assertEquals(DomainCheck.GOOGLE, WorkspaceDomainVerifier.parse(mx("5 ASPMX.L.GOOGLE.COM")))
        assertEquals(DomainCheck.GOOGLE, WorkspaceDomainVerifier.parse(mx("10 mx1.infomaniak.ch.", "1 aspmx.l.google.com.")))
    }

    @Test
    fun parseRejectsOtherProvidersAndLookalikes() {
        assertEquals(DomainCheck.OTHER, WorkspaceDomainVerifier.parse(mx("10 mx1.infomaniak.ch.")))
        assertEquals(DomainCheck.OTHER, WorkspaceDomainVerifier.parse(mx("10 evilgoogle.com.")))
        assertEquals(DomainCheck.OTHER, WorkspaceDomainVerifier.parse("""{"Status":0}"""))
    }

    @Test
    fun parseMapsNxDomainAndGarbage() {
        assertEquals(DomainCheck.NOT_FOUND, WorkspaceDomainVerifier.parse("""{"Status":3}"""))
        assertEquals(DomainCheck.ERROR, WorkspaceDomainVerifier.parse("not json"))
    }

    @Test
    fun acceptedDomainExcludesConsumerAndBareNames() {
        assertFalse(WorkspaceDomainVerifier.isAcceptedDomain("gmail.com"))
        assertFalse(WorkspaceDomainVerifier.isAcceptedDomain("googlemail.com"))
        assertFalse(WorkspaceDomainVerifier.isAcceptedDomain(""))
        assertFalse(WorkspaceDomainVerifier.isAcceptedDomain("asso"))
        assertTrue(WorkspaceDomainVerifier.isAcceptedDomain("asso.ch"))
    }

    @Test
    fun checkQueriesDnsGoogleAndReportsFetchFailure() = runBlocking {
        val urls = mutableListOf<String>()
        val result = WorkspaceDomainVerifier.check("asso.ch") { url -> urls += url; null }
        assertEquals(DomainCheck.ERROR, result)
        assertEquals(listOf("https://dns.google/resolve?name=asso.ch&type=MX"), urls)
    }

    @Test
    fun checkParsesTheFetchedBody() = runBlocking {
        val result = WorkspaceDomainVerifier.check("asso.ch") { mx("1 aspmx.l.google.com.") }
        assertEquals(DomainCheck.GOOGLE, result)
    }
}
