package com.example.nova.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversation_turns")
data class ConversationTurnEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val role: String, // "USER" or "ASSISTANT"
    val messageText: String,
    val toolCalled: String = "",
    val verificationStatus: String = "",
    val verificationDetail: String = "",
    val providerUsed: String = "",
    val timestampMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "memory_facts")
data class MemoryFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val factKey: String,
    val factValue: String,
    val category: String, // "PREFERENCE", "CONTACT_ALIAS", "APP_HINT", "USER_NOTE"
    val createdAtMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "task_audit_logs")
data class TaskAuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val userCommand: String,
    val toolName: String,
    val executionChannel: String,
    val targetPackage: String,
    val verificationOutcome: String,
    val verificationDetail: String,
    val elapsedMs: Long,
    val timestampMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "scheduled_reminders")
data class ScheduledReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val triggerAtMs: Long,
    val scheduledViaSystemAlarm: Boolean,
    val isCompleted: Boolean = false,
    val createdAtMs: Long = System.currentTimeMillis()
)
