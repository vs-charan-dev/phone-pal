package com.phonepal.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** APP = text captured from another app's screen (Instagram, Reddit, LinkedIn…). */
enum class Source { YOUTUBE, X, WEB, APP, NOTE }

enum class Status { QUEUED, FETCHING, SUMMARIZING, DONE, FAILED }

/**
 * One thing you liked / saved. List fields (keyPoints, contentIdeas, hooks) are stored
 * newline-separated; tags are comma-separated.
 */
@Entity(tableName = "items", indices = [Index(value = ["url"])])
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String?,
    val source: Source,
    val sharedText: String,            // whatever the share sheet gave us
    val title: String? = null,
    val author: String? = null,        // channel / account
    val contentText: String? = null,   // transcript, tweet text or page text
    val contentNote: String? = null,   // e.g. "auto-generated captions (en)" or why there is no transcript
    val durationSec: Long? = null,
    val summary: String? = null,
    val keyPoints: String? = null,
    val contentIdeas: String? = null,
    val hooks: String? = null,
    val tags: String? = null,
    val category: String? = null,
    val myNote: String? = null,
    val status: Status = Status.QUEUED,
    val error: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
) {
    val tagList: List<String>
        get() = tags?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    companion object {
        fun lines(s: String?): List<String> =
            s?.lines()?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
    }
}
