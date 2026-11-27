package io.github.vlsergey.recommend4me.part

import io.github.vlsergey.recommend4me.database.source.tables.references.PART
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
 * a work reads of its chapters. Every part with a text has the hash of it ([PART.CONTENT_HASH]): it is what says the
 * part has one.
 */
class PartMarks(private val db: DSLContext) {

    /** Of every part on the site with a text of the items [ids], in their order: item to its parts. */
    fun of(ids: Collection<Long>): Map<Long, List<PartMark>> {
        val out = HashMap<Long, MutableList<PartMark>>()
        ids.distinct().chunked(BATCH).forEach { chunk ->
            db.select(PART.ITEM_ID, PART.PART_ID, PART.TITLE, PART.CONTENT_HASH, PART.ENCODER, PART.ENCODED_HASH)
                .from(PART)
                .where(PART.CONTENT_HASH.isNotNull, onSite, PART.ITEM_ID.`in`(chunk))
                .orderBy(PART.ITEM_ID, PART.POSITION)
                .fetch {
                    out.getOrPut(it.value1()!!) { ArrayList() } +=
                        PartMark(it.value2()!!, it.value3(), it.value4()!!, it.value5(), it.value6())
                }
        }
        return out
    }

    /** How many items have a part on the site with a text: counted by the database, no row read. */
    fun itemsWithText(): Int =
        db.select(DSL.countDistinct(PART.ITEM_ID))
            .from(PART)
            .where(PART.CONTENT_HASH.isNotNull, onSite)
            .fetchOne()!!
            .value1()!!

    private companion object {
        /** The items read by one statement: the length of a query's list, not a meaning. */
        const val BATCH = 500
    }
}
