package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.database.corrections.tables.references.ITEM_DISTINCT
import io.github.vlsergey.recommend4me.database.corrections.tables.references.ITEM_LINK
import io.github.vlsergey.recommend4me.database.corrections.tables.references.ITEM_RELATION
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.DSLContext
import java.time.Instant

/**
 * Items of different sources of one content type that the user said are one work: a link kept in the
 * corrections of the source of the smaller of the two keys, its item first; and those the user said
 * are different works ([distinct]) or related works of another kind ([relate]: a translation, a part), kept alike.
 */
class TypeLinkRepository(
    /** The corrections of every source of the type, by source. */
    private val corrections: Map<String, DSLContext>,
) {

    private fun of(source: String): DSLContext = corrections[source] ?: error("No such source: $source")

    fun link(a: ItemKey, b: ItemKey, now: Instant) {
        val (first, second) = ordered(a, b)
        of(first.source).insertInto(ITEM_LINK)
            .set(ITEM_LINK.ITEM_ID, first.id).set(ITEM_LINK.OTHER_SOURCE, second.source).set(ITEM_LINK.OTHER_ITEM_ID, second.id)
            .set(ITEM_LINK.LINKED_AT, now)
            .onDuplicateKeyIgnore().execute()
    }

    fun unlink(a: ItemKey, b: ItemKey) {
        fun drop(x: ItemKey, y: ItemKey) = corrections[x.source]?.deleteFrom(ITEM_LINK)
            ?.where(ITEM_LINK.ITEM_ID.eq(x.id), ITEM_LINK.OTHER_SOURCE.eq(y.source), ITEM_LINK.OTHER_ITEM_ID.eq(y.id))?.execute()
        drop(a, b)
        drop(b, a)
    }

    /** Every link across the sources, read source by source. */
    fun links(): List<Pair<ItemKey, ItemKey>> = corrections.flatMap { (source, db) ->
        db.select(ITEM_LINK.ITEM_ID, ITEM_LINK.OTHER_SOURCE, ITEM_LINK.OTHER_ITEM_ID).from(ITEM_LINK).where(ITEM_LINK.OTHER_SOURCE.ne(source))
            .fetch { ItemKey(source, it.value1()!!) to ItemKey(it.value2()!!, it.value3()!!) }
    }

    /** The two items said to be different works; a link between them, if any, taken away. */
    fun distinct(a: ItemKey, b: ItemKey, now: Instant) {
        unlink(a, b)
        val (first, second) = ordered(a, b)
        of(first.source).insertInto(ITEM_DISTINCT)
            .set(ITEM_DISTINCT.ITEM_ID, first.id).set(ITEM_DISTINCT.OTHER_SOURCE, second.source).set(ITEM_DISTINCT.OTHER_ITEM_ID, second.id)
            .set(ITEM_DISTINCT.SAID_AT, now)
            .onDuplicateKeyIgnore().execute()
    }

    /** The word that the two are different works taken back. */
    fun undistinct(a: ItemKey, b: ItemKey) {
        val (first, second) = ordered(a, b)
        corrections[first.source]?.deleteFrom(ITEM_DISTINCT)
            ?.where(ITEM_DISTINCT.ITEM_ID.eq(first.id), ITEM_DISTINCT.OTHER_SOURCE.eq(second.source), ITEM_DISTINCT.OTHER_ITEM_ID.eq(second.id))?.execute()
    }

    /** Every pair said to be different works, the smaller key first, read source by source. */
    fun distincts(): List<Pair<ItemKey, ItemKey>> = corrections.flatMap { (source, db) ->
        db.select(ITEM_DISTINCT.ITEM_ID, ITEM_DISTINCT.OTHER_SOURCE, ITEM_DISTINCT.OTHER_ITEM_ID).from(ITEM_DISTINCT)
            .fetch { ItemKey(source, it.value1()!!) to ItemKey(it.value2()!!, it.value3()!!) }
    }

    /**
     * The two items said to be related works of the [kind] — one the translation of the other, one a
     * part of the other —, not linked: a link between them, if any, taken away; a kind said before replaced.
     */
    fun relate(a: ItemKey, b: ItemKey, kind: String, now: Instant) {
        unlink(a, b)
        val (first, second) = ordered(a, b)
        of(first.source).insertInto(ITEM_RELATION)
            .set(ITEM_RELATION.ITEM_ID, first.id).set(ITEM_RELATION.OTHER_SOURCE, second.source).set(ITEM_RELATION.OTHER_ITEM_ID, second.id)
            .set(ITEM_RELATION.KIND, kind).set(ITEM_RELATION.SAID_AT, now)
            .onDuplicateKeyUpdate().set(ITEM_RELATION.KIND, kind).set(ITEM_RELATION.SAID_AT, now)
            .execute()
    }

    /** The word that the two are related works taken back. */
    fun unrelate(a: ItemKey, b: ItemKey) {
        val (first, second) = ordered(a, b)
        corrections[first.source]?.deleteFrom(ITEM_RELATION)
            ?.where(ITEM_RELATION.ITEM_ID.eq(first.id), ITEM_RELATION.OTHER_SOURCE.eq(second.source), ITEM_RELATION.OTHER_ITEM_ID.eq(second.id))?.execute()
    }

    /** Every pair said to be related works, the smaller key first, with the kind, read source by source. */
    fun relations(): Map<Pair<ItemKey, ItemKey>, String> = corrections.flatMap { (source, db) ->
        db.select(ITEM_RELATION.ITEM_ID, ITEM_RELATION.OTHER_SOURCE, ITEM_RELATION.OTHER_ITEM_ID, ITEM_RELATION.KIND).from(ITEM_RELATION)
            .fetch { (ItemKey(source, it.value1()!!) to ItemKey(it.value2()!!, it.value3()!!)) to it.value4()!! }
    }.toMap()

    /** The two keys, the smaller first: the order the pair is kept in. */
    fun ordered(a: ItemKey, b: ItemKey): Pair<ItemKey, ItemKey> = if (a.toString() < b.toString()) a to b else b to a

    /** The links of the item removed from the library, on either side. */
    fun forget(key: ItemKey) {
        corrections.forEach { (source, db) ->
            val mine = if (source == key.source) ITEM_LINK.ITEM_ID.eq(key.id).and(ITEM_LINK.OTHER_SOURCE.ne(source)) else null
            val theirs = ITEM_LINK.OTHER_SOURCE.eq(key.source).and(ITEM_LINK.OTHER_ITEM_ID.eq(key.id)).and(ITEM_LINK.OTHER_SOURCE.ne(source))
            db.deleteFrom(ITEM_LINK).where(if (mine != null) mine.or(theirs) else theirs).execute()
            val ownDistinct = if (source == key.source) ITEM_DISTINCT.ITEM_ID.eq(key.id) else null
            val theirDistinct = ITEM_DISTINCT.OTHER_SOURCE.eq(key.source).and(ITEM_DISTINCT.OTHER_ITEM_ID.eq(key.id))
            db.deleteFrom(ITEM_DISTINCT).where(if (ownDistinct != null) ownDistinct.or(theirDistinct) else theirDistinct).execute()
            val ownRelation = if (source == key.source) ITEM_RELATION.ITEM_ID.eq(key.id) else null
            val theirRelation = ITEM_RELATION.OTHER_SOURCE.eq(key.source).and(ITEM_RELATION.OTHER_ITEM_ID.eq(key.id))
            db.deleteFrom(ITEM_RELATION).where(if (ownRelation != null) ownRelation.or(theirRelation) else theirRelation).execute()
        }
    }
}
