package com.example.nova.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.nova.memory.MemoryFactEntity
import com.example.nova.memory.ScheduledReminderEntity
import com.example.nova.memory.TaskAuditLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MemoryAndLogsScreen(
    memoryFacts: List<MemoryFactEntity>,
    auditLogs: List<TaskAuditLogEntity>,
    reminders: List<ScheduledReminderEntity>,
    onAddMemoryFact: (String, String, String) -> Unit,
    onDeleteMemoryFact: (Long) -> Unit,
    onDeleteReminder: (Long) -> Unit,
    onClearAuditLogs: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedSection by rememberSaveable { mutableIntStateOf(0) }
    var newFactKey by rememberSaveable { mutableStateOf("") }
    var newFactValue by rememberSaveable { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = selectedSection == 0,
                onClick = { selectedSection = 0 },
                label = { Text("Memory (${memoryFacts.size})") },
                leadingIcon = { Icon(Icons.Default.BookmarkBorder, contentDescription = null) },
                modifier = Modifier.testTag("memory_subtab_facts")
            )
            FilterChip(
                selected = selectedSection == 1,
                onClick = { selectedSection = 1 },
                label = { Text("Audit Log (${auditLogs.size})") },
                leadingIcon = { Icon(Icons.Default.History, contentDescription = null) },
                modifier = Modifier.testTag("memory_subtab_audit")
            )
            FilterChip(
                selected = selectedSection == 2,
                onClick = { selectedSection = 2 },
                label = { Text("Reminders (${reminders.size})") },
                leadingIcon = { Icon(Icons.Default.Alarm, contentDescription = null) },
                modifier = Modifier.testTag("memory_subtab_reminders")
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        when (selectedSection) {
            0 -> {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Add Persistent Memory Fact (Room DB)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = newFactKey,
                                onValueChange = { newFactKey = it },
                                label = { Text("Topic / Key") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(0.4f)
                                    .testTag("memory_key_input")
                            )
                            OutlinedTextField(
                                value = newFactValue,
                                onValueChange = { newFactValue = it },
                                label = { Text("Value / Preference") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(0.6f)
                                    .testTag("memory_value_input")
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Button(
                            onClick = {
                                if (newFactKey.isNotBlank() && newFactValue.isNotBlank()) {
                                    onAddMemoryFact(newFactKey, newFactValue, "PREFERENCE")
                                    newFactKey = ""
                                    newFactValue = ""
                                }
                            },
                            modifier = Modifier
                                .align(Alignment.End)
                                .testTag("save_memory_fact_button")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Save Fact")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (memoryFacts.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "0 Stored Memory Facts",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Nova starts with an empty database (zero mock records). Add a preference above or say 'remember that my preferred music app is YouTube Music'.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("memory_facts_list"),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(memoryFacts, key = { it.id }) { fact ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${fact.factKey}: ${fact.factValue}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "Category: ${fact.category}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(
                                        onClick = { onDeleteMemoryFact(fact.id) },
                                        modifier = Modifier.testTag("delete_fact_${fact.id}")
                                    ) {
                                        Icon(
                                            Icons.Default.DeleteOutline,
                                            contentDescription = "Delete memory fact"
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            1 -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Verified Action Execution Audit Trail",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    if (auditLogs.isNotEmpty()) {
                        OutlinedButton(
                            onClick = onClearAuditLogs,
                            modifier = Modifier.testTag("clear_audit_logs_button")
                        ) {
                            Text("Clear Logs")
                        }
                    }
                }
                Spacer(modifier = Modifier.height(6.dp))
                if (auditLogs.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "0 Executed Actions Logged",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Every tool execution and real-state verification outcome is recorded here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("audit_logs_list"),
                        contentPadding = PaddingValues(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(auditLogs, key = { it.id }) { log ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(modifier = Modifier.padding(12.dp)) {
                                    Text(
                                        text = "${log.toolName} • ${log.verificationOutcome} (${log.elapsedMs}ms)",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "Command: \"${log.userCommand}\" | Channel: ${log.executionChannel} | Time: ${fmt.format(Date(log.timestampMs))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = log.verificationDetail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }

            2 -> {
                if (reminders.isEmpty()) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                text = "0 Scheduled Reminders",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = "Schedule a reminder by saying 'remind me in 15 minutes to check oven' or 'set alarm for 8:00'.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("scheduled_reminders_list"),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(reminders, key = { it.id }) { item ->
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = "Trigger: ${fmt.format(Date(item.triggerAtMs))} • System Alarm: ${item.scheduledViaSystemAlarm}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = { onDeleteReminder(item.id) }) {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = "Delete reminder")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
