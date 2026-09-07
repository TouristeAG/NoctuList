package com.eventmanager.app.ui.components

import com.eventmanager.app.platform.LocalPlatformContext
import com.eventmanager.app.resources.Res
import com.eventmanager.app.resources.*
import org.jetbrains.compose.resources.stringResource

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eventmanager.app.data.models.Job
import com.eventmanager.app.data.models.Volunteer
import com.eventmanager.app.data.utils.VolunteerActivityManager
import com.eventmanager.app.ui.utils.*

@Composable
fun CleanupInactiveVolunteersDialog(
    volunteers: List<Volunteer>,
    jobs: List<Job> = emptyList(),
    onConfirm: (Int) -> Unit, // Int represents years of inactivity
    onDismiss: () -> Unit
) {
    val platformContext = LocalPlatformContext.current
    var selectedYears by remember { mutableStateOf(4) } // Default to 4 years
    var showPreview by remember { mutableStateOf(false) }
    val jobsByVolunteer = remember(jobs) {
        VolunteerActivityManager.groupJobsByVolunteerId(jobs)
    }

    // Calculate volunteers that would be deleted based on selected years
    // For volunteers who have worked: check days since last shift (from jobs when needed)
    // For volunteers who never worked: check days since last profile modification
    val volunteersToDelete = remember(selectedYears, volunteers, jobsByVolunteer) {
        volunteers.filter { volunteer ->
            val daysSinceLastActivity = VolunteerActivityManager.getDaysSinceLastActivity(
                volunteer,
                jobsByVolunteer[volunteer.id],
            )
            daysSinceLastActivity != null && daysSinceLastActivity >= (selectedYears * 365L)
        }
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
                Text(stringResource(Res.string.cleanup_inactive_volunteers))
            }
        },
        text = {
            Column {
                Text(
                    text = stringResource(Res.string.cleanup_choose_duration),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Years selection
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = stringResource(Res.string.cleanup_inactive_for),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Slider(
                        value = selectedYears.toFloat(),
                        onValueChange = { selectedYears = it.toInt() },
                        valueRange = 1f..10f,
                        steps = 8, // 1, 2, 3, 4, 5, 6, 7, 8, 9, 10 years
                        modifier = Modifier.weight(1f)
                    )
                    
                    Text(
                        text = stringResource(Res.string.cleanup_years_value, selectedYears),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Preview button
                Button(
                    onClick = { showPreview = !showPreview },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (showPreview) {
                            stringResource(Res.string.cleanup_hide_preview)
                        } else {
                            stringResource(Res.string.cleanup_preview_to_delete, volunteersToDelete.size
                            )
                        }
                    )
                }
                
                // Preview list
                if (showPreview && volunteersToDelete.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp)
                        ) {
                            Text(
                                text = stringResource(Res.string.cleanup_volunteers_to_be_deleted),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Bold
                            )
                            
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 200.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                items(
                                    items = volunteersToDelete,
                                    key = { volunteer -> volunteer.id }
                                ) { volunteer ->
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = volunteer.name,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                        Spacer(modifier = Modifier.weight(1f))
                                        Text(
                                            text = VolunteerActivityManager.getActivityStatusText(
                                                volunteer,
                                                jobsByVolunteer[volunteer.id],
                                            ),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onErrorContainer
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (showPreview && volunteersToDelete.isEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Text(
                            text = stringResource(Res.string.cleanup_no_volunteers_found, selectedYears
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(12.dp)
                        )
                    }
                }
                
                Spacer(modifier = Modifier.height(16.dp))
                
                // Warning message
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            text = stringResource(Res.string.warning),
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            fontWeight = FontWeight.Bold
                        )
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        
                        Text(
                            text = stringResource(Res.string.cleanup_warning, volunteersToDelete.size,
                                if (volunteersToDelete.size != 1) "s" else ""
                            ),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedYears) },
                enabled = volunteersToDelete.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text(
                    stringResource(Res.string.delete_volunteers, volunteersToDelete.size,
                        if (volunteersToDelete.size != 1) "s" else ""
                    )
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Res.string.cancel))
            }
        }
    )
}
