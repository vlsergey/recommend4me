package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.work.WorkRepository
import java.time.Instant

/**
 * Which items of the sources of one content type are one work, and the user's word on those that are
 * not — different works, or related of another kind (a translation, a part) — by the items, kept
 * by their normalized works ([WorkRepository]): a word on two items is a word on their works.
 */
class TypeLinkRepository(
    private val works: WorkRepository,
    /** Every source of the type. */
    private val sources: Collection<String>,
    /** The facets of one value of the type ([io.github.vlsergey.recommend4me.source.FacetDef.single]): of two works merged, one stands. */
    private val single: Set<String> = emptySet(),
) {

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

    companion object {
        const val DIFFERENT = "DIFFERENT"
    }
}
