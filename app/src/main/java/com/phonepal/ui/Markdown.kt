package com.phonepal.ui

import com.phonepal.data.Item
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Obsidian/Notion-friendly markdown export of one item, or of the whole library. */
object Markdown {
    private val date = SimpleDateFormat("yyyy-MM-dd", Locale.US)

    fun of(item: Item, includeSource: Boolean = false): String = buildString {
        append("## ").append(item.title ?: item.url ?: "Untitled").append("\n\n")
        val meta = listOfNotNull(
            item.source.name.lowercase(),
            item.author,
            item.category,
            date.format(Date(item.createdAt)),
        ).joinToString(" · ")
        append("*").append(meta).append("*\n\n")
        item.url?.let { append(it).append("\n\n") }
        if (item.tagList.isNotEmpty()) append(item.tagList.joinToString(" ") { "#" + it.replace(' ', '-') }).append("\n\n")
        item.summary?.let { append(it).append("\n\n") }
        section("Key points", Item.lines(item.keyPoints))
        section("Content ideas", Item.lines(item.contentIdeas))
        section("Hooks", Item.lines(item.hooks))
        item.myNote?.takeIf { it.isNotBlank() }?.let { append("**My note:** ").append(it).append("\n\n") }
        if (includeSource) item.contentText?.let { append("<details><summary>Source text</summary>\n\n").append(it).append("\n\n</details>\n\n") }
    }

    fun library(items: List<Item>): String =
        "# PhonePal library\n\n" + items.joinToString("\n---\n\n") { of(it) }

    private fun StringBuilder.section(title: String, lines: List<String>) {
        if (lines.isEmpty()) return
        append("**").append(title).append("**\n")
        lines.forEach { append("- ").append(it).append('\n') }
        append('\n')
    }
}
