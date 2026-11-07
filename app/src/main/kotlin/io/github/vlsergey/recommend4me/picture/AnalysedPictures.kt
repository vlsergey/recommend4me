package io.github.vlsergey.recommend4me.picture

import io.github.vlsergey.recommend4me.database.source.tables.references.PICTURE
import io.github.vlsergey.recommend4me.vector.Vectors
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * The vectors of the analysed pictures of one source, one by one, as the encoder in use made them: of an item, of the
 * pictures marked, of the screenshots, of the whole catalogue.
 */
class AnalysedPictures(private val db: DSLContext) {

    /** Every analysed picture of the item with its position, not combined. */
    fun positioned(itemId: String, encoder: String): List<Pair<Int, FloatArray>> =
        db.select(PICTURE.POSITION, PICTURE.VEC)
            .from(PICTURE)
            .where(PICTURE.ITEM_ID.eq(itemId), analyzedBy(encoder))
            .orderBy(PICTURE.POSITION)
            .fetch { it.value1()!! to Vectors.fromHalf(it.value2()!!) }

    /** The vectors of the pictures [refs] (item, position) analysed by [encoder], at their current address. */
    fun vectorsOf(
        refs: Collection<Pair<String, Int>>,
        encoder: String,
    ): Map<Pair<String, Int>, Pair<String, FloatArray>> {
        val out = HashMap<Pair<String, Int>, Pair<String, FloatArray>>()
        refs
            .map { it.first }
            .distinct()
            .chunked(PICTURES_BATCH)
            .forEach { chunk ->
                db.select(PICTURE.ITEM_ID, PICTURE.POSITION, PICTURE.URL, PICTURE.VEC)
                    .from(PICTURE)
                    .where(PICTURE.ITEM_ID.`in`(chunk), analyzedBy(encoder))
                    .fetch { r ->
                        (r.value1()!! to r.value2()!!)
                            .takeIf { it in refs }
                            ?.let { out[it] = r.value3()!! to Vectors.fromHalf(r.value4()!!) }
                    }
            }
        return out
    }

    /** How many screenshots (not the cover) of every item are analysed. */
    fun screenshotCounts(encoder: String): Map<String, Int> =
        db.select(PICTURE.ITEM_ID, DSL.count())
            .from(PICTURE)
            .where(PICTURE.POSITION.gt(0), analyzedBy(encoder))
            .groupBy(PICTURE.ITEM_ID)
            .fetch()
            .associate { it.value1()!! to it.value2()!! }

    /** Every analysed screenshot (not the cover) of the items, one vector each. */
    fun screenshots(itemIds: List<String>, encoder: String): Map<String, List<FloatArray>> {
        val out = HashMap<String, MutableList<FloatArray>>()
        itemIds.chunked(PICTURES_BATCH).forEach { ids ->
            db.select(PICTURE.ITEM_ID, PICTURE.VEC)
                .from(PICTURE)
                .where(PICTURE.ITEM_ID.`in`(ids), PICTURE.POSITION.gt(0), analyzedBy(encoder))
                .fetch { out.getOrPut(it.value1()!!) { ArrayList() } += Vectors.fromHalf(it.value2()!!) }
        }
        return out
    }

    /** [n] analysed pictures of the catalogue drawn at random. */
    fun sample(n: Int, encoder: String): List<FloatArray> =
        db.select(PICTURE.VEC).from(PICTURE).where(analyzedBy(encoder)).orderBy(DSL.rand()).limit(n).fetch {
            Vectors.fromHalf(it.value1()!!)
        }

    /** Every analysed picture of the catalogue, streamed item by item: (item, its pictures by position). */
    fun forEachItem(encoder: String, action: (itemId: String, pictures: List<Pair<Int, FloatArray>>) -> Unit) {
        var current: String? = null
        val list = ArrayList<Pair<Int, FloatArray>>()
        db.select(PICTURE.ITEM_ID, PICTURE.POSITION, PICTURE.VEC)
            .from(PICTURE)
            .where(analyzedBy(encoder))
            .orderBy(PICTURE.ITEM_ID)
            .fetchSize(FETCH_ROWS)
            .fetchLazy()
            .use { cursor ->
                cursor.forEach {
                    if (it.value1() != current) {
                        current?.let { id -> action(id, list.toList()) }
                        list.clear()
                        current = it.value1()
                    }
                    list += it.value2()!! to Vectors.fromHalf(it.value3()!!)
                }
            }
        current?.let { action(it, list.toList()) }
    }

    private companion object {
        /** The rows read from the database at a time while streaming: memory, not meaning. */
        const val FETCH_ROWS = 2000
    }
}
