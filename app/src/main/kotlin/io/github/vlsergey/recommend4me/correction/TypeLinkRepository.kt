package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.database.corrections.tables.references.ITEM_DISTINCT
import io.github.vlsergey.recommend4me.database.corrections.tables.references.ITEM_LINK
import io.github.vlsergey.recommend4me.database.corrections.tables.references.ITEM_RELATION
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.work.WorkRepository
import org.jooq.DSLContext
import java.time.Instant

/**
 * Which items of the sources of one content type are one work, and the user's word on those that are
 * not — different works, or related of another kind (a translation, a part) — by the items, kept
 * by their normalized works ([WorkRepository]): a word on two items is a word on their works.
 */
class TypeLinkRepository(
    private val works: WorkRepository,
    /** The corrections of every source of the type, by source: where the links were kept before the normalized works. */
    private val corrections: Map<String, DSLContext>,
    /** The facets of one value of the type ([io.github.vlsergey.recommend4me.source.FacetDef.single]): of two works merged, one stands. */
    private val single: Set<String> = emptySet(),
) {

    private val sources = corrections.keys

    /** Every work of several items: its items. */
    fun groups(): List<List<ItemKey>> = works.groups(sources).values.toList()

    /** The two items one work: their works merged, the smaller id kept, a word between them gone. */
    fun link(a: ItemKey, b: ItemKey, now: Instant) {
        val x = works.of(a, now)
        val y = works.of(b, now)
        if (x != y) works.merge(maxOf(x, y), minOf(x, y), now, single)
    }

    /** The two items one work no more: of a work holding both, [b] taken out. */
    fun unlink(a: ItemKey, b: ItemKey, now: Instant) {
        if (works.of(a, now) == works.of(b, now)) works.takeOut(b, now)
    }

    /** The two items said to be different works; [b] taken out of a work holding both. */
    fun distinct(a: ItemKey, b: ItemKey, now: Instant) = say(a, b, DIFFERENT, now)

    /** The two items said to be related works of the [kind] — one the translation of the other, one a part of the other —, not one: [b] taken out of a work holding both. */
    fun relate(a: ItemKey, b: ItemKey, kind: String, now: Instant) = say(a, b, kind, now)

    private fun say(a: ItemKey, b: ItemKey, kind: String, now: Instant) {
        unlink(a, b, now)
        works.say(works.of(a, now), works.of(b, now), kind, now)
    }

    /** The word on the works of the two taken back. */
    fun takeBack(a: ItemKey, b: ItemKey, now: Instant) = works.takeBack(works.of(a, now), works.of(b, now))

    /** Every pair of items of different works said to be different works, the smaller key first. */
    fun distincts(): Set<Pair<ItemKey, ItemKey>> = said().filterValues { it == DIFFERENT }.keys

    /** Every pair of items of works said to be related, the smaller key first, with the kind. */
    fun relations(): Map<Pair<ItemKey, ItemKey>, String> = said().filterValues { it != DIFFERENT }

    /** Every word on two works as said of every pair of their items. */
    private fun said(): Map<Pair<ItemKey, ItemKey>, String> {
        val words = works.words()
        val items = works.itemsOf(words.keys.flatMap { it.toList() }, sources)
        val out = HashMap<Pair<ItemKey, ItemKey>, String>()
        words.forEach { (pair, kind) ->
            for (x in items[pair.first].orEmpty()) for (y in items[pair.second].orEmpty()) out[ordered(x, y)] = kind
        }
        return out
    }

    /** The two keys, the smaller first: the order a pair is kept in. */
    fun ordered(a: ItemKey, b: ItemKey): Pair<ItemKey, ItemKey> = if (a.toString() < b.toString()) a to b else b to a

    /** The item removed from the library. */
    fun forget(key: ItemKey) = works.forget(key)

    /** Of the current grades of every work of several items the newest left. */
    fun keepNewestGrades() = works.keepNewestGrades(sources)

    /**
     * What the corrections of the sources kept before the normalized works given to them — the links
     * first, a word on two works after — and taken from there: nothing to do once done, and what a
     * start broken off left is taken up by the next.
     */
    fun moveToWorks(now: Instant): Int {
        var moved = 0
        corrections.forEach { (source, db) ->
            db.select(ITEM_LINK.ITEM_ID, ITEM_LINK.OTHER_SOURCE, ITEM_LINK.OTHER_ITEM_ID).from(ITEM_LINK).fetch().forEach {
                link(ItemKey(source, it.value1()!!), ItemKey(it.value2()!!, it.value3()!!), now)
                moved++
            }
        }
        corrections.forEach { (source, db) ->
            db.select(ITEM_DISTINCT.ITEM_ID, ITEM_DISTINCT.OTHER_SOURCE, ITEM_DISTINCT.OTHER_ITEM_ID).from(ITEM_DISTINCT).fetch().forEach {
                moved += moveWord(ItemKey(source, it.value1()!!), ItemKey(it.value2()!!, it.value3()!!), DIFFERENT, now)
            }
            db.select(ITEM_RELATION.ITEM_ID, ITEM_RELATION.OTHER_SOURCE, ITEM_RELATION.OTHER_ITEM_ID, ITEM_RELATION.KIND).from(ITEM_RELATION).fetch().forEach {
                moved += moveWord(ItemKey(source, it.value1()!!), ItemKey(it.value2()!!, it.value3()!!), it.value4()!!, now)
            }
        }
        corrections.values.forEach { db ->
            db.deleteFrom(ITEM_LINK).execute()
            db.deleteFrom(ITEM_DISTINCT).execute()
            db.deleteFrom(ITEM_RELATION).execute()
        }
        return moved
    }

    /** A word on two items of before: of two that are one work by then, the links win, as they did. */
    private fun moveWord(a: ItemKey, b: ItemKey, kind: String, now: Instant): Int {
        val x = works.of(a, now)
        val y = works.of(b, now)
        if (x == y) return 0
        works.say(x, y, kind, now)
        return 1
    }

    companion object {
        const val DIFFERENT = "DIFFERENT"
    }
}
