package io.github.vlsergey.recommend4me.setvector

import io.github.vlsergey.recommend4me.database.source.tables.references.SET_VECTOR
import io.github.vlsergey.recommend4me.vector.Vectors
import org.jooq.DSLContext

/** The kinds of sets an item has, with the key of their block in the model's input. */
enum class SetKind(val key: String) {
    /** The screenshots one by one. */
    SCREENS("set:screens"),

    /** What other people write, opinion by opinion, each read whole. */
    REVIEWS("set:reviews"),

    /** The text of the work, window by window. */
    PARTS("set:parts"),
}

/** The set vectors of one source's items, each made by the directions [embedding id][SET_VECTOR.EMBEDDING_ID] of its content type. */
class SetVectorRepository(private val db: DSLContext) {

    /** What every item's stored vector of [kind] by the directions [embeddingId] was made of: how many members. */
    fun sources(kind: SetKind, embeddingId: Long): Map<String, Int> =
        db.select(SET_VECTOR.ITEM_ID, SET_VECTOR.SOURCE).from(SET_VECTOR)
            .where(SET_VECTOR.KIND.eq(kind.name), SET_VECTOR.EMBEDDING_ID.eq(embeddingId))
            .fetch().associate { it.value1()!! to it.value2()!! }

    fun save(itemId: String, kind: SetKind, embeddingId: Long, source: Int, vector: FloatArray) {
        val packed = Vectors.pack(vector)
        db.insertInto(SET_VECTOR)
            .set(SET_VECTOR.ITEM_ID, itemId).set(SET_VECTOR.KIND, kind.name).set(SET_VECTOR.EMBEDDING_ID, embeddingId)
            .set(SET_VECTOR.SOURCE, source).set(SET_VECTOR.VEC, packed)
            .onDuplicateKeyUpdate()
            .set(SET_VECTOR.EMBEDDING_ID, embeddingId).set(SET_VECTOR.SOURCE, source).set(SET_VECTOR.VEC, packed)
            .execute()
    }

    /** The vectors of the items by the directions in use ([current], kind to embedding id): item to block key to vector. */
    fun ofMany(ids: Collection<String>, current: Map<SetKind, Long>): Map<String, Map<String, FloatArray>> {
        val out = HashMap<String, MutableMap<String, FloatArray>>()
        ids.distinct().chunked(500).forEach { chunk ->
            db.select(SET_VECTOR.ITEM_ID, SET_VECTOR.KIND, SET_VECTOR.EMBEDDING_ID, SET_VECTOR.VEC).from(SET_VECTOR)
                .where(SET_VECTOR.ITEM_ID.`in`(chunk))
                .fetch { r ->
                    val kind = SetKind.valueOf(r.value2()!!)
                    if (current[kind] == r.value3()) out.getOrPut(r.value1()!!) { HashMap() }[kind.key] = Vectors.unpack(r.value4()!!)
                }
        }
        return out
    }

    /** Every stored vector by the directions in use, streamed: (item, block key, vector). */
    fun forEach(current: Map<SetKind, Long>, action: (itemId: String, key: String, vector: FloatArray) -> Unit) {
        db.select(SET_VECTOR.ITEM_ID, SET_VECTOR.KIND, SET_VECTOR.EMBEDDING_ID, SET_VECTOR.VEC).from(SET_VECTOR)
            .fetchSize(2000).fetchLazy().use { cursor ->
                cursor.forEach { r ->
                    val kind = SetKind.valueOf(r.value2()!!)
                    if (current[kind] == r.value3()) action(r.value1()!!, kind.key, Vectors.unpack(r.value4()!!))
                }
            }
    }
}
