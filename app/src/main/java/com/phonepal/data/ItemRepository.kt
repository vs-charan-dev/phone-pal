package com.phonepal.data

class ItemRepository(private val dao: ItemDao) {
    fun search(q: String, source: Source?) = dao.search(q.trim(), source)
    fun observe(id: Long) = dao.observe(id)
    suspend fun get(id: Long) = dao.get(id)
    suspend fun update(item: Item) = dao.update(item.copy(updatedAt = System.currentTimeMillis()))
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun all() = dao.all()

    /** Text captured from the screen (bubble tap or an auto-saved like). Already has content, so no fetch step. */
    suspend fun addCaptured(source: Source, title: String?, author: String?, text: String, note: String): Long =
        dao.insert(
            Item(
                url = null, source = source, sharedText = "", title = title, author = author,
                contentText = text, contentNote = note,
            )
        )

    suspend fun since(since: Long) = dao.since(since)

    /** Returns (id, isNew). If this URL was already saved, returns the existing item's id. */
    suspend fun addFromShare(sharedText: String): Pair<Long, Boolean> {
        val link = LinkParser.parse(sharedText)
        if (link.url != null) {
            dao.findByUrl(link.url)?.let { return it.id to false }
        }
        val id = dao.insert(
            Item(
                url = link.url,
                source = link.source,
                sharedText = sharedText.trim(),
                title = if (link.source == Source.NOTE) sharedText.trim().lineSequence().first().take(80) else null,
                contentText = if (link.source == Source.NOTE) sharedText.trim() else null,
            )
        )
        return id to true
    }
}
