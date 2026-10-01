package com.mdedit.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import com.mdedit.data.local.dao.DocumentDao
import com.mdedit.data.local.entity.DocumentEntity

@Database(entities = [DocumentEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun documentDao(): DocumentDao
}
