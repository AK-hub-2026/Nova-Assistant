package com.example.nova.memory

import kotlinx.coroutines.flow.Flow

/**
 * Single repository abstracting Room database access for Nova.
 * Zero mock data is pre-populated; all streams reflect genuine user history and actions.
 */
class NovaRepository(private val database: NovaDatabase) {

    val conversationTurns: Flow<List<ConversationTurnEntity>> =
        database.conversationDao().observeAllTurns()

    val memoryFacts: Flow<List<MemoryFactEntity>> =
        database.memoryFactDao().observeAllFacts()

    val taskAuditLogs: Flow<List<TaskAuditLogEntity>> =
        database.taskAuditLogDao().observeAllAuditLogs()

    val scheduledReminders: Flow<List<ScheduledReminderEntity>> =
        database.scheduledReminderDao().observeAllReminders()

    suspend fun getRecentConversationTurns(limit: Int = 8): List<ConversationTurnEntity> =
        database.conversationDao().getRecentTurns(limit).reversed()

    suspend fun getRecentMemoryFacts(limit: Int = 20): List<MemoryFactEntity> =
        database.memoryFactDao().getRecentFacts(limit)

    suspend fun recordUserTurn(text: String): Long =
        database.conversationDao().insertTurn(
            ConversationTurnEntity(
                role = "USER",
                messageText = text
            )
        )

    suspend fun recordAssistantTurn(
        text: String,
        toolCalled: String = "",
        verificationStatus: String = "",
        verificationDetail: String = "",
        providerUsed: String = ""
    ): Long = database.conversationDao().insertTurn(
        ConversationTurnEntity(
            role = "ASSISTANT",
            messageText = text,
            toolCalled = toolCalled,
            verificationStatus = verificationStatus,
            verificationDetail = verificationDetail,
            providerUsed = providerUsed
        )
    )

    suspend fun clearConversationHistory() =
        database.conversationDao().clearAllTurns()

    suspend fun saveMemoryFact(key: String, value: String, category: String = "USER_NOTE"): Long =
        database.memoryFactDao().insertFact(
            MemoryFactEntity(
                factKey = key.trim(),
                factValue = value.trim(),
                category = category
            )
        )

    suspend fun deleteMemoryFact(id: Long) =
        database.memoryFactDao().deleteFactById(id)

    suspend fun clearAllMemoryFacts() =
        database.memoryFactDao().clearAllFacts()

    suspend fun recordTaskAuditLog(
        userCommand: String,
        toolName: String,
        executionChannel: String,
        targetPackage: String,
        verificationOutcome: String,
        verificationDetail: String,
        elapsedMs: Long
    ): Long = database.taskAuditLogDao().insertAuditLog(
        TaskAuditLogEntity(
            userCommand = userCommand,
            toolName = toolName,
            executionChannel = executionChannel,
            targetPackage = targetPackage,
            verificationOutcome = verificationOutcome,
            verificationDetail = verificationDetail,
            elapsedMs = elapsedMs
        )
    )

    suspend fun clearTaskAuditLogs() =
        database.taskAuditLogDao().clearAllAuditLogs()

    suspend fun insertReminder(
        title: String,
        triggerAtMs: Long,
        scheduledViaSystemAlarm: Boolean
    ): Long = database.scheduledReminderDao().insertReminder(
        ScheduledReminderEntity(
            title = title,
            triggerAtMs = triggerAtMs,
            scheduledViaSystemAlarm = scheduledViaSystemAlarm
        )
    )

    suspend fun deleteReminder(id: Long) =
        database.scheduledReminderDao().deleteReminderById(id)
}
