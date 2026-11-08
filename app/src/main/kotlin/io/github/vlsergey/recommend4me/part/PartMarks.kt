package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.source.tables.references.PART
import io.github.vlsergey.recommend4me.vector.Vectors
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * What a part with a text is made of, its text not read: its id, its title, the hash of its text, and the encoder and
 * the hash of the text its windows' vectors were made of.
 */
data class PartMark(
    val partId: String,
    val title: String?,
    val hash: String,
    val encoder: String?,
    val encodedHash: String?,
)

/**
 * WHAT THE PARTS OF ONE SOURCE'S WORKS ARE MADE OF, their texts not read ([PartMark]): what the search's fingerprint of
 * a work reads of its chapters. A part kept before the hash of its text was ([PART.CONTENT_HASH]) is hashed the first
 * time it is asked for, once.
 */
class PartMarks(private val db: DSLContext) {

    /** Of every part with a text of the items [ids] (every item when null), in their order: item to its parts. */
    fun of(ids: Collection<String>?): Map<String, List<PartMark>> {
        if (ids == null) hashUnhashed(null) else ids.distinct().chunked(BATCH).forEach(::hashUnhashed)
        val out = HashMap<String, MutableList<PartMark>>()
        fun read(only: Collection<String>?) =
            db.select(PART.ITEM_ID, PART.PART_ID, PART.TITLE, PART.CONTENT_HASH, PART.ENCODER, PART.ENCODED_HASH)
                .from(PART)
                .where(PART.CONTENT.isNotNull)
                .and(only?.let { PART.ITEM_ID.`in`(it) } ?: DSL.noCondition())
                .orderBy(PART.ITEM_ID, PART.POSITION)
                .fetch {
                    out.getOrPut(it.value1()!!) { ArrayList() } +=
                        PartMark(it.value2()!!, it.value3(), it.value4()!!, it.value5(), it.value6())
                }
        if (ids == null) read(null) else ids.distinct().chunked(BATCH).forEach(::read)
        return out
    }

    /**
     * The hash of the text of every part of the items [ids] (every item when null) kept without one, written now: the
     * texts of a few parts at a time read.
     */
    private fun hashUnhashed(ids: Collection<String>?) {
        val unhashed =
            db.select(PART.ITEM_ID, PART.PART_ID)
                .from(PART)
                .where(PART.CONTENT.isNotNull, PART.CONTENT_HASH.isNull)
                .and(ids?.let { PART.ITEM_ID.`in`(it) } ?: DSL.noCondition())
                .fetch { it.value1()!! to it.value2()!! }
        unhashed.groupBy({ it.first }, { it.second }).forEach { (item, parts) ->
            val hashes =
                db.select(PART.PART_ID, PART.CONTENT)
                    .from(PART)
                    .where(PART.ITEM_ID.eq(item), PART.PART_ID.`in`(parts), PART.CONTENT.isNotNull)
                    .fetch { it.value1()!! to Vectors.hash(it.value2()!!) }
            if (hashes.isEmpty()) return@forEach
            val batch =
                db.batch(
                    db.update(PART)
                        .set(PART.CONTENT_HASH, DSL.param("hash", String::class.java))
                        .where(
                            PART.ITEM_ID.eq(DSL.param("item", String::class.java)),
                            PART.PART_ID.eq(DSL.param("part", String::class.java)),
                        )
                )
            hashes.forEach { (part, hash) -> batch.bind(hash, item, part) }
            batch.execute()
        }
    }

    private companion object {
        /** The items read by one statement: the length of a query's list, not a meaning. */
        const val BATCH = 500
    }
}
