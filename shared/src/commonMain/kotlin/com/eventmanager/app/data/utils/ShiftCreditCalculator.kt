package com.eventmanager.app.data.utils

import com.eventmanager.app.data.models.BenefitCalculator
import com.eventmanager.app.data.models.BenefitSystemType
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.NovaJobType
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.models.jobReferenceKey

data class ShiftCreditEntry(
    val amount: Double,
    val sourceReference: String,
    val description: String,
    val jobReferenceKey: String,
    val jobTypeName: String,
    val jobDate: Long
)

object ShiftCreditCalculator {
    private const val CHF_PER_DRINK = 5.0
    private const val ORION_WALLET_CHF = 270.0
    const val GALAXIE_BONUS_CHF = 5.0

    fun isJobDayReached(
        job: Job,
        offsetHours: Int,
        now: Long = System.currentTimeMillis()
    ): Boolean = now >= DateTimeUtils.getStartOfDayWithOffset(job.date, offsetHours)

    /** Resolves nova type; infers MEETING from job type name when Sheets column was never set. */
    fun effectiveNovaJobType(config: JobTypeConfig): NovaJobType {
        if (config.name.equals("MEETING", ignoreCase = true) &&
            config.novaJobType == NovaJobType.DEFAULT_SHIFT
        ) {
            return NovaJobType.MEETING
        }
        return config.novaJobType
    }

    fun creditsForJob(
        job: Job,
        volunteerJobs: List<Job>,
        jobTypeConfigs: List<JobTypeConfig>,
        offsetHours: Int = 0,
        now: Long = System.currentTimeMillis()
    ): List<ShiftCreditEntry> {
        if (!isJobDayReached(job, offsetHours, now)) return emptyList()
        return creditsForAddedJob(job, volunteerJobs, jobTypeConfigs, offsetHours)
    }

    fun creditsForAddedJob(
        job: Job,
        volunteerJobs: List<Job>,
        jobTypeConfigs: List<JobTypeConfig>,
        offsetHours: Int = 0
    ): List<ShiftCreditEntry> {
        val ctx = BenefitCalculator.CalculationContext(jobTypeConfigs, offsetHours = offsetHours)
        val config = ctx.jobTypeConfigsByName[job.jobTypeName] ?: return emptyList()
        val refKey = jobReferenceKey(job)
        val entries = mutableListOf<ShiftCreditEntry>()

        val baseCredit = baseCreditForJob(job, config)
        if (baseCredit > 0) {
            entries += ShiftCreditEntry(
                amount = baseCredit,
                sourceReference = "shift_credit:$refKey",
                description = "Shift credit: ${job.jobTypeName}",
                jobReferenceKey = refKey,
                jobTypeName = job.jobTypeName,
                jobDate = job.date
            )
        }

        if (ctx.orionJobTypeNames.contains(job.jobTypeName)) {
            val orionJobs = volunteerJobs
                .filter { ctx.orionJobTypeNames.contains(it.jobTypeName) }
                .sortedBy { it.date }
            val firstOrion = orionJobs.firstOrNull()
            if (firstOrion != null && jobReferenceKey(firstOrion) == refKey) {
                val mandateYear = mandateYearForOrionJob(firstOrion, offsetHours)
                entries += ShiftCreditEntry(
                    amount = ORION_WALLET_CHF,
                    sourceReference = "orion_wallet:${job.volunteerId}:$mandateYear",
                    description = "Orion internal wallet",
                    jobReferenceKey = refKey,
                    jobTypeName = job.jobTypeName,
                    jobDate = job.date
                )
            }
        }

        // The Galaxie bonus depends on the whole month, not on one shift: see ShiftCreditReconciler.
        return entries
    }

    private fun baseCreditForJob(job: Job, config: JobTypeConfig?): Double {
        if (config == null || !config.isActive) return 0.0
        config.accountCreditChf?.let { return maxOf(0.0, it) }

        if (config.benefitSystemType == BenefitSystemType.MANUAL) {
            return maxOf(0.0, config.manualRewards?.creditedAmountChf() ?: 0.0)
        }

        if (!config.isShiftJob) return 0.0

        val drinks = when (effectiveNovaJobType(config)) {
            NovaJobType.DEFAULT_SHIFT,
            NovaJobType.PHOTOGRAPHER_VIDEOGRAPHER,
            NovaJobType.GRAPHIC_DESIGNER_EVENT -> 2
            NovaJobType.MEETING -> 1
            NovaJobType.GRAPHIC_DESIGNER_ASSOCIATION -> 4
        }
        return drinks * CHF_PER_DRINK
    }

    private fun mandateYearForOrionJob(orionJob: Job, offsetHours: Int): Int {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = com.eventmanager.app.data.utils.DateTimeUtils.getStartOfDayWithOffset(
                orionJob.date, offsetHours
            )
        }
        return cal.get(java.util.Calendar.YEAR)
    }
}
