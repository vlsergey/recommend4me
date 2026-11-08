package io.github.vlsergey.recommend4me.correction

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.series.SeriesGroups
import io.github.vlsergey.recommend4me.work.PairWord
import io.github.vlsergey.recommend4me.work.WorkPairJournal
import io.github.vlsergey.recommend4me.work.WorkRelations
import io.github.vlsergey.recommend4me.work.WorkRepository
import java.time.Instant

/**
 * THE WORD ON PAIRS OF ITEMS OF A CONTENT TYPE AS IT STANDS: one work ([TypeLinkRepository.SAME]), different works
 * ([TypeLinkRepository.DIFFERENT]), related works of a kind, of one series by the user's word
 * ([TypeLinkRepository.SERIES]) — read of their normalized works — and the journal of every word said and taken back.
 */
interface PairWords {
    /** The word on the two items now: one work ([TypeLinkRepository.SAME]), the word on their two works, or none. */
    fun word(a: ItemKey, b: ItemKey, now: Instant): String?

    /** The word on every pair of items now, as [word] tells it, read in one go: of an item bound to no work, none. */
    fun words(pairs: Collection<Pair<ItemKey, ItemKey>>): Map<Pair<ItemKey, ItemKey>, String?>

    /** Every word on two items of the type, the newest first. */
    fun journal(): List<PairWord>

    /** Every pair of items of different works said to be different works, the smaller key first. */
    fun distincts(): Set<Pair<ItemKey, ItemKey>>

    /**
     * Every pair of items of works said to be related, the smaller key first, with the kind: of one series, the works
     * the user said to be of one series.
     */
    fun relations(): Map<Pair<ItemKey, ItemKey>, String>

    /** The two keys, the smaller first: the order a pair is kept in. */
    fun ordered(a: ItemKey, b: ItemKey): Pair<ItemKey, ItemKey>
}

/** The [PairWords] of the items of the [sources] of a type, read of their normalized works and the [journal]. */
internal class WorkPairWords(
    private val works: WorkRepository,
    private val journal: WorkPairJournal,
    /** Every source of the type. */
    private val sources: Collection<String>,
    private val series: SeriesGroups,
) : PairWords {

    override fun word(a: ItemKey, b: ItemKey, now: Instant): String? {
        val x = works.of(a, now)
        val y = works.of(b, now)
        return when {
            x == y -> TypeLinkRepository.SAME
            series.oneGroup(x, y) -> TypeLinkRepository.SERIES
            else -> works.relations.word(x, y)
        }
    }

    override fun words(pairs: Collection<Pair<ItemKey, ItemKey>>): Map<Pair<ItemKey, ItemKey>, String?> {
        val bound =
            pairs
                .flatMap { it.toList() }
                .distinct()
                .groupBy { it.source }
                .flatMap { (source, keys) ->
                    works.ofItems(source, keys.map { it.id }).map { (id, work) -> ItemKey(source, id) to work }
                }
                .toMap()
        val words = works.relations.words()
        val groups = series.groupsOf(bound.values)
        return pairs.associateWith { (a, b) ->
            val x = bound[a] ?: return@associateWith null
            val y = bound[b] ?: return@associateWith null
            when {
                x == y -> TypeLinkRepository.SAME
                groups[x] != null && groups[x] == groups[y] -> TypeLinkRepository.SERIES
                else -> words[WorkRelations.pair(x, y)]
            }
        }
    }

    override fun journal(): List<PairWord> = journal.all(sources)

    override fun distincts(): Set<Pair<ItemKey, ItemKey>> =
        said().filterValues { it == TypeLinkRepository.DIFFERENT }.keys

    override fun relations(): Map<Pair<ItemKey, ItemKey>, String> =
        said().filterValues { it != TypeLinkRepository.DIFFERENT }

    /**
     * Every word on two works as said of every pair of their items: of the works the user said to be one series, of
     * every two of them.
     */
    private fun said(): Map<Pair<ItemKey, ItemKey>, String> {
        val words = HashMap(works.relations.words())
        series.all().values.forEach { group ->
            val all = group.sorted()
            all.forEachIndexed { k, x -> all.drop(k + 1).forEach { y -> words[x to y] = TypeLinkRepository.SERIES } }
        }
        val items = works.itemsOf(words.keys.flatMap { it.toList() }, sources)
        val out = HashMap<Pair<ItemKey, ItemKey>, String>()
        words.forEach { (pair, kind) ->
            for (x in items[pair.first].orEmpty()) for (y in items[pair.second].orEmpty()) out[ordered(x, y)] = kind
        }
        return out
    }

    override fun ordered(a: ItemKey, b: ItemKey): Pair<ItemKey, ItemKey> =
        if (a.toString() < b.toString()) a to b else b to a
}
