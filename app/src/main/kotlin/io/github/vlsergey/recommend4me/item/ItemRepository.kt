package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.database.source.tables.references.CAPTURED_PAGE
import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM
import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM_VERSION
import io.github.vlsergey.recommend4me.source.ItemHead
import io.github.vlsergey.recommend4me.source.StoredItem
import io.github.vlsergey.recommend4me.source.Upserted
import io.github.vlsergey.recommend4me.worklist.WorkDatum
import java.time.Instant
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL

/** The ids bound to one query at most: a thousand in a list of a condition, memory and H2's plan, not meaning. */
internal const val ITEM_BATCH = 1000

/** The rows streamed from the database at a time: memory, not meaning. */
internal const val STREAMED_ROWS = 5000

/**
 * The most items whose values are read by their ids, a batch a query ([ItemFacets.facetsOf]): twice a portion of a
 * scoring; of more, one pass over every value of the source.
 */
internal const val BY_IDS_UP_TO = 20_000

/** The longest key of a facet's value the tables of the facets hold (`VARCHAR(500)`). */
internal const val VALUE_KEY_LENGTH = 500

/** The longest name of a facet's value the tables of the facets hold (`VARCHAR(1000)`). */
internal const val NAME_LENGTH = 1000

/**
 * The items of one source as its source database holds them: what every item has, its facets, numbers and texts, and
 * the dictionary of the facets' values — each held by its own part ([ItemFacets], [FacetDictionary],
 * [FacetValueRelations], [ItemValueKeys], [ItemNumbers], [ItemTexts], [ItemKeyList]), this one by the items' heads.
 * [changed] is told of every item whose searchable data was written, the item's date ([ItemChanges]) set as it is.
 */
