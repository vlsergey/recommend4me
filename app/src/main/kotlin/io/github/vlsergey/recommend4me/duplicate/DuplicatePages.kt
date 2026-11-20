package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.model.DuplicatePage
import io.github.vlsergey.recommend4me.api.model.DuplicatePair
import io.github.vlsergey.recommend4me.api.model.DuplicateSort
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.series.SeriesDifferences
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.worklist.ListIndexes
import java.time.Instant
import java.time.ZoneOffset
import org.springframework.stereotype.Component

/** The pairs, or the words of the journal, a page holds at most. */
internal const val MAX_PAGE = 200

/**
 * Which pairs a page holds and in what order: by their answer — [open], those not answered on; those answered with the
 * words [answers] —, of them only those of the same authors ([sameAuthors]) or of the same title ([sameTitle]) when
 * asked ([WholeMatch]), by the [sort].
 */
class PairChoice(
    val open: Boolean,
    val answers: Set<DuplicateAnswer> = emptySet(),
    val sort: DuplicateSort = DuplicateSort.FORMULA,
    val sameAuthors: Boolean = false,
    val sameTitle: Boolean = false,
)

/**
 * THE PAIRS AS THEY ARE LOOKED THROUGH: a page of the pairs of a type in the order the user chose ([page]), and the
 * pairs of one work ([ofItem]).
 */
@Component
class DuplicatePages(
    private val stores: Stores,
    private val duplicates: Duplicates,
    private val views: DuplicateViews,
    private val models: PairModels,
    private val plugins: Plugins,
    private val window: PairWindow,
    private val indexes: ListIndexes,
) {
    /**
     * A page of the pairs of the type the [choice] holds by their answer: those not answered on — neither linked (by
     * any way, through other works too) nor said to be different or related —, those answered with the words it names,
     * as the search or the last answer left the words with them. The first by the chance the pair is one work, by the
     * model of the answers, times what the [sort] says of the work the pair was found for
     * ([DuplicateCandidate.foundFor]; of both, the larger): its score by the formula of the list (the prediction times
     * the chance of interest: a work the user would read first), its chance of interest, its predicted grade, or
     * nothing — a work of a high score, the likeliest of many works of another site, does not lift every pair of them;
     * equal, the likelier pair first. Or times the pair's own mean size ([DuplicateSort.SIZE]): the long works first.
     * Or by the chance the pair is one series of works ([DuplicateSort.SERIES]), alone or times the pair's size
     * ([DuplicateSort.SERIES_SIZE]: the long works of one series first). Or, of the pairs in doubt
     * ([DuplicateSort.UNCERTAIN]), those nearest where the user's answers change ([PairDoubt]): what an answer teaches
     * the model most; or the same of the chance of one series ([DuplicateSort.UNCERTAIN_SERIES]). Chosen in the
     * database by the keys the search wrote with the pairs as things stood then ([PairScore], [DuplicateChoice]), the
     * page's pairs alone read: the chances shown of the models as they are. Null of no such type.
     */
    fun page(typeId: String, choice: PairChoice, offset: Int, limit: Int): DuplicatePage? {
        val type = stores.type(typeId) ?: return null
        val search = type.duplicates.search()
        val chances = models.of(type)
        val (total, chosen) = type.duplicates.choice.page(choice, offset.coerceAtLeast(0), limit.coerceIn(1, MAX_PAGE))
        val layers = type.duplicates.reads.ofPairs(chosen.map { it.pair }).associateBy { it.pair }
        val said =
            Said(
                chosen.mapNotNull { r -> r.answer?.let { r.pair to it } }.toMap(),
                chosen.filter { it.derived }.mapTo(HashSet()) { it.pair },
            )
        val ranked = chosen.map { r ->
            val c = layers[r.pair]
            val chance = c?.let { chances?.same(it) }
            // What the page is ordered by, as the search wrote it: the sort's key, not the chance alone
            Ranked(r.pair, c, chance, c?.let { chances?.series(it) }, r.priority ?: 0.0)
        }
        return DuplicatePage(
            total = total,
            pairs = views.pairs(type, said, ranked),
            pending = duplicates.isSearching(type),
            searchedAt = search?.madeAt?.atOffset(ZoneOffset.UTC),
            works = search?.works,
            learnt = chances?.learnt == true,
        )
    }

    /**
     * The two works as the screen shows a pair: how alike they are — as the last search kept them, or of a pair it did
     * not keep worked out now the same way ([LoosePairs]) — and the word said of them; null of no such type, or a work
     * no longer there.
     */
    fun pair(typeId: String, a: ItemKey, b: ItemKey): DuplicatePair? {
        val type = stores.type(typeId) ?: return null
        val pair = type.links.ordered(a, b)
        val kept =
            type.duplicates.find(pair.first, pair.second)
                ?: plugins.textEncoder()?.id?.let {
                    LoosePairs.candidate(type, it, pair.first, pair.second, indexes.of(type.id))
                }
        val chances = models.of(type)
        val word =
            type.links.word(a, b, Instant.now())?.let { w -> DuplicateAnswer.entries.firstOrNull { it.name == w } }
        val chance = kept?.let { chances?.same(it) }
        val ranked = Ranked(pair, kept, chance, kept?.let { chances?.series(it) }, chance ?: 0.0)
        // A word "different" on two works of one series not heeded, as of every pair ([PairAnswers.said])
        val unheeded =
            word == DuplicateAnswer.DIFFERENT && SeriesDifferences.ofOneSeries(type, listOf(pair)).isNotEmpty()
        val said =
            if (unheeded) Said(mapOf(pair to DuplicateAnswer.SERIES), setOf(pair))
            else Said(listOfNotNull(word?.let { pair to it }).toMap(), emptySet())
        return window.of(type, ranked, said)
    }

    fun ofItem(key: ItemKey): List<DuplicatePair> {
        val type = stores.typeOf(key.source)
        val own = (type.links.groupsOf(listOf(key)).firstOrNull() ?: listOf(key)).toSet()
        val chances = models.of(type)
        val found =
            type.duplicates.reads
                .ofItems(own)
                .filter { (it.pair.first in own) != (it.pair.second in own) }
                .map { c -> chances?.same(c).let { Ranked(c.pair, c, it, chances?.series(c), it ?: 0.0) } }
                .sortedByDescending { it.priority }
                // Found by several items of the work, the other is one: the likeliest pair of them, an answer going
                // across the work
                .distinctBy { if (it.pair.first in own) it.pair.second else it.pair.first }
        return views.pairs(type, type.duplicates.reads.wordsOf(found.map { it.pair }), found)
    }
}
