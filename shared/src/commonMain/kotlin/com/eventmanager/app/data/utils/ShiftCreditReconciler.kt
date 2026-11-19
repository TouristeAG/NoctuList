package com.eventmanager.app.data.utils

import com.eventmanager.app.data.models.AccountTransfer
import com.eventmanager.app.data.models.AccountTransferType
import com.eventmanager.app.data.models.BenefitCalculator
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.affectsAccountLedger
import com.eventmanager.app.data.models.jobReferenceKey

/** A reversal the reconciler wants written: [amount] is negative. */
data class PlannedShiftReversal(
    val original: AccountTransfer,
    val amount: Double,
    val sourceReference: String,
)

data class ShiftCreditPlan(
    val credits: List<ShiftCreditEntry>,
    val reversals: List<PlannedShiftReversal>,
) {
    val isEmpty: Boolean get() = credits.isEmpty() && reversals.isEmpty()
}

/**
 * Compares what a volunteer should have been credited for their shifts with what the ledger
 * holds, and plans the transfers that close the gap.
 *
 * The ledger is append-only, so nothing is ever deleted: a credit that is no longer deserved
 * (shift cancelled, moved, duplicate bonus) gets a reversal, and a credit that is deserved again
 * after having been reversed gets a new generation (`<ref>#2`, `<ref>#3`, ...).
 *
 * The Galaxie bonus is one per volunteer and per calendar month of the shift, granted once the
 * month holds three contributions. Older builds keyed it per shift (`galaxie_bonus:<jobKey>`)
 * and counted the *current* month, which paid it for almost every shift; those rows are read as
 * the bonus of their shift's month so a correct one is kept and the extras are reversed.
 */
object ShiftCreditReconciler {
    private const val EPSILON = 0.005
    private const val SHIFT_PREFIX = "shift_credit:"
    private const val ORION_PREFIX = "orion_wallet:"
    private const val GALAXIE_PREFIX = "galaxie_bonus:"
    private const val REVERSAL_PREFIX = "reversal:"
    private val generationSuffix = Regex("#\\d+$")

    fun galaxieMonthReference(volunteerId: String, monthKey: String): String =
        "$GALAXIE_PREFIX$volunteerId:$monthKey"

    fun monthKey(timestamp: Long, offsetHours: Int): String {
        val cal = java.util.Calendar.getInstance().apply {
            timeInMillis = DateTimeUtils.getStartOfDayWithOffset(timestamp, offsetHours)
        }
        val year = cal.get(java.util.Calendar.YEAR)
        val month = cal.get(java.util.Calendar.MONTH) + 1
        return "$year-${month.toString().padStart(2, '0')}"
    }

    /** Every credit the volunteer's current shifts entitle them to, as of [now]. */
    fun expectedCredits(
        volunteerId: String,
        volunteerJobs: List<Job>,
        jobTypeConfigs: List<JobTypeConfig>,
        offsetHours: Int,
        now: Long,
    ): List<ShiftCreditEntry> {
        val jobs = volunteerJobs
            .filter { it.volunteerId == volunteerId }
            .distinctBy { jobReferenceKey(it) }
            .sortedWith(compareBy<Job> { it.date }.thenBy { jobReferenceKey(it) })
        val entries = mutableListOf<ShiftCreditEntry>()

        // Base credit and the one-off Orion wallet: same rules as a single added shift.
        for (job in jobs) {
            if (!ShiftCreditCalculator.isJobDayReached(job, offsetHours, now)) continue
            entries += ShiftCreditCalculator.creditsForAddedJob(job, jobs, jobTypeConfigs, offsetHours)
                .filterNot { it.sourceReference.startsWith(GALAXIE_PREFIX) }
        }

        entries += galaxieBonuses(volunteerId, jobs, jobTypeConfigs, offsetHours, now)
        return entries.distinctBy { it.sourceReference }
    }