class ItemRepository
private constructor(
    private val db: DSLContext,
    private val changed: (Collection<Long>) -> Unit,
    dictionary: FacetDictionary,
    texts: Collection<String>,
) :
    FacetDictionary by dictionary,
    FacetValueRelations by FacetValueRelationRepository(db, dictionary),
    ItemFacets by ItemFacetRepository(db, changed, dictionary),
    ItemValueKeys by ItemValueKeyRepository(db, changed),
    ItemNumbers by ItemNumberRepository(db),
    ItemKeyList by ItemKeyRepository(db),
    ItemTexts by ItemTextRepository(db, changed, texts),
    ItemSiteIds by ItemSiteIdRepository(db),
    ItemMeasures by ItemMeasureRepository(db, texts) {

    /** Of the source whose works have the texts of the keys [texts] ([SiteTexts.keysOf]). */
    constructor(
        db: DSLContext,
        texts: Collection<String>,
        changed: (Collection<Long>) -> Unit,
    ) : this(
        db,
        { ids ->
            ItemChanges(db).changed(ids, WorkDatum.DATA, Instant.now())
            changed(ids)
        },
        FacetDictionaryRepository(db),
        texts,
    )

    /**
     * Creates or updates the item the site names [item] by its id: its number — a new item is given one — and whether
     * it is new or its version changed. The head and its version in one transaction: a stop between leaves no head
     * without its versions.
     */
    fun upsert(item: ItemHead, now: Instant): Upserted {
        val (changedVersion, retitled, id) =
            db.transactionResult { c ->
                val tx = DSL.using(c)
                val known =
                    tx.select(ITEM.VERSION, ITEM.TITLE, ITEM.URL, ITEM.UPDATED_AT, ITEM.INTERNAL_ID)
                        .from(ITEM)
                        .where(ITEM.SITE_ID.eq(item.siteId))
                        .fetchOne()
                val updatedAt = item.updatedAt ?: known?.value4() ?: now
                val number =
                    known?.value5()
                        ?: tx.insertInto(ITEM)
                            .set(ITEM.SITE_ID, item.siteId)
                            .set(ITEM.URL, item.url)
                            .set(ITEM.TITLE, item.title)
                            .set(ITEM.VERSION, item.version)
                            .set(ITEM.UPDATED_AT, updatedAt)
                            .set(ITEM.FIRST_SEEN_AT, now)
                            .set(ITEM.CHANGED_AT, now)
                            .set(ITEM.DATA_AT, now)
                            .returningResult(ITEM.INTERNAL_ID)
                            .fetchOne()!!
                            .value1()!!
                if (
                    known != null &&
                        headChanged(known.value1(), known.value2(), known.value3(), known.value4(), item, updatedAt)
                ) {
                    tx.update(ITEM)
                        .set(ITEM.URL, item.url)
                        .set(ITEM.TITLE, item.title)
                        .set(ITEM.VERSION, item.version)
                        .set(ITEM.UPDATED_AT, updatedAt)
                        .set(ITEM.CHANGED_AT, now)
                        .set(ITEM.DATA_AT, now)
                        .where(ITEM.INTERNAL_ID.eq(number))
                        .execute()
                }
                val newVersion = known?.value1() != item.version
                if (newVersion) {
                    tx.insertInto(ITEM_VERSION)
                        .set(ITEM_VERSION.ITEM_ID, number)
                        .set(ITEM_VERSION.VERSION, item.version)
                        .set(ITEM_VERSION.UPDATED_AT, updatedAt)
                        .set(ITEM_VERSION.SEEN_AT, now)
                        .onDuplicateKeyIgnore()
                        .execute()
                }
                Triple(newVersion, known == null || known.value2() != item.title, number)
            }
        // Told once it is kept: a reader of the change finds it
        if (retitled) changed(listOf(id))
        return Upserted(id, changedVersion)
    }

    /** The item numbered [id] by the application, as the site says it. */
    fun find(id: Long): StoredItem? = db.select(HEAD).from(ITEM).where(ITEM.INTERNAL_ID.eq(id)).fetchOne(::head)

    /** The items [ids], by the application's numbers of them, in one query per thousand. */
    fun heads(ids: Collection<Long>): Map<Long, StoredItem> {
        val out = HashMap<Long, StoredItem>()
        ids.distinct().chunked(ITEM_BATCH).forEach { chunk ->
            db.select(HEAD).from(ITEM).where(ITEM.INTERNAL_ID.`in`(chunk)).fetch {
                out[it.get(ITEM.INTERNAL_ID)!!] = head(it)
            }
        }
        return out
    }

    fun count(): Int = db.fetchCount(ITEM)

    fun exists(id: Long): Boolean = db.fetchExists(ITEM, ITEM.INTERNAL_ID.eq(id))

    /**
     * The item removed from the library: everything the source database holds of it — its versions, values, texts,
     * pictures, reviews, parts and every vector of them go with it — and the pages kept of it alone. Nothing tells it
     * was there: seen on the site again, it is a work never seen.
     */
    fun remove(itemId: Long) = db.transaction { c ->
        val tx = c.dsl()
        tx.deleteFrom(CAPTURED_PAGE).where(CAPTURED_PAGE.ITEM_ID.eq(itemId)).execute()
        tx.deleteFrom(ITEM).where(ITEM.INTERNAL_ID.eq(itemId)).execute()
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
    fun titles(): Map<Long, String> =
        db.select(ITEM.INTERNAL_ID, ITEM.TITLE)
            .from(ITEM)
            .fetchSize(STREAMED_ROWS)
            .fetchMap({ it.value1()!! }, { it.value2()!! })

    companion object {
        /** The heads streamed from the database at a time: memory, not meaning. */
        private const val HEADS_STREAMED = 2000

        private val HEAD =
            listOf(
                ITEM.INTERNAL_ID,
                ITEM.SITE_ID,
                ITEM.URL,
                ITEM.TITLE,
                ITEM.VERSION,
                ITEM.UPDATED_AT,
                ITEM.FIRST_SEEN_AT,
            )

        /** The head of the item of the row [r] as the site says it. */
        private fun head(r: Record) =
            StoredItem(
                id = r.get(ITEM.INTERNAL_ID)!!,
                siteId = r.get(ITEM.SITE_ID)!!,
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
