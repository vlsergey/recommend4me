package io.github.vlsergey.recommend4me.picture

import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM_PICTURE_VECTOR
import io.github.vlsergey.recommend4me.database.source.tables.references.PICTURE
import io.github.vlsergey.recommend4me.database.streamed
import io.github.vlsergey.recommend4me.vector.Vectors
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * The stored vectors of the pictures of every item of one source, made of its analysed pictures
 * ([PictureRepository.combine]: the cover, the mean of the screenshots) and kept by the encoder that made them: what
 * the models read of an item's pictures.
 */
class ItemPictureVectors(private val db: DSLContext) {

    /**
     * Makes again the stored vectors of the items' pictures from their analysed pictures; an item without any loses its
     * rows.
     */
    fun refreshItemVectors(itemIds: Collection<String>, encoder: String) {
        if (itemIds.isEmpty()) return
        itemIds.distinct().chunked(PICTURES_BATCH).forEach { ids ->
            val perItem = HashMap<String, MutableList<Pair<Int, FloatArray>>>()
            db.select(PICTURE.ITEM_ID, PICTURE.POSITION, PICTURE.VEC)
                .from(PICTURE)
                .where(PICTURE.ITEM_ID.`in`(ids), analyzedBy(encoder))
                .fetch {
                    perItem.getOrPut(it.value1()!!) { ArrayList() } += it.value2()!! to Vectors.fromHalf(it.value3()!!)
                }
            db.transaction { configuration ->
                val tx = DSL.using(configuration)
                tx.deleteFrom(ITEM_PICTURE_VECTOR).where(ITEM_PICTURE_VECTOR.ITEM_ID.`in`(ids)).execute()
                val rows = perItem.flatMap { (id, list) ->
                    PictureRepository.combine(list).map { (block, v) -> Triple(id, block, v) }
                }
                if (rows.isNotEmpty()) {
                    // Merged, not inserted: two pages of one item read at once both make its vectors,
                    // and the second must replace the first's rows, not fail on them
                    val batch =
                        tx.batch(
                            tx.mergeInto(
                                    ITEM_PICTURE_VECTOR,
                                    ITEM_PICTURE_VECTOR.ITEM_ID,
                                    ITEM_PICTURE_VECTOR.BLOCK,
                                    ITEM_PICTURE_VECTOR.ENCODER,
                                    ITEM_PICTURE_VECTOR.VEC,
                                )
                                .key(ITEM_PICTURE_VECTOR.ITEM_ID, ITEM_PICTURE_VECTOR.BLOCK)
                                .values(null as String?, null as String?, null as String?, null as ByteArray?)
                        )
                    rows.forEach { (id, block, v) -> batch.bind(id, block, encoder, Vectors.half(v)) }
                    batch.execute()
                }
            }
        }
    }

    /** The items with pictures analysed by [encoder] and no stored vectors of them by it. */
    fun itemsWithoutVectors(encoder: String): List<String> =
        db.selectDistinct(PICTURE.ITEM_ID)
            .from(PICTURE)
            .where(analyzedBy(encoder))
            .andNotExists(
                DSL.selectOne()
                    .from(ITEM_PICTURE_VECTOR)
                    .where(ITEM_PICTURE_VECTOR.ITEM_ID.eq(PICTURE.ITEM_ID), ITEM_PICTURE_VECTOR.ENCODER.eq(encoder))
            )
            .fetch { it.value1()!! }

    /** Every item's stored vectors of its pictures by [encoder], streamed: (item, block key, vector). */
    fun forEachItemVector(encoder: String, action: (itemId: String, key: String, vector: FloatArray) -> Unit) =
        forEachItemHalves(encoder) { id, key, bytes -> action(id, key, Vectors.fromHalf(bytes)) }

    /** [forEachItemVector] as stored: every vector its half-precision numbers, little-endian. */
    fun forEachItemHalves(encoder: String, action: (itemId: String, key: String, halves: ByteArray) -> Unit) =
        db.streamed("SELECT item_id, block, vec FROM item_picture_vector WHERE encoder = ?", listOf(encoder)) { r ->
            action(r.getString("item_id"), r.getString("block"), r.getBytes("vec"))
        }

    /** The stored vectors of the items [ids]: item to block key to vector. */
    fun itemVectorsOf(ids: Collection<String>, encoder: String): Map<String, Map<String, FloatArray>> =
        itemHalvesOf(ids, encoder).mapValues { (_, own) -> own.mapValues { Vectors.fromHalf(it.value) } }

    /** [itemVectorsOf] as stored: every vector its half-precision numbers, little-endian. */
    fun itemHalvesOf(ids: Collection<String>, encoder: String): Map<String, Map<String, ByteArray>> {
        val out = HashMap<String, MutableMap<String, ByteArray>>()
        ids.distinct().chunked(PICTURES_BATCH).forEach { chunk ->
            db.select(ITEM_PICTURE_VECTOR.ITEM_ID, ITEM_PICTURE_VECTOR.BLOCK, ITEM_PICTURE_VECTOR.VEC)
                .from(ITEM_PICTURE_VECTOR)
                .where(ITEM_PICTURE_VECTOR.ITEM_ID.`in`(chunk), ITEM_PICTURE_VECTOR.ENCODER.eq(encoder))
                .fetch { out.getOrPut(it.value1()!!) { HashMap() }[it.value2()!!] = it.value3()!! }
        }
        return out
    }
}
