package com.example.nova.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversation_turns ORDER BY timestampMs ASC")
    fun observeAllTurns(): Flow<List<ConversationTurnEntity>>

    @Query("SELECT * FROM conversation_turns ORDER BY timestampMs DESC LIMIT :limit")
    suspend fun getRecentTurns(limit: Int): List<ConversationTurnEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTurn(turn: ConversationTurnEntity): Long

    @Query("DELETE FROM conversation_turns")
    suspend fun clearAllTurns()
}

@Dao
interface MemoryFactDao {
    @Query("SELECT * FROM memory_facts ORDER BY createdAtMs DESC")
    fun observeAllFacts(): Flow<List<MemoryFactEntity>>

    @Query("SELECT * FROM memory_facts ORDER BY createdAtMs DESC LIMIT :limit")
    suspend fun getRecentFacts(limit: Int): List<MemoryFactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFact(fact: MemoryFactEntity): Long

    @Query("DELETE FROM memory_facts WHERE id = :id")
    suspend fun deleteFactById(id: Long)

    @Query("DELETE FROM memory_facts")
    suspend fun clearAllFacts()
}

@Dao
interface TaskAuditLogDao {
    @Query("SELECT * FROM task_audit_logs ORDER BY timestampMs DESC")
    fun observeAllAuditLogs(): Flow<List<TaskAuditLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAuditLog(log: TaskAuditLogEntity): Long

    @Query("DELETE FROM task_audit_logs")
    suspend fun clearAllAuditLogs()
}

@Dao
interface ScheduledReminderDao {
    @Query("SELECT * FROM scheduled_reminders ORDER BY triggerAtMs ASC")
    fun observeAllReminders(): Flow<List<ScheduledReminderEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReminder(reminder: ScheduledReminderEntity): Long

    @Query("UPDATE scheduled_reminders SET isCompleted = :completed WHERE id = :id")
    suspend fun setReminderCompleted(id: Long, completed: Boolean)

    @Query("DELETE FROM scheduled_reminders WHERE id = :id")
    suspend fun deleteReminderById(id: Long)
}

@Dao
interface VoiceSessionLogDao {
    @Query("SELECT * FROM voice_session_logs ORDER BY timestampMs DESC")
    fun observeAllVoiceLogs(): Flow<List<VoiceSessionLogEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoiceLog(log: VoiceSessionLogEntity): Long

    @Query("DELETE FROM voice_session_logs")
    suspend fun clearAllVoiceLogs()
}

