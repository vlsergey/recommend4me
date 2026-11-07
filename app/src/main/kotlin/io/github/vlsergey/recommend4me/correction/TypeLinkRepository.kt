package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.work.WorkPairJournal
import io.github.vlsergey.recommend4me.work.WorkRepository
import java.time.Instant

/**
 * Which items of the sources of one content type are one work, and the user's word on those that are not — different
 * works, or related of another kind (a translation, a part) — by the items, kept by their normalized works
 * ([WorkRepository]): a word on two items is a word on their works. Every word said and taken back is written to the
 * [journal], with the word the pair had before; the word as it stands is read as [PairWords] read it.
 */
class TypeLinkRepository(
    private val works: WorkRepository,
    private val journal: WorkPairJournal,
    /** Every source of the type. */
    private val sources: Collection<String>,
    /**
     * The facets of one value of the type ([io.github.vlsergey.recommend4me.source.FacetDef.single]): of two works
     * merged, one stands.
     */
    private val single: Set<String> = emptySet(),
) : PairWords by WorkPairWords(works, journal, sources) {

    /** Every work of several items: its items. */
    fun groups(): List<List<ItemKey>> = works.groups(sources).values.toList()

    /** The two items one work: their works merged, the smaller id kept, a word between them gone. */
    fun link(a: ItemKey, b: ItemKey, now: Instant) {
        val before = word(a, b, now)
        val x = works.of(a, now)
        val y = works.of(b, now)
        if (x != y) works.membership.merge(maxOf(x, y), minOf(x, y), now, single)
        journal.write(a, b, SAME, before, now)
    }

    /** The two items one work no more: of a work holding both, [b] taken out. */
    fun unlink(a: ItemKey, b: ItemKey, now: Instant) {
        if (takeOut(a, b, now)) journal.write(a, b, null, SAME, now)
    }

    /** Of a work holding both, [b] taken out; false of two works. */
    private fun takeOut(a: ItemKey, b: ItemKey, now: Instant): Boolean {
        if (works.of(a, now) != works.of(b, now)) return false
        works.membership.takeOut(b, now)
        return true
    }

    /** The two items said to be different works; [b] taken out of a work holding both. */
    fun distinct(a: ItemKey, b: ItemKey, now: Instant) = say(a, b, DIFFERENT, now)

    /**
     * The two items said to be related works of the [kind] — one the translation of the other, one a part of the other
     * —, not one: [b] taken out of a work holding both.
     */
    fun relate(a: ItemKey, b: ItemKey, kind: String, now: Instant) = say(a, b, kind, now)

    private fun say(a: ItemKey, b: ItemKey, kind: String, now: Instant) {
        val before = word(a, b, now)
        takeOut(a, b, now)
        works.relations.say(works.of(a, now), works.of(b, now), kind, now)
        journal.write(a, b, kind, before, now)
    }

    /** The word on the works of the two taken back. */
    fun takeBack(a: ItemKey, b: ItemKey, now: Instant) {
        val before = word(a, b, now)
        works.relations.takeBack(works.of(a, now), works.of(b, now))
        journal.write(a, b, null, before, now)
    }

    /** The item removed from the library. */
    fun forget(key: ItemKey) = works.membership.forget(key)

    companion object {
        const val SAME = "SAME"
        const val DIFFERENT = "DIFFERENT"
    }
}
