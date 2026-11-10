package com.eventmanager.app.portal

import com.eventmanager.app.data.models.BenefitCalculator
import com.eventmanager.app.data.models.BenefitSystemType
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobType
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.ManualRewards
import com.eventmanager.app.data.models.ShiftTime
import com.eventmanager.app.data.models.Volunteer
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PortalTimelineBuilderTest {
    private val hour = 60L * 60 * 1000
    private val day = 24 * hour
    private val now = Instant.parse("2026-11-10T15:00:00Z").toEpochMilli()
    private val volunteer = Volunteer(
        id = "v1", name = "Ada", lastNameAbbreviation = "A", email = "ada@example.org", phoneNumber = "1",
    )

    private fun config(freeEntry: Boolean = true, durationDays: Int = 3) = JobTypeConfig(
        name = "Accueil",
        isShiftJob = false,
        benefitSystemType = BenefitSystemType.MANUAL,
        manualRewards = ManualRewards(durationDays = durationDays, freeEntry = freeEntry, invites = 1),
    )

    private fun job(date: Long, id: String = "j$date") = Job(
        jobNanoId = id,
        volunteerId = "v1",
        jobType = JobType.OTHER,
        jobTypeName = "Accueil",
        venueName = "Main",
        date = date,
        shiftTime = ShiftTime.BEFORE_MIDNIGHT,
    )

    private fun build(jobs: List<Job>, offset: Int = 0, cfg: JobTypeConfig = config()) =
        PortalTimelineBuilder.build(volunteer, jobs, listOf(cfg), now, offset)

    private fun localMidnightAfter(t: Long, offset: Int): Long = Math.floorDiv(t + offset * hour, day) * day - offset * hour + day

    @Test
    fun firstStepStartsAtLocalMidnight() {
        val timeline = build(emptyList(), offset = 2)
        assertEquals(Instant.parse("2026-11-09T22:00:00Z").toEpochMilli(), timeline.steps.first().from)
    }

    @Test
    fun identicalConsecutiveStepsAreMerged() {
        assertEquals(1, build(emptyList()).steps.size)
    }

    @Test
    fun upcomingShiftCreatesStepAndGains() {
        val shift = now + day
        val timeline = build(listOf(job(shift)))
        val expectedFrom = localMidnightAfter(shift, 0)
        assertTrue(timeline.steps.any { it.from == expectedFrom })
        assertTrue(timeline.steps.first { it.from == expectedFrom }.benefits.any { it.freeEntry })
        val gains = timeline.gains.single()
        assertEquals("j$shift", gains.jobNanoId)
        assertTrue(gains.gains.any { it.freeEntry })
    }

    @Test
    fun shiftAt2300LocalCountsForThatDay() {
        // 23:00 local (UTC+2) on 2026-11-11 is 21:00 UTC.
        val shift = Instant.parse("2026-11-11T21:00:00Z").toEpochMilli()
        val timeline = build(listOf(job(shift)), offset = 2)
        val followingLocalMidnight = Instant.parse("2026-11-11T22:00:00Z").toEpochMilli()
        val step = timeline.steps.first { it.benefits.any { b -> b.freeEntry } }
        assertEquals(followingLocalMidnight, step.from)
        assertTrue(timeline.gains.single().gains.any { it.freeEntry })
    }

    @Test
    fun expiryCreatesStep() {
        val past = job(now - day)
        val cfg = config(durationDays = 6)
        val status = BenefitCalculator.calculateVolunteerBenefitStatusFromVolunteerJobs(volunteer, listOf(past), listOf(cfg), now, 0)
        val validUntil = status.activeBenefits.mapNotNull { it.validUntil }.single()
        assertTrue(validUntil in (now + 4 * day)..(now + 6 * day), "setup: expiry should be a few days out")
        val timeline = build(listOf(past), cfg = cfg)
        val step = timeline.steps.first { it.from == validUntil + 1 }
        assertTrue(timeline.steps.first().benefits.any { it.freeEntry })
        assertFalse(step.benefits.any { it.validUntil == validUntil })
        assertFalse(step.benefits.any { it.freeEntry })
    }

    @Test
    fun shiftBeyondHorizonStillGetsGains() {
        val timeline = build(listOf(job(now + 45 * day)))
        assertTrue(timeline.gains.single().gains.isNotEmpty())
    }

    @Test
    fun atMostTenUpcomingShiftsGetGains() {
        val jobs = (1..12).map { job(now + it * 3 * day, "n$it") }
        val timeline = build(jobs)
        assertEquals(10, timeline.gains.size)
        assertEquals((1..10).map { "n$it" }, timeline.gains.map { it.jobNanoId })
    }

    @Test
    fun alreadyHeldBenefitIsNotAGain() {
        // The benefit from yesterday's shift is held before and after; the new shift adds nothing.
        val cfg = config(durationDays = 5)
        val noPerks = job(now + 2 * day, "later").copy(jobTypeName = "Unconfigured")
        val unchanged = build(listOf(job(now - day), noPerks), cfg = cfg)
        assertTrue(unchanged.steps.first().benefits.any { it.freeEntry })
        assertTrue(unchanged.gains.single().gains.isEmpty(), "same validUntil must not count as a gain")

        val extended = build(listOf(job(now - day), job(now + 2 * day, "later")), cfg = cfg)
        assertNotNull(extended.gains.singleOrNull())
        assertTrue(extended.gains.single().gains.any { it.freeEntry }, "later validUntil is a gain")
    }
}
