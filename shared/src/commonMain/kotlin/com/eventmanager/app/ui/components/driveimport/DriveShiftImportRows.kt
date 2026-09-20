package com.eventmanager.app.ui.components.driveimport

import com.eventmanager.app.data.drive.MatchConfidence
import com.eventmanager.app.data.drive.MentionMatch
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobType
import com.eventmanager.app.data.models.JobTypeConfig
import com.eventmanager.app.data.models.NovaJobType
import com.eventmanager.app.data.models.ShiftTime
import com.eventmanager.app.data.models.Volunteer

/**
 * One editable line of the import review list. Mirrors the fields of the manual add-shift dialog
 * so an imported shift is indistinguishable from a hand-entered one.
 */
data class DriveImportRow(
    val key: String,
    val match: MentionMatch,
    val volunteer: Volunteer?,
    val jobTypeConfig: JobTypeConfig?,
    val shiftTime: ShiftTime = ShiftTime.BEFORE_MIDNIGHT,
    val dateTime: Long,
    val notes: String = "",
) {
    val label: String get() = match.mention.rawLabel

    /** Profité/pas profité only exists for regular shifts, exactly as in the manual dialog. */
    val showsShiftTime: Boolean
        get() = jobTypeConfig?.let { it.requiresShiftTime && it.novaJobType == NovaJobType.DEFAULT_SHIFT } == true

    val isReady: Boolean get() = volunteer != null && jobTypeConfig != null

    fun toJob(venueName: String, sharedDateTime: Long?): Job? {
        val volunteer = volunteer ?: return null
        val config = jobTypeConfig ?: return null
        return Job(
            volunteerId = volunteer.id,
            jobType = JobType.OTHER,
            jobTypeName = config.name,
            venueName = venueName,
            date = sharedDateTime ?: dateTime,
            shiftTime = if (showsShiftTime) shiftTime else ShiftTime.BEFORE_MIDNIGHT,
            notes = notes,
        )
    }
}

object DriveImportRowFactory {

    /**
     * The default shift type for an imported document is a Stellar "meeting": planning documents
     * and minutes ("PV") are what this feature is for. Falls back to nothing, forcing a choice,
     * rather than silently picking an unrelated type.
     */
    fun defaultJobTypeConfig(configs: List<JobTypeConfig>): JobTypeConfig? {
        val active = configs.filter { it.isActive }
        return active.firstOrNull {
            it.novaJobType == NovaJobType.MEETING &&
                it.benefitSystemType == com.eventmanager.app.data.models.BenefitSystemType.STELLAR
        }
    }

    fun build(
        matches: List<MentionMatch>,
        configs: List<JobTypeConfig>,
        defaultDateTime: Long,
    ): List<DriveImportRow> {
        val default = defaultJobTypeConfig(configs)
        return matches.mapIndexed { index, match ->
            DriveImportRow(
                key = "${index}_${match.mention.dedupeKey}",
                match = match,
                volunteer = match.selectedVolunteer,
                jobTypeConfig = default,
                dateTime = defaultDateTime,
            )
        }
    }
}

/** Drives the colour and wording of the badge next to each matched volunteer. */
fun MatchConfidence.isActionRequired(): Boolean =
    this == MatchConfidence.AMBIGUOUS || this == MatchConfidence.UNMATCHED
