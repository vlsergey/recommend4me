package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM_TEXT
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** THE TEXTS THE SITE GIVES ITS ITEMS — by their keys, as the source database holds them. */
interface ItemTexts {
    /** The texts of the item written where changed, a blank one removed; the item told changed when any was. */
    fun setTexts(itemId: String, texts: Map<String, String?>)

    /** The texts of one item: key to text. */
    fun texts(itemId: String): Map<String, String>

    /** The texts of the items [ids] — of the [keys] alone when given: item to key to text. */
    fun textsOf(ids: Collection<String>, keys: Collection<String>? = null): Map<String, Map<String, String>>

    /** Every text of the [keys] of every item, streamed item by item. */
    fun forEachTexts(keys: Collection<String>, action: (itemId: String, texts: Map<String, String>) -> Unit)
}

/** The [ItemTexts] of the source database [db]: [changed] told of every item whose texts were written. */
internal class ItemTextRepository(private val db: DSLContext, private val changed: (Collection<String>) -> Unit) :
    ItemTexts {

    override fun setTexts(itemId: String, texts: Map<String, String?>) {
        if (texts.isEmpty()) return
        val before = texts(itemId)
        var any = false
        texts.forEach { (key, value) ->
            val text = value?.trim()?.takeIf { it.isNotEmpty() }
            if (before[key] == text) return@forEach
            any = true
            if (text == null) {
                db.deleteFrom(ITEM_TEXT).where(ITEM_TEXT.ITEM_ID.eq(itemId), ITEM_TEXT.TEXT_KEY.eq(key)).execute()
            } else {
                db.insertInto(ITEM_TEXT)
                    .set(ITEM_TEXT.ITEM_ID, itemId)
                    .set(ITEM_TEXT.TEXT_KEY, key)
                    .set(ITEM_TEXT.CONTENT, text)
                    .onDuplicateKeyUpdate()
                    .set(ITEM_TEXT.CONTENT, text)
                    .execute()
            }
        }
        if (any) changed(listOf(itemId))
    }

    override fun texts(itemId: String): Map<String, String> =
        db.select(ITEM_TEXT.TEXT_KEY, ITEM_TEXT.CONTENT)
            .from(ITEM_TEXT)
            .where(ITEM_TEXT.ITEM_ID.eq(itemId))
            .fetch()
            .associate { it.value1()!! to it.value2()!! }

    override fun textsOf(ids: Collection<String>, keys: Collection<String>?): Map<String, Map<String, String>> {
        val out = HashMap<String, MutableMap<String, String>>()
        ids.distinct().chunked(ITEM_BATCH).forEach { chunk ->
            db.select(ITEM_TEXT.ITEM_ID, ITEM_TEXT.TEXT_KEY, ITEM_TEXT.CONTENT)
                .from(ITEM_TEXT)
                .where(ITEM_TEXT.ITEM_ID.`in`(chunk))
                .and(keys?.let { ITEM_TEXT.TEXT_KEY.`in`(it) } ?: DSL.noCondition())
                .fetch { out.getOrPut(it.value1()!!) { HashMap() }[it.value2()!!] = it.value3()!! }
        }
        return out
    }

    override fun forEachTexts(keys: Collection<String>, action: (itemId: String, texts: Map<String, String>) -> Unit) {
        var current: String? = null
        val texts = HashMap<String, String>()
        db.select(ITEM_TEXT.ITEM_ID, ITEM_TEXT.TEXT_KEY, ITEM_TEXT.CONTENT)
            .from(ITEM_TEXT)
            .where(ITEM_TEXT.TEXT_KEY.`in`(keys))
            .orderBy(ITEM_TEXT.ITEM_ID)
            .fetchSize(TEXTS_STREAMED)
            .fetchLazy()
            .use { cursor ->
                cursor.forEach {
                    if (it.value1() != current) {
                        current?.let { id -> action(id, texts.toMap()) }
                        texts.clear()
                        current = it.value1()
                    }
                    texts[it.value2()!!] = it.value3()!!
                }
            }
        current?.let { action(it, texts.toMap()) }
    }

    private companion object {
        /** The rows of texts streamed at a time — long ones, fewer than of the rest: memory, not meaning. */
        const val TEXTS_STREAMED = 500
    }
}
