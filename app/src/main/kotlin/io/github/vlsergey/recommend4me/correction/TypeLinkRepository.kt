package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.series.SeriesGroups
import io.github.vlsergey.recommend4me.work.WorkPairJournal
import io.github.vlsergey.recommend4me.work.WorkRepository
import java.time.Instant

/**
 * Which items of the sources of one content type are one work, and the user's word on those that are not — different
 * works, related of another kind (a translation, a part), of one series ([SeriesGroups]) — by the items, kept by their
 * normalized works ([WorkRepository]): a word on two items is a word on their works. Every word said and taken back is
 * written to the [journal], with the word the pair had before; the word as it stands is read as [PairWords] read it.
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
    /** The works the user said to be one series. */
    private val series: SeriesGroups,
) : PairWords by WorkPairWords(works, journal, sources, series) {

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

    /**
     * The two items said to be different works; [b] taken out of a work holding both. Of two works the user said to be
     * of one series, both taken out of it first ([apart]).
     */
    fun distinct(a: ItemKey, b: ItemKey, now: Instant) {
        apart(a, b, now)
        say(a, b, DIFFERENT, now)
    }

    /**
     * The two items said to be related works of the [kind] — one the translation of the other, one a part of the other
     * —, not one: [b] taken out of a work holding both. Of one series ([SERIES]): their two works put in one series
     * ([SeriesGroups.together]), a word on them taken back.
     */
    fun relate(a: ItemKey, b: ItemKey, kind: String, now: Instant) {
        if (kind != SERIES) return say(a, b, kind, now)
        val before = word(a, b, now)
        takeOut(a, b, now)
        val x = works.of(a, now)
        val y = works.of(b, now)
        works.relations.takeBack(x, y)
        series.together(x, y, now)
        journal.write(a, b, SERIES, before, now)
    }

    private fun say(a: ItemKey, b: ItemKey, kind: String, now: Instant) {
        val before = word(a, b, now)
        takeOut(a, b, now)
        works.relations.say(works.of(a, now), works.of(b, now), kind, now)
        journal.write(a, b, kind, before, now)
    }

    /**
     * The word on the works of the two taken back: of two works the user said to be of one series, both taken out of it
     * ([apart]). Nothing of two works with no word.
     */
    fun takeBack(a: ItemKey, b: ItemKey, now: Instant) {
        if (apart(a, b, now)) return
        val before = word(a, b, now) ?: return
        works.relations.takeBack(works.of(a, now), works.of(b, now))
        journal.write(a, b, null, before, now)
    }

    /**
     * Of two works the user said to be of one series, both taken out of it ([SeriesGroups.leave]), in the journal;
     * false of two works not so said, nothing taken back. Of a series of the sites the user's word takes nothing: the
     * sites say it.
     */
    private fun apart(a: ItemKey, b: ItemKey, now: Instant): Boolean {
        val x = works.of(a, now)
        val y = works.of(b, now)
        if (x == y || !series.oneGroup(x, y)) return false
        series.leave(listOf(x, y))
        journal.write(a, b, null, SERIES, now)
        return true
    }

    /** The item removed from the library. */
    fun forget(key: ItemKey) = works.membership.forget(key)

    companion object {
        const val SAME = "SAME"
        const val DIFFERENT = "DIFFERENT"

        /** Two works of one series: the user's word puts them in one ([SeriesGroups]). */
        const val SERIES = "SERIES"
    }
}
