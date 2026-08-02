package com.tvlive.app.data.db

import androidx.room.*
import com.tvlive.app.data.model.Channel
import com.tvlive.app.data.model.PlayHistory
import com.tvlive.app.data.model.Source
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelDao {
    @Query("SELECT * FROM channels ORDER BY `group`, channelNumber, name")
    fun getAllChannels(): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE `group` = :group ORDER BY channelNumber, name")
    fun getChannelsByGroup(group: String): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE favorite = 1 ORDER BY name")
    fun getFavorites(): Flow<List<Channel>>

    @Query("SELECT * FROM channels WHERE name LIKE '%' || :keyword || '%' ORDER BY name")
    fun search(keyword: String): Flow<List<Channel>>

    @Query("SELECT DISTINCT `group` FROM channels ORDER BY `group`")
    fun getGroups(): Flow<List<String>>

    @Query("SELECT COUNT(*) FROM channels")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<Channel>)

    @Query("UPDATE channels SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: Long, fav: Boolean)

    @Query("DELETE FROM channels WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: Long)

    @Query("DELETE FROM channels")
    suspend fun deleteAll()
}

@Dao
interface SourceDao {
    @Query("SELECT * FROM sources ORDER BY isDefault DESC, name")
    fun getAllSources(): Flow<List<Source>>

    @Query("SELECT * FROM sources WHERE enabled = 1 ORDER BY isDefault DESC, name")
    suspend fun getEnabledSources(): List<Source>

    @Query("SELECT * FROM sources WHERE id = :id")
    suspend fun getById(id: Long): Source?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: Source): Long

    @Update
    suspend fun update(source: Source)

    @Delete
    suspend fun delete(source: Source)

    @Query("UPDATE sources SET isDefault = 0")
    suspend fun clearDefault()

    @Query("UPDATE sources SET isDefault = 1 WHERE id = :id")
    suspend fun setDefault(id: Long)

    @Query("SELECT COUNT(*) FROM sources")
    suspend fun count(): Int
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY watchTime DESC LIMIT 50")
    fun getAll(): Flow<List<PlayHistory>>

    @Insert
    suspend fun insert(history: PlayHistory)

    @Query("DELETE FROM history WHERE channelId = :channelId")
    suspend fun deleteByChannel(channelId: Long)

    @Query("DELETE FROM history")
    suspend fun deleteAll()

    @Query("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY watchTime DESC LIMIT 50)")
    suspend fun trimOld()
}
