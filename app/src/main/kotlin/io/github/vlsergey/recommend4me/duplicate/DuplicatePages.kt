package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.model.DuplicatePage
import io.github.vlsergey.recommend4me.api.model.DuplicatePair
import io.github.vlsergey.recommend4me.api.model.DuplicateSort
import io.github.vlsergey.recommend4me.interest.Interest
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.size.WorkSizes
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.Works
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
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
) {
    /** Whether a pair of the [answer] (none: not answered on) is among the chosen. */
    fun holds(answer: DuplicateAnswer?): Boolean = if (answer == null) open else answer in answers
}

/**
 * THE PAIRS AS THEY ARE LOOKED THROUGH: a page of the pairs of a type in the order the user chose ([page]), and the
 * pairs of one work ([ofItem]).
 */
@Component
class DuplicatePages(
    private val stores: Stores,
    private val works: Works,
    private val duplicates: Duplicates,
    private val answers: PairAnswers,
    private val views: DuplicateViews,
    private val sizes: WorkSizes,
    private val models: PairModels,
    private val plugins: Plugins,
    private val window: PairWindow,
) {
    /**
     * A page of the pairs of the type the [choice] holds by their answer: those not answered on — neither linked (by
     * any way, through other works too) nor said to be different or related —, those answered with the words it names —
     * the works linked across the sites, those said to be different or related, found by the last search or not. The
     * first by the chance the pair is one work, by the model of the answers, times what the [sort] says of the work the
     * pair was found for ([DuplicateCandidate.foundFor]; of both, the larger): its score by the formula of the list
     * (the prediction times the chance of interest: a work the user would read first), its chance of interest, its
     * predicted grade, or nothing — a work of a high score, the likeliest of many works of another site, does not lift
     * every pair of them; equal, the likelier pair first. Or times the pair's own mean size ([DuplicateSort.SIZE]): the
     * long works first. Or by the chance the pair is one series of works ([DuplicateSort.SERIES]). Or, of the pairs in
     * doubt ([DuplicateSort.UNCERTAIN]), those nearest where the user's answers change ([PairChances.boundary]): what
     * an answer teaches the model most; or the same of the chance of one series ([DuplicateSort.UNCERTAIN_SERIES]).
     * Every pair read for the request and let go. Null of no such type.
     */
    fun page(typeId: String, choice: PairChoice, offset: Int, limit: Int): DuplicatePage? {
        val type = stores.type(typeId) ?: return null
        val sort = choice.sort
        val said = answers.said(type)
        val search = type.duplicates.search()
        val chances = models.of(type)
        val kept = type.duplicates.all()
        // The size of every work of the pairs, in words: of the sort by the pair's size alone
        val words =
            if (sort != DuplicateSort.SIZE) emptyMap()
            else wordsOf(type, (kept.map { it.pair } + said.keys).flatMap { listOf(it.first, it.second) }.toSet())
        val boundary = boundaryOf(sort, chances, kept, said)
        val ranking = Ranking(sort, chances, worthOf(type, sort), words, boundary)
        val byPair = kept.associateBy { it.pair }
        val open = if (choice.holds(null)) kept.filter { it.pair !in said }.map { it.pair } else emptyList()
        val chosen = wholly(type, choice, open + said.filterValues(choice::holds).keys)
        val rows = chosen.map { ranking.ranked(it, byPair[it]) }
        val sorted =
            rows.sortedWith(
                compareByDescending<Ranked> { it.priority }
                    .thenByDescending { it.chance ?: -1.0 }
                    .thenBy { it.pair.first.toString() }
            )
        val page = sorted.drop(offset.coerceAtLeast(0)).take(limit.coerceIn(1, MAX_PAGE))
        return DuplicatePage(
            total = rows.size,
            pairs = views.pairs(type, said, page),
            pending = duplicates.isSearching(type),
            searchedAt = search?.madeAt?.atOffset(ZoneOffset.UTC),
            works = search?.works,
            learnt = chances?.learnt == true,
        )
    }

    /** Of the [pairs], those whose works are alike wholly as the [choice] asks: all of them, of no such asking. */
    private fun wholly(
        type: TypeStore,
        choice: PairChoice,
        pairs: List<Pair<ItemKey, ItemKey>>,
    ): List<Pair<ItemKey, ItemKey>> {
        if (!choice.sameAuthors && !choice.sameTitle) return pairs
        val match =
            WholeMatch(type, pairs.flatMap { listOf(it.first, it.second) }, choice.sameAuthors, choice.sameTitle)
        return pairs.filter { (!choice.sameAuthors || match.authors(it)) && (!choice.sameTitle || match.title(it)) }
    }

    /**
     * Where the user's answers change, of a sort of the pairs in doubt: of one work, between those said one work and
     * those said different, the translations and the parts left out as the model of one work leaves them; of one
     * series, between those said one series and those given any other word, as the model of a series learns them.
     * Nought of any other sort, or without a model.
     */
    private fun boundaryOf(
        sort: DuplicateSort,
        chances: PairChances?,
        kept: Collection<DuplicateCandidate>,
        said: Map<Pair<ItemKey, ItemKey>, DuplicateAnswer>,
    ): Double =
        when (sort) {
            DuplicateSort.UNCERTAIN ->
                chances?.boundary(kept, chances::logit) { c ->
                    said[c.pair]
                        ?.takeIf { it == DuplicateAnswer.SAME || it == DuplicateAnswer.DIFFERENT }
                        ?.let { it == DuplicateAnswer.SAME }
                }
            DuplicateSort.UNCERTAIN_SERIES ->
                chances?.boundary(kept, chances::seriesLogit) { c ->
                    said[c.pair]?.let { it == DuplicateAnswer.SERIES }
                }
            else -> null
        } ?: 0.0

    /**
     * What the [sort] says of every work: of the prediction (not below nought) and the chance of interest (the user's
     * mark over the model's; without a model of interest, as likely as not to tell nothing: 1). None of a sort that
     * says nothing of the work.
     */
    private fun worthOf(type: TypeStore, sort: DuplicateSort): Map<ItemKey, Double> {
        val worth = HashMap<ItemKey, Double>()
        if (sort == DuplicateSort.FORMULA || sort == DuplicateSort.INTEREST || sort == DuplicateSort.GRADE)
            type.sources.forEach { s ->
                val marks = s.interest.all()
                type.predictions.predicted(s.id).forEach { (id, p) ->
                    val grade = p.score.coerceAtLeast(0.0)
                    val interest = Interest.chance(marks[id]?.interesting, p.interest) ?: 1.0
                    worth[ItemKey(s.id, id)] = sort.worth(grade, interest)
                }
            }
        return worth
    }

    /**
     * What the last search found for the work of [key]: every pair of an item of it and an item of another work,
     * answered or not, the likeliest first.
     */
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
                ?: plugins.textEncoder()?.id?.let { LoosePairs.candidate(type, it, pair.first, pair.second) }
        val chances = models.of(type)
        val word =
            type.links.word(a, b, Instant.now())?.let { w -> DuplicateAnswer.entries.firstOrNull { it.name == w } }
        val chance = kept?.let { chances?.same(it) }
        val ranked = Ranked(pair, kept, chance, kept?.let { chances?.series(it) }, chance ?: 0.0)
        return views
            .pairs(type, Said(listOfNotNull(word?.let { pair to it }).toMap(), emptySet()), listOf(ranked))
            .firstOrNull()
            ?.let { window.of(type, it) }
    }

    fun ofItem(key: ItemKey): List<DuplicatePair> {
        val type = stores.typeOf(key.source)
        val own = works.of(type).members(key).toSet()
        val chances = models.of(type)
        val found =
            type.duplicates
                .all()
                .filter { (it.pair.first in own) != (it.pair.second in own) }
                .map { c -> chances?.same(c).let { Ranked(c.pair, c, it, chances?.series(c), it ?: 0.0) } }
                .sortedByDescending { it.priority }
                // Found by several items of the work, the other is one: the likeliest pair of them, an answer going
                // across the work
                .distinctBy { if (it.pair.first in own) it.pair.second else it.pair.first }
        return views.pairs(type, answers.said(type), found)
    }

    /**
     * The size in words of every work of [keys]: stated by its site, or worked out of its characters
     * ([WorkSizes.ofEvery]); none of a work of neither. The pairs are of nearly every work: every source's sizes read
     * whole, once.
     */
    private fun wordsOf(type: TypeStore, keys: Collection<ItemKey>): Map<ItemKey, Double> {
        val out = HashMap<ItemKey, Double>()
        keys
            .groupBy { it.source }
            .forEach { (source, own) ->
                val s = type.source(source) ?: return@forEach
                val every = sizes.ofEvery(type, s)
                own.forEach { key -> every[key.id]?.words?.let { out[key] = it.value } }
            }
        return out
    }
}

