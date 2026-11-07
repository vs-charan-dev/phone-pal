package com.phonepal.tracking

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

/** One continuous stretch of watching/listening to one title in one media app. */
@Entity(tableName = "watch_sessions", indices = [Index(value = ["startedAt"])])
data class WatchSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val title: String,
    val artist: String?,          // YouTube channel / music artist
    val durationMs: Long?,
    val startedAt: Long,
    val lastActiveAt: Long,
    val playedMs: Long = 0,
)

/** A post that was fully on screen in a feed app (X, Instagram, Reddit, LinkedIn). */
@Entity(tableName = "seen_posts", indices = [Index(value = ["hash"], unique = true), Index(value = ["seenAt"])])
data class SeenPost(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pkg: String,
    val hash: String,
    val text: String,
    val seenAt: Long,
    val liked: Boolean = false,
    val itemId: Long? = null,
)

data class PkgCount(val pkg: String, val n: Int)

@Dao
interface TrackingDao {
    @Insert
    suspend fun insertSession(s: WatchSession): Long

    @Query("SELECT * FROM watch_sessions WHERE pkg = :pkg AND title = :title AND lastActiveAt >= :since ORDER BY lastActiveAt DESC LIMIT 1")
    suspend fun recentSession(pkg: String, title: String, since: Long): WatchSession?

    @Query("UPDATE watch_sessions SET playedMs = playedMs + :delta, lastActiveAt = :now WHERE id = :id")
    suspend fun addPlayed(id: Long, delta: Long, now: Long)

    @Query("UPDATE watch_sessions SET artist = :artist WHERE id = :id AND artist IS NULL")
    suspend fun setArtist(id: Long, artist: String)

    @Query("SELECT * FROM watch_sessions WHERE lastActiveAt >= :since AND playedMs >= 15000 ORDER BY startedAt DESC")
    suspend fun watchedSince(since: Long): List<WatchSession>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPost(p: SeenPost): Long

    @Query("SELECT * FROM seen_posts WHERE hash = :hash")
    suspend fun findPost(hash: String): SeenPost?

    @Query("UPDATE seen_posts SET liked = 1, itemId = :itemId WHERE hash = :hash")
    suspend fun markLiked(hash: String, itemId: Long)

    @Query("SELECT pkg, COUNT(*) AS n FROM seen_posts WHERE seenAt >= :since GROUP BY pkg ORDER BY n DESC")
    suspend fun postCountsSince(since: Long): List<PkgCount>

    @Query("SELECT COUNT(*) FROM seen_posts WHERE seenAt >= :since AND liked = 1")
    suspend fun likedSince(since: Long): Int
}

@Database(entities = [WatchSession::class, SeenPost::class], version = 1, exportSchema = false)
abstract class TrackingDatabase : RoomDatabase() {
    abstract fun dao(): TrackingDao

    companion object {
        @Volatile private var instance: TrackingDatabase? = null

        fun get(context: Context): TrackingDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, TrackingDatabase::class.java, "tracking.db")
                .build().also { instance = it }
        }
    }
}
