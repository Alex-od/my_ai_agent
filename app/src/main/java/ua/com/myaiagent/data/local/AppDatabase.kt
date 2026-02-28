package ua.com.myaiagent.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE conversations ADD COLUMN isActive INTEGER NOT NULL DEFAULT 0")
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE conversations ADD COLUMN summary TEXT DEFAULT NULL")
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS facts (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                conversationId INTEGER NOT NULL,
                fact TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
            )"""
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS index_facts_conversationId ON facts(conversationId)")

        database.execSQL(
            """CREATE TABLE IF NOT EXISTS branches (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                conversationId INTEGER NOT NULL,
                name TEXT NOT NULL,
                forkAtMessage INTEGER NOT NULL,
                snapshot TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
            )"""
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS index_branches_conversationId ON branches(conversationId)")
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Recreate facts table with key + value columns
        database.execSQL("DROP TABLE IF EXISTS facts")
        database.execSQL(
            """CREATE TABLE IF NOT EXISTS facts (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                conversationId INTEGER NOT NULL,
                key TEXT NOT NULL,
                value TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                FOREIGN KEY(conversationId) REFERENCES conversations(id) ON DELETE CASCADE
            )"""
        )
        database.execSQL("CREATE INDEX IF NOT EXISTS index_facts_conversationId ON facts(conversationId)")
    }
}

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        FactEntity::class,
        BranchEntity::class,
    ],
    version = 5,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}
