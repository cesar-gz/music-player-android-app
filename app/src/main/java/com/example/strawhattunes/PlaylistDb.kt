package com.example.strawhattunes

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val playlistId: Long = 0,
    val name: String
)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "mediaStoreId"],
    indices = [Index(value = ["mediaStoreId"])]
)
data class PlaylistSongEntity(
    val playlistId: Long,
    val mediaStoreId: Long,
    val title: String,
    val artist: String?
)

@Dao
interface PlaylistDao {

    @Query("SELECT * FROM playlists ORDER BY name ASC")
    suspend fun getPlaylists(): List<PlaylistEntity>

    @Insert
    suspend fun insertPlaylist(p: PlaylistEntity): Long

    @Query("DELETE FROM playlists WHERE playlistId = :playlistId")
    suspend fun deletePlaylist(playlistId: Long)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addSongToPlaylist(item: PlaylistSongEntity)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND mediaStoreId = :mediaStoreId")
    suspend fun removeSongFromPlaylist(playlistId: Long, mediaStoreId: Long)

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY title ASC")
    suspend fun getSongsInPlaylist(playlistId: Long): List<PlaylistSongEntity>
}

@Database(
    entities = [PlaylistEntity::class, PlaylistSongEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AppDb : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile private var INSTANCE: AppDb? = null

        fun get(context: Context): AppDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDb::class.java,
                    "strawhat_tunes.db"
                ).build().also { INSTANCE = it }
            }
    }
}
