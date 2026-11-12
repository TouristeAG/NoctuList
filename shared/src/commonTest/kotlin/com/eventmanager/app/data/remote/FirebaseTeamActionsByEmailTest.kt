package com.eventmanager.app.data.remote

import kotlin.test.Test
import kotlin.test.assertEquals

class FirebaseTeamActionsByEmailTest {
    private fun rec(uid: String, email: String?) = FirestoreMemberRecord(uid, email, "member")

    @Test
    fun singleMatchReturnsUidIgnoringCaseAndSpaces() {
        val r = resolveMemberUid(listOf(rec("u1", "jean@asso.ch"), rec("u2", "x@asso.ch")), "Jean@Asso.ch ", "u9")
        assertEquals("u1", r.getOrNull())
    }

    @Test
    fun ambiguousEmailIsRefused() {
        val r = resolveMemberUid(listOf(rec("u1", "a@b.ch"), rec("u2", "A@b.ch")), "a@b.ch", null)
        assertEquals("AMBIGUOUS", r.exceptionOrNull()?.message)
    }

    @Test
    fun unknownEmailIsNotFound() {
        val r = resolveMemberUid(listOf(rec("u1", "a@b.ch"), rec("u2", null)), "z@b.ch", null)
        assertEquals("NOT_FOUND", r.exceptionOrNull()?.message)
    }

    @Test
    fun ownEmailIsRefused() {
        val r = resolveMemberUid(listOf(rec("u1", "a@b.ch")), "a@b.ch", "u1")
        assertEquals("SELF", r.exceptionOrNull()?.message)
    }

    @Test
    fun blankEmailIsNotFound() {
        val r = resolveMemberUid(listOf(rec("u1", null)), "  ", null)
        assertEquals("NOT_FOUND", r.exceptionOrNull()?.message)
    }
}
