package com.eventmanager.app.data.utils

import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.Volunteer
import java.util.Calendar

object VolunteerActivityManager {
    private const val ACTIVE_THRESHOLD_YEARS = 1

    /**
     * Active means a shift in the last year. The stored [Volunteer.isActive] flag is not used:
     * new volunteers default to `isActive = true` even when they have never worked.
     */
    fun isVolunteerActive(volunteer: Volunteer, volunteerJobs: List<Job>? = null): Boolean {
        val lastShiftDate = effectiveLastShiftDate(volunteer, volunteerJobs) ?: return false
        val oneYearAgo = Calendar.getInstance().apply {
            add(Calendar.YEAR, -ACTIVE_THRESHOLD_YEARS)
        }.timeInMillis
        return lastShiftDate >= oneYearAgo
    }

    fun effectiveLastShiftDate(volunteer: Volunteer, volunteerJobs: List<Job>? = null): Long? {
        if (volunteerJobs != null) {
            val fromJobs = volunteerJobs.maxOfOrNull { job ->
                if (job.volunteerId == volunteer.id) job.date else Long.MIN_VALUE
            }?.takeIf { it != Long.MIN_VALUE && it > 0L }
            if (fromJobs != null) return fromJobs
        }
        return volunteer.lastShiftDate
    }

    fun getDaysSinceLastShift(volunteer: Volunteer, volunteerJobs: List<Job>? = null): Long? {
        val lastShiftDate = effectiveLastShiftDate(volunteer, volunteerJobs) ?: return null
        val currentTime = Calendar.getInstance().timeInMillis
        return (currentTime - lastShiftDate) / (1000 * 60 * 60 * 24)
    }

    fun getDaysSinceLastActivity(volunteer: Volunteer, volunteerJobs: List<Job>? = null): Long? {
        val daysSinceLastShift = getDaysSinceLastShift(volunteer, volunteerJobs)
        if (daysSinceLastShift != null) return daysSinceLastShift
        val lastModified = volunteer.lastModified
        if (lastModified > 0) {
            val currentTime = Calendar.getInstance().timeInMillis
            return (currentTime - lastModified) / (1000 * 60 * 60 * 24)
        }
        return null
    }

    fun calculateActivityFromJobs(volunteer: Volunteer, allJobs: List<Job>): Volunteer {
        val volunteerJobs = allJobs.filter { it.volunteerId == volunteer.id }
        if (volunteerJobs.isEmpty()) {
            return volunteer.copy(lastShiftDate = null, isActive = false)
        }
        val mostRecentJobDate = volunteerJobs.maxOfOrNull { it.date } ?: 0L
        val isActive = isVolunteerActive(volunteer.copy(lastShiftDate = mostRecentJobDate))
        return volunteer.copy(lastShiftDate = mostRecentJobDate, isActive = isActive)
    }

    fun calculateActivityFromJobsMap(volunteer: Volunteer, jobsByVolunteerId: Map<String, List<Job>>): Volunteer {
        val volunteerJobs = jobsByVolunteerId[volunteer.id] ?: emptyList()
        if (volunteerJobs.isEmpty()) {
            return volunteer.copy(lastShiftDate = null, isActive = false)
        }
        val mostRecentJobDate = volunteerJobs.maxOfOrNull { it.date } ?: 0L
        val isActive = isVolunteerActive(volunteer.copy(lastShiftDate = mostRecentJobDate))
        return volunteer.copy(lastShiftDate = mostRecentJobDate, isActive = isActive)
    }

    fun groupJobsByVolunteerId(allJobs: List<Job>): Map<String, List<Job>> {
        return allJobs.groupBy { it.volunteerId }
    }

    fun updateVolunteerActivityFromJobs(volunteers: List<Volunteer>, allJobs: List<Job>): List<Volunteer> {
        val jobsByVolunteerId = groupJobsByVolunteerId(allJobs)
        return volunteers.map { volunteer ->
            calculateActivityFromJobsMap(volunteer, jobsByVolunteerId)
        }
    }

    fun getActivityStatusText(volunteer: Volunteer, volunteerJobs: List<Job>? = null): String {
        val daysSince = getDaysSinceLastShift(volunteer, volunteerJobs) ?: return "Never worked"
        return when {
            daysSince == 0L -> "Active today"
            daysSince < 30 -> "Active ${daysSince}d ago"
            daysSince < 365 -> "Active ${daysSince / 30}mo ago"
            else -> "Inactive ${daysSince / 365}y ago"
        }
    }
}
