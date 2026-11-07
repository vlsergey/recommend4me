package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.NORMALIZED_TAG
import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.TAG_VECTOR
import io.github.vlsergey.recommend4me.vector.Vectors
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * THE VECTORS OF THE TAGS' NAMES, in the user's dictionaries ([db]): one of every name of a tag by an encoder, made in
 * the background ([TagVectors]) and dropped when its names change.
 */
class TagVectorRepository(private val db: DSLContext) {

    /** The vectors of the tags [ids] dropped: their names changed, they are made anew in the background. */
    fun forgetVectors(ids: Collection<Long>) {
        if (ids.isNotEmpty()) db.deleteFrom(TAG_VECTOR).where(TAG_VECTOR.TAG.`in`(ids)).execute()
    }

    /** The tags that have no vector by [plugin]'s [model]: a join, not a read of every tag. */
    fun withoutVectors(plugin: String, model: String): List<Long> {
        val v = TAG_VECTOR
        return db.select(NORMALIZED_TAG.ID)
            .from(NORMALIZED_TAG)
            .leftJoin(v)
            .on(v.TAG.eq(NORMALIZED_TAG.ID), v.PLUGIN.eq(plugin), v.MODEL.eq(model))
            .where(v.TAG.isNull)
            .fetch { it.value1()!! }
    }

    /** Keeps the vectors of the tags, one of every name of each; none: a tag of no name to encode. */
    fun keepVectors(plugin: String, model: String, vectors: Map<Long, List<FloatArray>>) = db.transaction { c ->
        val tx = DSL.using(c)
        val v = TAG_VECTOR
        vectors.forEach { (tag, list) ->
            val packed = list.map(Vectors::half).toTypedArray<ByteArray?>()
            tx.insertInto(v)
                .set(v.TAG, tag)
                .set(v.PLUGIN, plugin)
                .set(v.MODEL, model)
                .set(v.VECTORS, packed)
                .onDuplicateKeyUpdate()
                .set(v.VECTORS, packed)
                .execute()
        }
    }

    /** The vectors of every tag's names by [plugin]'s [model], by key: the tags of no name left out. */
    fun vectors(plugin: String, model: String): Map<String, List<FloatArray>> {
        val v = TAG_VECTOR
        return db.select(v.TAG, v.VECTORS)
            .from(v)
            .where(v.PLUGIN.eq(plugin), v.MODEL.eq(model))
            .fetch()
            .mapNotNull { r ->
                r.value2()!!
                    .mapNotNull { it?.let(Vectors::fromHalf) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { TagRepository.key(r.value1()!!) to it }
            }
            .toMap()
    }

    /** The vectors of every name of the tag [tag] by the encoder [model] of [plugin]; none when it has none. */
    fun vectorsOf(tag: Long, plugin: String, model: String): List<FloatArray> {
        val v = TAG_VECTOR
        return db.select(v.VECTORS)
            .from(v)
            .where(v.TAG.eq(tag), v.PLUGIN.eq(plugin), v.MODEL.eq(model))
            .fetchOne()
            ?.value1()
            ?.mapNotNull { it?.let(Vectors::fromHalf) }
            .orEmpty()
    }
}
