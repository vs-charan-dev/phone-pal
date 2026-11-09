package com.phonepal.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Which version of an item is in the Gemini File Search store. Kept in its own table so the
 * normal item updates (which copy whole Item objects around) can never clobber it.
 *
 * [indexedVersion] is the item's updatedAt at the time it was uploaded: an item edited after that
 * (re-summarized, note changed) is uploaded again. [docName] is empty when the item was skipped.
 */
@Entity(tableName = "rag_docs")
data class RagDoc(
    @PrimaryKey val itemId: Long,
    val docName: String,
    val indexedVersion: Long,
)

@Dao
interface RagDao {
    /** Finished items that were never indexed, or changed since. Newest first. */
    @Query(
        """
        SELECT items.* FROM items LEFT JOIN rag_docs ON rag_docs.itemId = items.id
        WHERE items.status = 'DONE'
          AND (rag_docs.itemId IS NULL OR rag_docs.indexedVersion < items.updatedAt)
        ORDER BY items.createdAt DESC LIMIT :limit
        """
    )
    suspend fun dirty(limit: Int): List<Item>

    @Query("SELECT * FROM rag_docs WHERE itemId = :itemId")
    suspend fun get(itemId: Long): RagDoc?

    /** Documents whose item was deleted from the library. */
    @Query("SELECT * FROM rag_docs WHERE itemId NOT IN (SELECT id FROM items)")
    suspend fun orphans(): List<RagDoc>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(doc: RagDoc)

    @Query("DELETE FROM rag_docs WHERE itemId = :itemId")
    suspend fun delete(itemId: Long)

    @Query("DELETE FROM rag_docs")
    suspend fun clear()

    @Query("SELECT COUNT(*) FROM items WHERE status = 'DONE'")
    fun doneCount(): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM rag_docs JOIN items ON items.id = rag_docs.itemId
        WHERE rag_docs.docName != '' AND rag_docs.indexedVersion >= items.updatedAt
        """
    )
    fun indexedCount(): Flow<Int>
}