    private fun galaxieBonuses(
        volunteerId: String,
        jobs: List<Job>,
        jobTypeConfigs: List<JobTypeConfig>,
        offsetHours: Int,
        now: Long,
    ): List<ShiftCreditEntry> {
        val ctx = BenefitCalculator.CalculationContext(jobTypeConfigs, now, offsetHours)
        if (ctx.shiftJobTypeNames.isEmpty()) return emptyList()

        val reached = jobs.filter {
            ctx.shiftJobTypeNames.contains(it.jobTypeName) &&
                ShiftCreditCalculator.isJobDayReached(it, offsetHours, now)
        }
        return reached.groupBy { monthKey(it.date, offsetHours) }.mapNotNull { (month, monthJobs) ->
            val monthEnd = DateTimeUtils.getEndOfMonthWithOffset(
                DateTimeUtils.getStartOfDayWithOffset(monthJobs.first().date, offsetHours),
                offsetHours,
            )
            // Orion status as it stood in that month, so a later rank change does not rewrite history.
            val isOrion = BenefitCalculator.isVolunteerOrionActive(
                jobs, jobTypeConfigs, currentTime = minOf(now, monthEnd), offsetHours = offsetHours
            )
            val contributions = monthJobs.filterNot {
                isOrion && ctx.meetingJobTypeNames.contains(it.jobTypeName)
            }
            val third = contributions.getOrNull(2) ?: return@mapNotNull null
            ShiftCreditEntry(
                amount = ShiftCreditCalculator.GALAXIE_BONUS_CHF,
                sourceReference = galaxieMonthReference(volunteerId, month),
                description = "Galaxie monthly bonus drink",
                jobReferenceKey = jobReferenceKey(third),
                jobTypeName = third.jobTypeName,
                jobDate = third.date,
            )
        }
    }

    /**
     * @param transfers every ledger row of this volunteer.
     * @param reverseCreatedBefore when set, only credits created before this instant may be
     *   reversed. Background runs use it so a credit synced from another device is not reversed
     *   before the shift it belongs to has arrived here too.
     */
    fun plan(
        volunteerId: String,
        volunteerJobs: List<Job>,
        transfers: List<AccountTransfer>,
        jobTypeConfigs: List<JobTypeConfig>,
        offsetHours: Int,
        now: Long,
        reverseCreatedBefore: Long? = null,
    ): ShiftCreditPlan {
        val expected = expectedCredits(volunteerId, volunteerJobs, jobTypeConfigs, offsetHours, now)
        val ledger = Ledger(transfers.filter { it.holderId == volunteerId })
        val currentJobKeys = volunteerJobs.map { jobReferenceKey(it) }.toSet()
        val ctx = BenefitCalculator.CalculationContext(jobTypeConfigs, now, offsetHours)

        val credits = mutableListOf<ShiftCreditEntry>()
        val reversals = mutableListOf<PlannedShiftReversal>()

        fun mayReverse(credit: AccountTransfer): Boolean =
            reverseCreatedBefore == null || credit.createdAt < reverseCreatedBefore

        // A credit that was reversed is paid again only when the shift was saved after that
        // reversal (really re-added). A shift restored by sync with its old timestamp stays
        // reversed: someone took that money back on purpose or by a delete we cannot tell apart.
        fun mayCreditAgain(family: List<AccountTransfer>, lastEdit: Long): Boolean {
            val reversedAt = ledger.lastReversalAt(family) ?: return true
            return lastEdit > reversedAt
        }
        fun lastEditOf(jobKey: String): Long =
            volunteerJobs.filter { jobReferenceKey(it) == jobKey }.maxOfOrNull { it.lastModified } ?: 0L

        fun reverse(credit: AccountTransfer) {
            if (!mayReverse(credit)) return
            val net = ledger.netOf(credit)
            if (net <= EPSILON) return
            reversals += PlannedShiftReversal(credit, -net, ledger.nextReversalReference(credit.sourceReference))
        }

        // --- Base shift credits and Orion wallet -------------------------------------------
        val expectedByBase = expected
            .filterNot { it.sourceReference.startsWith(GALAXIE_PREFIX) }
            .associateBy { it.sourceReference }
        val activeByBase = ledger.activeCredits
            .filter { it.sourceReference.startsWith(SHIFT_PREFIX) || it.sourceReference.startsWith(ORION_PREFIX) }
            .groupBy { baseReference(it.sourceReference) }
        val allByBase = ledger.allCredits.groupBy { baseReference(it.sourceReference) }

        // Shifts whose credit stays reversed: they no longer count toward the monthly bonus either.
        val withheldJobKeys = mutableSetOf<String>()

        for ((base, entry) in expectedByBase) {
            val active = activeByBase[base].orEmpty().sortedBy { it.createdAt }
            if (active.isEmpty()) {
                if (mayCreditAgain(allByBase[base].orEmpty(), lastEditOf(entry.jobReferenceKey))) {
                    credits += entry.copy(sourceReference = ledger.nextCreditReference(base))
                } else if (base.startsWith(SHIFT_PREFIX)) {
                    withheldJobKeys += entry.jobReferenceKey
                }
            } else {
                active.drop(1).forEach(::reverse)
            }
        }
        for ((base, active) in activeByBase) {
            if (expectedByBase.containsKey(base)) continue
            for (credit in active) {
                val stillDeserved = when {
                    // A shift that still exists keeps its credit even if its job type was later
                    // switched off: only a cancelled or edited shift loses it.
                    base.startsWith(SHIFT_PREFIX) -> credit.jobReferenceKey in currentJobKeys
                    // Without Orion job types loaded we cannot tell, so leave the wallet alone.
                    else -> ctx.orionJobTypeNames.isEmpty()
                }
                if (!stillDeserved) reverse(credit)
            }
        }

        // --- Galaxie bonus, one per month ---------------------------------------------------
        if (ctx.shiftJobTypeNames.isNotEmpty()) {
            val countedJobs = volunteerJobs.filterNot { jobReferenceKey(it) in withheldJobKeys }
            val expectedByMonth = galaxieBonuses(volunteerId, countedJobs.distinctBy { jobReferenceKey(it) }
                .sortedWith(compareBy<Job> { it.date }.thenBy { jobReferenceKey(it) }), jobTypeConfigs, offsetHours, now)
                .associateBy { it.sourceReference.substringAfterLast(':') }
            val activeByMonth = ledger.activeCredits
                .filter { it.sourceReference.startsWith(GALAXIE_PREFIX) }
                .groupBy { galaxieMonthOf(it, offsetHours) }
            val allByMonth = ledger.allCredits
                .filter { it.sourceReference.startsWith(GALAXIE_PREFIX) }
                .groupBy { galaxieMonthOf(it, offsetHours) }

            for ((month, entry) in expectedByMonth) {
                val active = activeByMonth[month].orEmpty().sortedBy { it.createdAt }
                if (active.isEmpty()) {
                    val monthLastEdit = volunteerJobs
                        .filter { monthKey(it.date, offsetHours) == month }
                        .maxOfOrNull { it.lastModified } ?: 0L
                    if (mayCreditAgain(allByMonth[month].orEmpty(), monthLastEdit)) {
                        credits += entry.copy(sourceReference = ledger.nextCreditReference(entry.sourceReference))
                    }
                } else {
                    active.drop(1).forEach(::reverse)
                }
            }
            for ((month, active) in activeByMonth) {
                if (expectedByMonth.containsKey(month)) continue
                active.forEach(::reverse)
            }
        }

        return ShiftCreditPlan(credits, reversals)
    }

