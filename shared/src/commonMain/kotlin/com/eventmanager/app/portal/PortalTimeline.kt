package com.eventmanager.app.portal

import com.eventmanager.app.data.models.Benefit
import com.eventmanager.app.data.models.BenefitCalculator
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.models.VolunteerRank
import com.eventmanager.app.data.models.isNovaMeetingOnlyStylePerk
import com.eventmanager.app.data.utils.DateTimeUtils
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
    private const val DAY = 24L * 60 * 60 * 1000

    fun build(
        volunteer: Volunteer,
        jobs: List<Job>,
        configs: List<JobTypeConfig>,
        now: Long,
        offsetHours: Int,
    ): PortalTimeline {
        // Same day convention as BenefitCalculator: Geneva day start plus offsetHours.
        fun dayStart(t: Long) = DateTimeUtils.getStartOfDayWithOffset(t, offsetHours)

        val today = dayStart(now)
        val horizonEnd = dayStart(today + HORIZON_DAYS * DAY + DAY / 2)
        val upcoming = jobs.filter { it.date > now }.sortedBy { it.date }.take(MAX_UPCOMING)

        // A shift counts from the start of its own day, so that night's perks show on that day.
        fun visibleAt(point: Long, source: List<Job>) =
            source.filter { it.date <= point || dayStart(it.date) <= point }

        fun stepAt(point: Long, source: List<Job>): PortalTimelineStep {
            val visible = visibleAt(point, source)
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

        val computed = sortedMapOf<Long, PortalTimelineStep>()
        val pending = ArrayDeque<Long>()
        fun addPoint(point: Long) {
            if (point !in computed && point !in pending) pending.addLast(point)
        }
        for (i in 0..HORIZON_DAYS) addPoint(dayStart(today + i * DAY + DAY / 2))
        upcoming.forEach { addPoint(dayStart(it.date)) }
        while (pending.isNotEmpty()) {
            val point = pending.removeFirst()
            val step = stepAt(point, jobs)
            computed[point] = step
            // A benefit ending inside the horizon changes the picture the moment after it ends.
            step.benefits.mapNotNull { it.validUntil }
                .filter { it + 1 in today..horizonEnd }
                .forEach { addPoint(it + 1) }
        }

        val steps = mutableListOf<PortalTimelineStep>()
        for (step in computed.values) {
            val last = steps.lastOrNull()
            if (last == null || !sameState(last, step)) steps += step
        }

        val gains = upcoming.map { job ->
            val point = dayStart(job.date)
            val with = stepAt(point, jobs).benefits
            val without = stepAt(point, jobs.filter { it !== job }).benefits
            PortalShiftGains(job.jobNanoId, gainsBetween(without, with))
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
