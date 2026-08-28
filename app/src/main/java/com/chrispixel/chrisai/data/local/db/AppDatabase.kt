package com.chrispixel.chrisai.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * Local database for conversations, messages and persistent memories.
 *
 * Schema migrations must add a [androidx.room.migration.Migration] from the
 * previous version here; the updater keeps app data in place between versions,
 * so migrations (never destructive resets) are the only supported upgrade path.
 */
@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        MemoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao
}