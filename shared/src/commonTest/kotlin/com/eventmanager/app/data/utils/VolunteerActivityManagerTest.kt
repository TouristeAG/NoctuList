package com.eventmanager.app.data.utils

import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.JobType
import com.eventmanager.app.data.models.ShiftTime
import com.eventmanager.app.data.models.Volunteer
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class VolunteerActivityManagerTest {

    private fun volunteer(
        isActiveFlag: Boolean = true,
        lastShiftDate: Long? = null,
    ) = Volunteer(
        id = "volunteer-id-xxxxxxxxx",
        name = "Ada",
        lastNameAbbreviation = "L",
        email = "ada@example.com",
        phoneNumber = "",
        isActive = isActiveFlag,
        lastShiftDate = lastShiftDate,
    )

    @Test
    fun neverWorkedIsInactiveEvenIfStoredFlagIsTrue() {
        val volunteer = volunteer(isActiveFlag = true, lastShiftDate = null)
        assertFalse(VolunteerActivityManager.isVolunteerActive(volunteer))
        assertFalse(VolunteerActivityManager.isVolunteerActive(volunteer, emptyList()))
    }

    @Test
    fun recentJobMakesVolunteerActive() {
        val now = System.currentTimeMillis()
        val volunteer = volunteer(isActiveFlag = false, lastShiftDate = null)
        val job = Job(
            volunteerId = volunteer.id,
            jobType = JobType.OTHER,
            jobTypeName = "Bar",
            venueName = "Main",
            date = now,
            shiftTime = ShiftTime.BEFORE_MIDNIGHT,
        )
        assertTrue(VolunteerActivityManager.isVolunteerActive(volunteer, listOf(job)))
        assertFalse(VolunteerActivityManager.isVolunteerActive(volunteer))
    }

    @Test
    fun daysSinceLastActivityUsesJobsWhenLastShiftDateMissing() {
        val now = System.currentTimeMillis()
        val volunteer = volunteer(isActiveFlag = false, lastShiftDate = null)
        val job = Job(
            volunteerId = volunteer.id,
            jobType = JobType.OTHER,
            jobTypeName = "Bar",
            venueName = "Main",
            date = now - 2L * 24 * 60 * 60 * 1000,
            shiftTime = ShiftTime.BEFORE_MIDNIGHT,
        )
        val days = VolunteerActivityManager.getDaysSinceLastActivity(volunteer, listOf(job))
        assertTrue(days != null && days in 1L..3L)
    }
}
