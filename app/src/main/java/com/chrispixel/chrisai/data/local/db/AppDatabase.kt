package com.chrispixel.chrisai.data.local.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

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
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun memoryDao(): MemoryDao

    companion object {
        /** v2: user-attached images (absolute path per message). Non-destructive. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE messages ADD COLUMN imagePath TEXT")
            }
        }

        /** v3: v1.0 session context kind per conversation. Non-destructive. */
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN kind TEXT NOT NULL DEFAULT 'general'")
            }
        }
    }
}