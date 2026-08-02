package com.tvlive.app.data.db

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import android.content.Context
import com.tvlive.app.data.model.Channel
import com.tvlive.app.data.model.PlayHistory
import com.tvlive.app.data.model.Source

@Database(
    entities = [Channel::class, Source::class, PlayHistory::class],
    version = 2,  // v2: 清除旧版GitHub源, 改用本地源+国内直连源
    exportSchema = false
)
abstract class TvLiveDatabase : RoomDatabase() {

    abstract fun channelDao(): ChannelDao
    abstract fun sourceDao(): SourceDao
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var INSTANCE: TvLiveDatabase? = null

        fun get(context: Context): TvLiveDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    TvLiveDatabase::class.java,
                    "tvlive.db"
                ).fallbackToDestructiveMigration().build()
                INSTANCE = instance
                instance
            }
        }
    }
}
