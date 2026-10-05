package com.eventmanager.app.data.utils

import com.eventmanager.app.data.models.BenefitCalculator
import com.eventmanager.app.data.models.BenefitSystemType
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobType
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.ManualRewards
import com.eventmanager.app.data.models.ShiftTime
import com.eventmanager.app.data.models.Volunteer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ManualShiftRewardsTest {

    private val anchor = 1_720_000_000_000L
    private val dayStart = DateTimeUtils.getStartOfDayWithOffset(anchor, 0)
    private val hour = 60L * 60L * 1000L
    private val day = 24L * hour

    private fun volunteer() = Volunteer(
        id = "v1",
        name = "Ada",
        lastNameAbbreviation = "A",
        email = "ada@example.com",
        phoneNumber = "1",
    )

    private fun manualConfig(
        credit: Double = 0.0,
        freeDrinks: Int = 0,
        discount: Int = 0,
        invites: Int = 0,
        durationDays: Int = 2,
    ) = JobTypeConfig(
        name = "Accueil",
        isShiftJob = false,
        benefitSystemType = BenefitSystemType.MANUAL,
        manualRewards = ManualRewards(
            durationDays = durationDays,
            freeDrinks = freeDrinks,
            barDiscountPercentage = discount,
            invites = invites,
            accountCreditChf = credit,
        ),
    )

    private fun job(date: Long) = Job(
        volunteerId = "v1",
        jobType = JobType.OTHER,
        jobTypeName = "Accueil",
        venueName = "Main",
        date = date,
        shiftTime = ShiftTime.BEFORE_MIDNIGHT,
    )

    @Test
    fun shiftCredit_usesSavedAmountOnTheShiftDay() {
        val jobDate = dayStart + 12 * hour
        val config = manualConfig(credit = 12.5, freeDrinks = 8)
        val entry = ShiftCreditCalculator.creditsForJob(
            job = job(jobDate),
            volunteerJobs = listOf(job(jobDate)),
            jobTypeConfigs = listOf(config),
            now = jobDate,
        ).single()

        assertEquals(12.5, entry.amount, 1e-9)
        assertTrue(entry.sourceReference.startsWith("shift_credit:"))
    }

    @Test
    fun shiftCredit_legacyFreeDrinkCountIsTheChfAmount() {
        val jobDate = dayStart + 12 * hour
        val config = manualConfig(credit = 0.0, freeDrinks = 8)
        val entry = ShiftCreditCalculator.creditsForJob(
            job = job(jobDate),
            volunteerJobs = listOf(job(jobDate)),
            jobTypeConfigs = listOf(config),
            now = jobDate,
        ).single()

        assertEquals(8.0, entry.amount, 1e-9)
    }

    @Test
    fun shiftCredit_waitsUntilTheShiftDay() {
        val future = dayStart + 2 * day
        val credits = ShiftCreditCalculator.creditsForJob(
            job = job(future),
            volunteerJobs = listOf(job(future)),
            jobTypeConfigs = listOf(manualConfig(credit = 12.5)),
            now = dayStart + hour,
        )
        assertTrue(credits.isEmpty())
    }

    @Test
    fun discountAndInvites_applyOnlyDuringTheConfiguredDuration() {
        val jobDate = dayStart + 12 * hour
        val config = manualConfig(discount = 30, invites = 2, durationDays = 2, freeDrinks = 4)
        val periodEnd = dayStart + 2 * day

        val during = benefitsAt(jobDate, config, dayStart + hour)
        assertEquals(30, during.barDiscount)
        assertEquals(2, during.inviteCount)
        assertEquals(true, during.friendInvitation)
        assertEquals(true, during.guestListAccess)
        assertEquals(0, during.drinkTokens)
        assertEquals(true, during.isActive)

        val atEnd = benefitsAt(jobDate, config, periodEnd)
        assertEquals(30, atEnd.barDiscount)
        assertEquals(2, atEnd.inviteCount)

        val before = benefitsAt(jobDate, config, dayStart - 1)
        assertEquals(0, before.barDiscount)
        assertEquals(0, before.inviteCount)
        assertEquals(false, before.guestListAccess)
        assertEquals(false, before.isActive)

        val after = benefitsAt(jobDate, config, periodEnd + 1)
        assertEquals(0, after.barDiscount)
        assertEquals(0, after.inviteCount)
        assertEquals(false, after.guestListAccess)
        assertEquals(false, after.isActive)
    }

    @Test
    fun pos_appliesActiveDiscountToEligibleCashAndKeepsCreditAtFullPrice() {
        val jobDate = dayStart + 12 * hour
        val config = manualConfig(discount = 30, invites = 2)
        val percent = benefitsAt(jobDate, config, jobDate).barDiscount
        assertEquals(30, percent)

        val eligible = PosCartLine(1L, "Beer", 20.0, 1, barDiscountEligible = true)
        val withCredit = computePosPayment(listOf(eligible), accountBalance = 10.0, barDiscountPercent = percent)
        assertEquals(10.0, withCredit.creditPaid, 1e-9)
        assertEquals(10.0, withCredit.cashOrCardBeforeDiscount, 1e-9)
        assertEquals(7.0, withCredit.cashOrCardDue, 1e-9)
        assertEquals(-10.0, computePosLedgerAmount(withCredit), 1e-9)

        val cashOnly = computePosPayment(listOf(eligible), accountBalance = 0.0, barDiscountPercent = percent)
        assertEquals(0.0, cashOnly.creditPaid, 1e-9)
        assertEquals(14.0, cashOnly.cashOrCardDue, 1e-9)

        val ineligible = PosCartLine(2L, "Ticket", 20.0, 1, barDiscountEligible = false)
        val skipped = computePosPayment(listOf(ineligible), accountBalance = 0.0, barDiscountPercent = percent)
        assertEquals(20.0, skipped.cashOrCardDue, 1e-9)

        val expired = benefitsAt(jobDate, config, dayStart + 3 * day).barDiscount
        val fullPrice = computePosPayment(listOf(eligible), accountBalance = 0.0, barDiscountPercent = expired)
        assertEquals(0, expired)
        assertEquals(20.0, fullPrice.cashOrCardDue, 1e-9)
    }

    private fun benefitsAt(jobDate: Long, config: JobTypeConfig, now: Long) =
        BenefitCalculator.calculateVolunteerBenefitStatus(
            volunteer = volunteer(),
            jobs = listOf(job(jobDate)),
            jobTypeConfigs = listOf(config),
            currentTime = now,
            offsetHours = 0,
        ).benefits
}