/** What the sort says of a work of the [grade] (not below nought) and the chance of [interest]: 1 of a sort of none. */
private fun DuplicateSort.worth(grade: Double, interest: Double): Double =
    when (this) {
        DuplicateSort.FORMULA -> grade * interest
        DuplicateSort.INTEREST -> interest
        DuplicateSort.GRADE -> grade
        DuplicateSort.CHANCE,
        DuplicateSort.SIZE,
        DuplicateSort.SERIES,
        DuplicateSort.UNCERTAIN,
        DuplicateSort.UNCERTAIN_SERIES -> 1.0
    }

/**
 * How the [sort] ranks a pair: by the [chances] it is one work, times what the sort says of the work it was found for
 * ([worth]), of its size in [words], or by how near it is to the [boundary] of the user's answers; or by the chance it
 * is one series. None of a pair of no model of pairs.
 */
private class Ranking(
    private val sort: DuplicateSort,
    private val chances: PairChances?,
    private val worth: Map<ItemKey, Double>,
    private val words: Map<ItemKey, Double>,
    private val boundary: Double,
) {
    /** The pair of its [layers] (none of an answered pair not kept) with what ranks it. */
    fun ranked(pair: Pair<ItemKey, ItemKey>, layers: DuplicateCandidate?): Ranked {
        val chance = layers?.let { chances?.same(it) }
        val series = layers?.let { chances?.series(it) }
        val priority =
            when (sort) {
                DuplicateSort.CHANCE -> chance ?: 0.0
                DuplicateSort.SERIES -> series ?: 0.0
                DuplicateSort.UNCERTAIN,
                DuplicateSort.UNCERTAIN_SERIES -> doubt(layers)
                DuplicateSort.SIZE -> size(pair) * (chance ?: 0.0)
                DuplicateSort.FORMULA,
                DuplicateSort.INTEREST,
                DuplicateSort.GRADE -> sought(pair, layers) * (chance ?: 0.0)
            }
        return Ranked(pair, layers, chance, series, priority)
    }

    /**
     * How near the pair of [layers] is to where the user's answers change, on the scale of the chance the sort is of:
     * one at it, less the farther; none without that chance.
     */
    private fun doubt(layers: DuplicateCandidate?): Double {
        if (layers == null || chances == null) return 0.0
        val at = if (sort == DuplicateSort.UNCERTAIN_SERIES) chances.seriesLogit(layers) else chances.logit(layers)
        return at?.let { 1 / (1 + abs(it - boundary)) } ?: 0.0
    }

    /** The mean of the two works' sizes, of those known: of a work of no size known, the other's alone. */
    private fun size(pair: Pair<ItemKey, ItemKey>): Double {
        val known = listOfNotNull(words[pair.first], words[pair.second])
        return if (known.isEmpty()) 0.0 else known.average()
    }

    /**
     * What the sort says of the work the pair was found for — of both, the larger; of neither (an answered pair not
     * kept), the larger too.
     */
    private fun sought(pair: Pair<ItemKey, ItemKey>, layers: DuplicateCandidate?): Double {
        val first = worth[pair.first] ?: 0.0
        val second = worth[pair.second] ?: 0.0
        return when (layers?.foundFor) {
            DuplicateCandidate.FIRST -> first
            DuplicateCandidate.SECOND -> second
            else -> maxOf(first, second)
        }
    }
}
