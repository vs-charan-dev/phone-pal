package com.phonepal.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ItemDao {
    @Insert
    suspend fun insert(item: Item): Long

    @Update
    suspend fun update(item: Item)

    @Query("SELECT * FROM items WHERE id = :id")
    suspend fun get(id: Long): Item?

    @Query("SELECT * FROM items WHERE id = :id")
    fun observe(id: Long): Flow<Item?>

    @Query("SELECT * FROM items WHERE url = :url LIMIT 1")
    suspend fun findByUrl(url: String): Item?

    @Query(
        """
        SELECT * FROM items
        WHERE (:source IS NULL OR source = :source)
          AND (:q = '' OR title LIKE '%' || :q || '%' OR author LIKE '%' || :q || '%'
               OR summary LIKE '%' || :q || '%' OR tags LIKE '%' || :q || '%'
               OR contentIdeas LIKE '%' || :q || '%' OR keyPoints LIKE '%' || :q || '%'
               OR hooks LIKE '%' || :q || '%' OR myNote LIKE '%' || :q || '%'
               OR contentText LIKE '%' || :q || '%')
        ORDER BY createdAt DESC
        """
    )
    fun search(q: String, source: Source?): Flow<List<Item>>

    @Query("SELECT * FROM items ORDER BY createdAt DESC")
    suspend fun all(): List<Item>

    @Query("SELECT * FROM items WHERE createdAt >= :since ORDER BY createdAt DESC")
    suspend fun since(since: Long): List<Item>

    @Query("DELETE FROM items WHERE id = :id")
    suspend fun delete(id: Long)
}
