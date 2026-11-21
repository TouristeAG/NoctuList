package com.eventmanager.app.data.utils

import com.eventmanager.app.data.models.AccountHolderType
import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.AccountTransferType
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobType
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.ShiftTime
import com.eventmanager.app.data.models.jobReferenceKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShiftCreditReconcilerTest {
    private val hour = 60L * 60L * 1000L
    private val day = 24L * hour
    private val configs = listOf(JobTypeConfig(name = "BAR", isShiftJob = true))

    // 2026-03-02 local noon, then shifts spread over March; "now" is mid-April.
    private val march = java.util.Calendar.getInstance().apply {
        set(2026, java.util.Calendar.MARCH, 2, 20, 0, 0); set(java.util.Calendar.MILLISECOND, 0)
    }.timeInMillis
    private val now = march + 45 * day

    private fun job(date: Long, venue: String = "Main", lastModified: Long = date) = Job(
        volunteerId = "v1",
        jobType = JobType.OTHER,
        jobTypeName = "BAR",
        venueName = venue,
        date = date,
        shiftTime = ShiftTime.BEFORE_MIDNIGHT,
        lastModified = lastModified,
    )

    private fun credit(ref: String, amount: Double, j: Job? = null, createdAt: Long = march) = AccountTransfer(
        holderType = AccountHolderType.VOLUNTEER,
        holderId = "v1",
        holderName = "Ada",
        amount = amount,
        type = AccountTransferType.SHIFT_CREDIT,
        sourceReference = ref,
        jobReferenceKey = j?.let { jobReferenceKey(it) }.orEmpty(),
        jobDate = j?.date,
        createdAt = createdAt,
    )

    private fun reversal(ref: String, amount: Double, createdAt: Long = march + day) = AccountTransfer(
        holderType = AccountHolderType.VOLUNTEER,
        holderId = "v1",
        holderName = "Ada",
        amount = amount,
        type = AccountTransferType.SHIFT_REVERSAL,
        sourceReference = ref,
        createdAt = createdAt,
    )

    /** Ledger rows a fresh reconciliation would write for [jobs]. */
    private fun settled(jobs: List<Job>): List<AccountTransfer> =
        ShiftCreditReconciler.plan("v1", jobs, emptyList(), configs, 0, now).credits.map {
            credit(it.sourceReference, it.amount).copy(jobReferenceKey = it.jobReferenceKey, jobDate = it.jobDate)
        }

    private fun plan(jobs: List<Job>, transfers: List<AccountTransfer>, reverseCreatedBefore: Long? = null) =
        ShiftCreditReconciler.plan("v1", jobs, transfers, configs, 0, now, reverseCreatedBefore)

    private fun List<ShiftCreditEntry>.galaxie() = filter { it.sourceReference.startsWith("galaxie_bonus:") }

    @Test
    fun galaxieBonus_isPaidOncePerMonth_evenForPastMonthsAndManyShifts() {
        val jobs = (0 until 5).map { job(march + it * 3 * day) }
        val credits = plan(jobs, emptyList()).credits

        assertEquals(5, credits.count { it.sourceReference.startsWith("shift_credit:") })
        val bonus = credits.galaxie().single()
        assertEquals("galaxie_bonus:v1:2026-03", bonus.sourceReference)
        assertEquals(jobReferenceKey(jobs[2]), bonus.jobReferenceKey)
    }

    @Test
    fun galaxieBonus_needsThreeShiftsInTheSameMonth() {
        val jobs = listOf(job(march), job(march + day), job(march + 40 * day))
        assertTrue(plan(jobs, emptyList()).credits.galaxie().isEmpty())
    }

    @Test
    fun galaxieBonus_isNotPaidBeforeTheThirdShiftDay() {
        val jobs = listOf(job(march), job(march + day), job(now + 2 * day))
        assertTrue(plan(jobs, emptyList()).credits.galaxie().isEmpty())
    }

    @Test
    fun legacyPerShiftBonuses_keepOneAndReverseTheRest() {
        val jobs = (0 until 3).map { job(march + it * day) }
        val legacy = jobs.map { credit("galaxie_bonus:${jobReferenceKey(it)}", 5.0, it) }
        val shiftCredits = settled(jobs).filter { it.sourceReference.startsWith("shift_credit:") }

        val result = plan(jobs, shiftCredits + legacy)

        assertTrue(result.credits.isEmpty())
        assertEquals(2, result.reversals.size)
        assertTrue(result.reversals.all { it.amount == -5.0 })
    }

    @Test
    fun legacyBonusInAMonthWithoutThreeShifts_isReversed() {
        val jobs = listOf(job(march), job(march + 40 * day))
        val legacy = credit("galaxie_bonus:${jobReferenceKey(jobs[0])}", 5.0, jobs[0])
        val result = plan(jobs, settled(jobs) + legacy)
        assertEquals(listOf("reversal:${legacy.sourceReference}"), result.reversals.map { it.sourceReference })
    }

    @Test
    fun cancelledShift_reversesItsFullCreditAndTheMonthBonus() {
        val jobs = (0 until 3).map { job(march + it * day) }
        val ledger = settled(jobs)

        val result = plan(jobs.dropLast(1), ledger)

        assertTrue(result.credits.isEmpty())
        val reversed = result.reversals.associate { it.original.sourceReference to it.amount }
        assertEquals(-10.0, reversed["shift_credit:${jobReferenceKey(jobs[2])}"])
        assertEquals(-5.0, reversed["galaxie_bonus:v1:2026-03"])
        assertEquals(2, reversed.size)
    }

    @Test
    fun editedShift_movesTheCreditInsteadOfAddingOne() {
        val original = job(march)
        val ledger = settled(listOf(original))
        val edited = original.copy(venueName = "Other")

        val result = plan(listOf(edited), ledger)

        assertEquals(listOf("shift_credit:${jobReferenceKey(edited)}"), result.credits.map { it.sourceReference })
        assertEquals(listOf("shift_credit:${jobReferenceKey(original)}"), result.reversals.map { it.original.sourceReference })
    }

    @Test
    fun reAddedShift_isCreditedAgainUnderANewGeneration() {
        val reversedAt = march + 5 * day
        val shift = job(march, lastModified = reversedAt + hour)
        val ref = "shift_credit:${jobReferenceKey(shift)}"
        val ledger = listOf(credit(ref, 10.0, shift), reversal("reversal:$ref", -10.0, reversedAt))

        val result = plan(listOf(shift), ledger)

        assertEquals(listOf("$ref#2"), result.credits.map { it.sourceReference })
        assertTrue(result.reversals.isEmpty())
    }

    @Test
    fun shiftRestoredBySyncWithItsOldTimestamp_staysReversed() {
        val reversedAt = march + 5 * day
        val shift = job(march, lastModified = march)
        val ref = "shift_credit:${jobReferenceKey(shift)}"
        val ledger = listOf(credit(ref, 10.0, shift), reversal("reversal:$ref", -10.0, reversedAt))

        assertTrue(plan(listOf(shift), ledger).isEmpty)
    }

    @Test
    fun settledLedger_planIsEmpty() {
        val jobs = (0 until 4).map { job(march + it * day) }
        assertTrue(plan(jobs, settled(jobs)).isEmpty)
    }

    @Test
    fun partialReversal_isCompletedLater() {
        val shift = job(march)
        val ref = "shift_credit:${jobReferenceKey(shift)}"
        val ledger = listOf(credit(ref, 10.0, shift), reversal("reversal:$ref", -4.0))

        val result = plan(emptyList(), ledger).reversals.single()

        assertEquals(-6.0, result.amount)
        assertEquals("reversal:$ref~2", result.sourceReference)
    }

    @Test
    fun backgroundRun_leavesRecentCreditsAlone() {
        val shift = job(march)
        val ledger = listOf(credit("shift_credit:${jobReferenceKey(shift)}", 10.0, shift, createdAt = now))
        assertTrue(plan(emptyList(), ledger, reverseCreatedBefore = now - hour).reversals.isEmpty())
    }
}