    private fun baseReference(sourceReference: String): String =
        sourceReference.replace(generationSuffix, "")

    private fun galaxieMonthOf(credit: AccountTransfer, offsetHours: Int): String? {
        val body = baseReference(credit.sourceReference).removePrefix(GALAXIE_PREFIX)
        // Current format: <volunteerId>:<yyyy-MM>. Legacy format: <jobReferenceKey>, which has '|'.
        if (!body.contains('|')) return body.substringAfterLast(':')
        val date = credit.jobDate ?: body.split('|').getOrNull(2)?.toLongOrNull() ?: return null
        return monthKey(date, offsetHours)
    }

    private class Ledger(transfers: List<AccountTransfer>) {
        private val allReferences = transfers.map { it.sourceReference }.toSet()
        private val counted = transfers.filter { it.affectsAccountLedger() }
        private val reversalRowsByTarget: Map<String, List<AccountTransfer>> = counted
            .filter { it.type == AccountTransferType.SHIFT_REVERSAL }
            .groupBy { reversalTarget(it.sourceReference) }
        private val reversalsByTarget: Map<String, Double> =
            reversalRowsByTarget.mapValues { (_, rows) -> rows.sumOf { it.amount } }

        val allCredits: List<AccountTransfer> = counted
            .filter { it.type == AccountTransferType.SHIFT_CREDIT }

        fun lastReversalAt(credits: List<AccountTransfer>): Long? = credits
            .flatMap { reversalRowsByTarget[it.sourceReference].orEmpty() }
            .maxOfOrNull { it.createdAt }

        val activeCredits: List<AccountTransfer> = allCredits.filter { netOf(it) > EPSILON }

        fun netOf(credit: AccountTransfer): Double =
            credit.amount + (reversalsByTarget[credit.sourceReference] ?: 0.0)

        fun nextCreditReference(base: String): String {
            if (base !in allReferences) return base
            var generation = 2
            while ("$base#$generation" in allReferences) generation++
            return "$base#$generation"
        }

        /** A partial reversal (balance floor) is completed later under `reversal:<ref>~2`. */
        fun nextReversalReference(creditReference: String): String {
            val first = "$REVERSAL_PREFIX$creditReference"
            if (first !in allReferences) return first
            var attempt = 2
            while ("$first~$attempt" in allReferences) attempt++
            return "$first~$attempt"
        }

        private fun reversalTarget(reversalReference: String): String =
            reversalReference.removePrefix(REVERSAL_PREFIX).replace(Regex("~\\d+$"), "")
    }
}
