package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Benefit
import com.eventmanager.app.data.models.BenefitCalculator
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.models.VolunteerRank
import com.eventmanager.app.data.models.isNovaMeetingOnlyStylePerk
import com.eventmanager.app.data.utils.VolunteerActivityManager

/** Benefit state that holds from [from] (epoch millis) until the next step starts. */
data class PortalTimelineStep(
    val from: Long,
    val active: Boolean,
    val summary: Benefit?,
    val benefits: List<Benefit>,
    val uniqueEntriesRemaining: Int?,
)

/** What an upcoming shift adds once it has happened. */
data class PortalShiftGains(val jobNanoId: String, val gains: List<Benefit>)

data class PortalTimeline(val steps: List<PortalTimelineStep>, val gains: List<PortalShiftGains>)

/**
 * Benefit state over the next days, computed with [BenefitCalculator] so a web page can show
 * time-correct benefits without a device online. All points are day-aligned, so publishing the
 * same data twice on the same day gives an identical result.
 */
object PortalTimelineBuilder {
    const val HORIZON_DAYS = 30
    const val MAX_UPCOMING = 10
    private const val HOUR = 60L * 60 * 1000
    private const val DAY = 24 * HOUR

    fun build(
        volunteer: Volunteer,
        jobs: List<Job>,
        configs: List<JobTypeConfig>,
        now: Long,
        offsetHours: Int,
    ): PortalTimeline {
        val shiftOffset = offsetHours * HOUR
        fun dayStart(t: Long) = Math.floorDiv(t + shiftOffset, DAY) * DAY - shiftOffset

        val today = dayStart(now)
        val horizonEnd = today + HORIZON_DAYS * DAY
        val upcoming = jobs.filter { it.date > now }.sortedBy { it.date }.take(MAX_UPCOMING)
        // The local midnight after each shift: from there the shift is counted.
        val afterShift = upcoming.associateWith { dayStart(it.date) + DAY }

        val points = sortedSetOf<Long>()
        for (i in 0..HORIZON_DAYS) points += today + i * DAY
        points += afterShift.values

        fun statusAt(point: Long): PortalTimelineStep {
            val visible = jobs.filter { it.date <= point }
            val status = BenefitCalculator.calculateVolunteerBenefitStatusFromVolunteerJobs(
                volunteer, visible, configs, point, offsetHours,
            )
            val benefits = status.activeBenefits
                .filter { it.isActive || (it.futureEventEntriesRemaining ?: 0) > 0 }
                .filterNot { status.rank == VolunteerRank.ORION && it.isNovaMeetingOnlyStylePerk() }
            return PortalTimelineStep(
                from = point,
                active = VolunteerActivityManager.isVolunteerActive(volunteer, visible),
                summary = if (benefits.isNotEmpty()) status.benefits else null,
                benefits = benefits,
                uniqueEntriesRemaining = status.benefits.futureEventEntriesRemaining,
            )
        }

        val raw = points.map { statusAt(it) }
        // A benefit ending inside the horizon changes the picture the moment after it ends.
        raw.flatMap { it.benefits }
            .mapNotNull { it.validUntil }
            .filter { it + 1 in today..horizonEnd }
            .forEach { points += it + 1 }

        val steps = mutableListOf<PortalTimelineStep>()
        for (point in points) {
            val step = raw.firstOrNull { it.from == point } ?: statusAt(point)
            val last = steps.lastOrNull()
            if (last == null || !sameState(last, step)) steps += step
        }

        fun stepAt(t: Long): PortalTimelineStep = steps.lastOrNull { it.from <= t } ?: steps.first()

        val gains = upcoming.map { job ->
            val point = afterShift.getValue(job)
            PortalShiftGains(job.jobNanoId, gainsBetween(stepAt(point - 1).benefits, stepAt(point).benefits))
        }
        return PortalTimeline(steps, gains)
    }

    private fun sameState(a: PortalTimelineStep, b: PortalTimelineStep) =
        a.active == b.active && a.summary == b.summary && a.benefits == b.benefits &&
            a.uniqueEntriesRemaining == b.uniqueEntriesRemaining

    /** Benefits in [after] that [before] does not already hold for at least as long. */
    private fun gainsBetween(before: List<Benefit>, after: List<Benefit>): List<Benefit> {
        val held = before.groupBy { contentKey(it) }.mapValues { (_, list) -> list.maxOf { it.validUntil ?: Long.MAX_VALUE } }
        return after.filter { benefit ->
            val heldUntil = held[contentKey(benefit)] ?: return@filter true
            (benefit.validUntil ?: Long.MAX_VALUE) > heldUntil
        }
    }

    private fun contentKey(b: Benefit): Benefit = b.copy(validUntil = null)
}
