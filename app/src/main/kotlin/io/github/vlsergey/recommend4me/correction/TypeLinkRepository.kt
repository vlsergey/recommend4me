package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.work.WorkPairJournal
import io.github.vlsergey.recommend4me.work.WorkRepository
import java.time.Instant

/**
 * Which items of the sources of one content type are one work, and the user's word on those that are
 * not — different works, or related of another kind (a translation, a part) — by the items, kept
 * by their normalized works ([WorkRepository]): a word on two items is a word on their works. Every
 * word said and taken back is written to the [journal], with the word the pair had before.
 */
class TypeLinkRepository(
    private val works: WorkRepository,
    private val journal: WorkPairJournal,
    /** Every source of the type. */
    private val sources: Collection<String>,
    /** The facets of one value of the type ([io.github.vlsergey.recommend4me.source.FacetDef.single]): of two works merged, one stands. */
    private val single: Set<String> = emptySet(),
) {

    /** Every work of several items: its items. */
    fun groups(): List<List<ItemKey>> = works.groups(sources).values.toList()

    /** The two items one work: their works merged, the smaller id kept, a word between them gone. */
    fun link(a: ItemKey, b: ItemKey, now: Instant) {
        val before = word(a, b, now)
        val x = works.of(a, now)
        val y = works.of(b, now)
        if (x != y) works.merge(maxOf(x, y), minOf(x, y), now, single)
        journal.write(a, b, SAME, before, now)
    }

    /** The two items one work no more: of a work holding both, [b] taken out. */
    fun unlink(a: ItemKey, b: ItemKey, now: Instant) {
        if (takeOut(a, b, now)) journal.write(a, b, null, SAME, now)
    }

    /** Of a work holding both, [b] taken out; false of two works. */
    private fun takeOut(a: ItemKey, b: ItemKey, now: Instant): Boolean {
        if (works.of(a, now) != works.of(b, now)) return false
        works.takeOut(b, now)
        return true
    }

    /** The two items said to be different works; [b] taken out of a work holding both. */
    fun distinct(a: ItemKey, b: ItemKey, now: Instant) = say(a, b, DIFFERENT, now)

    /** The two items said to be related works of the [kind] — one the translation of the other, one a part of the other —, not one: [b] taken out of a work holding both. */
    fun relate(a: ItemKey, b: ItemKey, kind: String, now: Instant) = say(a, b, kind, now)

    private fun say(a: ItemKey, b: ItemKey, kind: String, now: Instant) {
        val before = word(a, b, now)
        takeOut(a, b, now)
        works.say(works.of(a, now), works.of(b, now), kind, now)
        journal.write(a, b, kind, before, now)
    }

    /** The word on the works of the two taken back. */
    fun takeBack(a: ItemKey, b: ItemKey, now: Instant) {
        val before = word(a, b, now)
        works.takeBack(works.of(a, now), works.of(b, now))
        journal.write(a, b, null, before, now)
    }

    /** The word on the two items now: one work ([SAME]), the word on their two works, or none. */
    fun word(a: ItemKey, b: ItemKey, now: Instant): String? {
        val x = works.of(a, now)
        val y = works.of(b, now)
        return if (x == y) SAME else works.word(x, y)
    }

    /** The word on every pair of items now, as [word] tells it, read in one go: of an item bound to no work, none. */
    fun words(pairs: Collection<Pair<ItemKey, ItemKey>>): Map<Pair<ItemKey, ItemKey>, String?> {
        val bound = pairs.flatMap { it.toList() }.distinct().groupBy { it.source }
            .flatMap { (source, keys) -> works.ofItems(source, keys.map { it.id }).map { (id, work) -> ItemKey(source, id) to work } }.toMap()
        val words = works.words()
        return pairs.associateWith { (a, b) ->
            val x = bound[a] ?: return@associateWith null
            val y = bound[b] ?: return@associateWith null
            if (x == y) SAME else words[WorkRepository.pair(x, y)]
        }
    }

    /** Every word on two items of the type, the newest first. */
    fun journal() = journal.all(sources)

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
        const val SAME = "SAME"
        const val DIFFERENT = "DIFFERENT"
    }
}
