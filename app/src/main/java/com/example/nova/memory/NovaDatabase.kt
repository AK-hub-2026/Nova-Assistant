package com.example.nova.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ConversationTurnEntity::class,
        MemoryFactEntity::class,
        TaskAuditLogEntity::class,
        ScheduledReminderEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class NovaDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun memoryFactDao(): MemoryFactDao
    abstract fun taskAuditLogDao(): TaskAuditLogDao
    abstract fun scheduledReminderDao(): ScheduledReminderDao

    companion object {
        @Volatile
        private var INSTANCE: NovaDatabase? = null

        fun getInstance(context: Context): NovaDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NovaDatabase::class.java,
                    "nova_assistant.db"
                ).fallbackToDestructiveMigration(dropAllTables = true).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
