package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM
import io.github.vlsergey.recommend4me.source.ItemNumbers
import org.jooq.DSLContext

/**
 * THE SITE'S IDS OF THE ITEMS AND THE APPLICATION'S NUMBERS OF THEM: a work is kept by the application's own number
 * (item.internal_id), every table of its data bound to it so, the site's id beside it (item.site_id) — a site that
 * changes the ids of its works changes that alone. A site's page names a work by the site's id: told here what number
 * it is kept by, and back.
 */
interface ItemSiteIds : ItemNumbers {
    /** The site's id of the item [id]; null of one no more there. */
    fun siteIdOf(id: Long): String?

    /**
     * The site's id and the application's number of every item, in the order of the numbers — the file's order of the
     * rows — as an array of numbers and one of ids: a million works two arrays, not a million pairs of objects.
     */
    fun siteIds(): SiteIds
}

/**
 * The site's id and the application's number of every item of a source, at the same place of [numbers] and [sites], in
 * the order of the numbers.
 */
class SiteIds(val numbers: LongArray, val sites: Array<String>) {
    /** The site's id of the item numbered [id]; null of one not there. */
    fun siteOf(id: Long): String? = numbers.binarySearch(id).takeIf { it >= 0 }?.let { sites[it] }
}

/** The [ItemSiteIds] of the source database [db]. */
internal class ItemSiteIdRepository(private val db: DSLContext) : ItemSiteIds {

    override fun idOf(siteId: String): Long? =
        db.select(ITEM.INTERNAL_ID).from(ITEM).where(ITEM.SITE_ID.eq(siteId)).fetchOne()?.value1()

    override fun idsOf(siteIds: Collection<String>): Map<String, Long> {
        val out = HashMap<String, Long>()
        siteIds.distinct().chunked(ITEM_BATCH).forEach { chunk ->
            db.select(ITEM.SITE_ID, ITEM.INTERNAL_ID).from(ITEM).where(ITEM.SITE_ID.`in`(chunk)).fetch {
                out[it.value1()!!] = it.value2()!!
            }
        }
        return out
    }

    override fun siteIdsOf(ids: Collection<Long>): Map<Long, String> {
        val out = HashMap<Long, String>()
        ids.distinct().chunked(ITEM_BATCH).forEach { chunk ->
            db.select(ITEM.INTERNAL_ID, ITEM.SITE_ID).from(ITEM).where(ITEM.INTERNAL_ID.`in`(chunk)).fetch {
                out[it.value1()!!] = it.value2()!!
            }
        }
        return out
    }

    override fun siteIdOf(id: Long): String? =
        db.select(ITEM.SITE_ID).from(ITEM).where(ITEM.INTERNAL_ID.eq(id)).fetchOne()?.value1()

    override fun siteIds(): SiteIds {
        var numbers = LongArray(INITIAL_IDS)
        val sites = ArrayList<String>()
        db.select(ITEM.INTERNAL_ID, ITEM.SITE_ID)
            .from(ITEM)
            .orderBy(ITEM.INTERNAL_ID)
            .fetchSize(STREAMED_ROWS)
            .fetchLazy()
            .use { cursor ->
                cursor.forEach {
                    if (sites.size == numbers.size) numbers = numbers.copyOf(numbers.size * 2)
                    numbers[sites.size] = it.value1()!!
                    sites += it.value2()!!
                }
            }
        return SiteIds(numbers.copyOf(sites.size), sites.toTypedArray())
    }

    private companion object {
        /** The site's ids of a source's items read into arrays of this length first, doubled as they fill. */
        const val INITIAL_IDS = 1024
    }
}
