package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.database.source.tables.references.CAPTURED_PAGE
import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM
import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM_VERSION
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.StoredItem
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL

/** The ids bound to one query at most: a thousand in a list of a condition, memory and H2's plan, not meaning. */
internal const val ITEM_BATCH = 1000

/** The rows streamed from the database at a time: memory, not meaning. */
internal const val STREAMED_ROWS = 5000

/** The longest key of a facet's value the tables of the facets hold (`VARCHAR(500)`). */
internal const val VALUE_KEY_LENGTH = 500

/**
 * The items of one source as its source database holds them: what every item has, its facets, numbers and texts, and
 * the dictionary of the facets' values — each held by its own part ([ItemFacets], [FacetDictionary], [ItemNumbers],
 * [ItemTexts]), this one by the items' heads. [changed] is told of every item whose searchable data was written.
 */
class ItemRepository
private constructor(
    private val db: DSLContext,
    private val changed: (Collection<String>) -> Unit,
    dictionary: FacetDictionary,
) :
    FacetDictionary by dictionary,
    ItemFacets by ItemFacetRepository(db, changed, dictionary),
    ItemNumbers by ItemNumberRepository(db),
    ItemTexts by ItemTextRepository(db, changed),
    ItemMeasures by ItemMeasureRepository(db) {

    constructor(
        db: DSLContext,
        changed: (Collection<String>) -> Unit,
    ) : this(db, changed, FacetDictionaryRepository(db))

    /**
     * Creates or updates an item; true when it is new or its version changed. The head and its version in one
     * transaction: a stop between leaves no head without its versions.
     */
    fun upsert(item: ItemHead, now: Instant): Boolean {
        val (changedVersion, retitled) =
            db.transactionResult { c ->
                val tx = DSL.using(c)
                val known =
                    tx.select(ITEM.VERSION, ITEM.TITLE, ITEM.URL, ITEM.UPDATED_AT)
                        .from(ITEM)
                        .where(ITEM.ITEM_ID.eq(item.id))
                        .fetchOne()
                val updatedAt = item.updatedAt ?: known?.value4() ?: now
                if (known == null) {
                    tx.insertInto(ITEM)
                        .set(ITEM.ITEM_ID, item.id)
                        .set(ITEM.URL, item.url)
                        .set(ITEM.TITLE, item.title)
                        .set(ITEM.VERSION, item.version)
                        .set(ITEM.UPDATED_AT, updatedAt)
                        .set(ITEM.FIRST_SEEN_AT, now)
                        .execute()
                } else if (
                    headChanged(known.value1(), known.value2(), known.value3(), known.value4(), item, updatedAt)
                ) {
                    tx.update(ITEM)
                        .set(ITEM.URL, item.url)
                        .set(ITEM.TITLE, item.title)
                        .set(ITEM.VERSION, item.version)
                        .set(ITEM.UPDATED_AT, updatedAt)
                        .where(ITEM.ITEM_ID.eq(item.id))
                        .execute()
                }
                val newVersion = known?.value1() != item.version
                if (newVersion) {
                    tx.insertInto(ITEM_VERSION)
                        .set(ITEM_VERSION.ITEM_ID, item.id)
                        .set(ITEM_VERSION.VERSION, item.version)
                        .set(ITEM_VERSION.UPDATED_AT, updatedAt)
                        .set(ITEM_VERSION.SEEN_AT, now)
                        .onDuplicateKeyIgnore()
                        .execute()
                }
                newVersion to (known == null || known.value2() != item.title)
            }
        // Told once it is kept: a reader of the change finds it
        if (retitled) changed(listOf(item.id))
        return changedVersion
    }

    fun find(itemId: String): StoredItem? = db.select(HEAD).from(ITEM).where(ITEM.ITEM_ID.eq(itemId)).fetchOne(::head)

    /** The items [ids], in one query per thousand. */
    fun heads(ids: Collection<String>): Map<String, StoredItem> {
        val out = HashMap<String, StoredItem>()
        ids.distinct().chunked(ITEM_BATCH).forEach { chunk ->
            db.select(HEAD).from(ITEM).where(ITEM.ITEM_ID.`in`(chunk)).fetch { out[it.get(ITEM.ITEM_ID)!!] = head(it) }
        }
        return out
    }

    /** Every item, streamed. */
    fun forEachHead(action: (StoredItem) -> Unit) {
        db.select(HEAD).from(ITEM).fetchSize(HEADS_STREAMED).fetchLazy().use { cursor ->
            cursor.forEach { action(head(it)) }
        }
    }

    /**
     * What the list of a content type sorts and filters by, of every item: id, version, update, when the application
     * first saw it.
     */
    fun keys(): List<ItemKeys> =
        db.select(ITEM.ITEM_ID, ITEM.VERSION, ITEM.UPDATED_AT, ITEM.FIRST_SEEN_AT)
            .from(ITEM)
            .fetchSize(STREAMED_ROWS)
            .fetch { ItemKeys(it.value1()!!, it.value2()!!, it.value3()!!, it.value4()!!) }

    fun count(): Int = db.fetchCount(ITEM)

    fun exists(itemId: String): Boolean = db.fetchExists(ITEM, ITEM.ITEM_ID.eq(itemId))

    /**
     * The item removed from the library: everything the source database holds of it — its versions, values, texts,
     * pictures, reviews, parts and every vector of them go with it — and the pages kept of it alone. Nothing tells it
     * was there: seen on the site again, it is a work never seen.
     */
    fun remove(itemId: String) = db.transaction { c ->
        val tx = c.dsl()
        tx.deleteFrom(CAPTURED_PAGE).where(CAPTURED_PAGE.ITEM_ID.eq(itemId)).execute()
        tx.deleteFrom(ITEM).where(ITEM.ITEM_ID.eq(itemId)).execute()
    }

    fun latestUpdate(): Instant? = db.select(DSL.max(ITEM.UPDATED_AT)).from(ITEM).fetchOne()?.value1()

    /** The items whose title holds [piece], any case, the newest first. */
    fun findByTitle(piece: String, limit: Int): List<StoredItem> =
        db.select(HEAD)
            .from(ITEM)
            .where(DSL.lower(ITEM.TITLE).contains(piece.lowercase()))
            .orderBy(ITEM.UPDATED_AT.desc())
            .limit(limit)
            .fetch(::head)

    /** The title of every item as the site gives it: item to title. */
    fun titles(): Map<String, String> =
        db.select(ITEM.ITEM_ID, ITEM.TITLE)
            .from(ITEM)
            .fetchSize(STREAMED_ROWS)
            .fetchMap({ it.value1()!! }, { it.value2()!! })

    companion object {
        /** The heads streamed from the database at a time: memory, not meaning. */
        private const val HEADS_STREAMED = 2000

        private val HEAD = listOf(ITEM.ITEM_ID, ITEM.URL, ITEM.TITLE, ITEM.VERSION, ITEM.UPDATED_AT, ITEM.FIRST_SEEN_AT)

        private fun head(r: Record) =
            StoredItem(
                id = r.get(ITEM.ITEM_ID)!!,
                url = r.get(ITEM.URL)!!,
                title = r.get(ITEM.TITLE)!!,
                version = r.get(ITEM.VERSION)!!,
                updatedAt = r.get(ITEM.UPDATED_AT)!!,
                firstSeenAt = r.get(ITEM.FIRST_SEEN_AT)!!,
            )

        /**
         * Whether the head kept — its [version], [title], [url] and [updatedAt] — differs from the [item] read, updated
         * at [itemUpdatedAt].
         */
        private fun headChanged(
            version: String?,
            title: String?,
            url: String?,
            updatedAt: Instant?,
            item: ItemHead,
            itemUpdatedAt: Instant,
        ): Boolean = version != item.version || title != item.title || url != item.url || updatedAt != itemUpdatedAt
    }
}

/** What the list sorts and filters by. */
data class ItemKeys(val id: String, val version: String, val updatedAt: Instant, val firstSeenAt: Instant)
