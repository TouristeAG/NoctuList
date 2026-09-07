package com.eventmanager.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.utils.VolunteerActivityManager
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.active_volunteers_count
import com.eventmanager.app.resources.close
import com.eventmanager.app.resources.inactive_volunteers_count
import com.eventmanager.app.resources.inactive_volunteers_label
import com.eventmanager.app.resources.volunteer_activity_status
import org.jetbrains.compose.resources.stringResource

@Composable
fun ActiveVolunteersDialog(
    volunteers: List<Volunteer>,
    jobs: List<Job> = emptyList(),
    onDismiss: () -> Unit,
) {
    // Activity is derived from shifts (same as dashboard), not the stored isActive / lastShiftDate alone.
    val jobsByVolunteer = remember(jobs) {
        VolunteerActivityManager.groupJobsByVolunteerId(jobs)
    }
    val activeVolunteers = remember(volunteers, jobsByVolunteer) {
        volunteers.filter { VolunteerActivityManager.isVolunteerActive(it, jobsByVolunteer[it.id]) }
    }
    val inactiveVolunteers = remember(volunteers, jobsByVolunteer) {
        volunteers.filter { !VolunteerActivityManager.isVolunteerActive(it, jobsByVolunteer[it.id]) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(Res.string.volunteer_activity_status)) },
        text = {
            Column {
                Text(
                    text = stringResource(Res.string.active_volunteers_count, activeVolunteers.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(Res.string.inactive_volunteers_count, inactiveVolunteers.size),
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF9E9E9E),
                    fontWeight = FontWeight.Bold,
                )
                Spacer(Modifier.height(16.dp))
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(activeVolunteers, key = { it.id }) { volunteer ->
                        val volunteerJobs = jobsByVolunteer[volunteer.id]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF4CAF50),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text(volunteer.name, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    VolunteerActivityManager.getActivityStatusText(volunteer, volunteerJobs),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    if (inactiveVolunteers.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                stringResource(Res.string.inactive_volunteers_label),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                        items(inactiveVolunteers, key = { "inactive-${it.id}" }) { volunteer ->
                            val volunteerJobs = jobsByVolunteer[volunteer.id]
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.Cancel,
                                    contentDescription = null,
                                    tint = Color(0xFF9E9E9E),
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(volunteer.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        VolunteerActivityManager.getActivityStatusText(volunteer, volunteerJobs),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(Res.string.close))
            }
        },
    )
}
