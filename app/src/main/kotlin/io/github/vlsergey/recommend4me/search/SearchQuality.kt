package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.api.model.QueryQuality as ApiQueryQuality
import io.github.vlsergey.recommend4me.api.model.QueryRanks as ApiQueryRanks
import io.github.vlsergey.recommend4me.api.model.SearchFeatureWeight as ApiSearchFeatureWeight
import io.github.vlsergey.recommend4me.api.model.SearchQuality as ApiSearchQuality
import io.github.vlsergey.recommend4me.api.model.SearchQualitySum as ApiSearchQualitySum
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.Stores
import java.time.ZoneOffset
import kotlin.math.log2
import org.springframework.stereotype.Component

/**
 * HOW WELL THE SEARCH FINDS WHAT THE USER JUDGED: every line the user said of the snippets shown which answer it and
 * which do not ([SearchJudgmentRepository]) searched again — with what is learnt ([QueryShift], [SearchWeights]), in
 * the reranker's order, and without the reranker — and where the works shown with a snippet judged stand; and how the
 * closeness by meaning, corrected and the encoder's own, orders the line's snippets judged. What a line is searched
 * with is learnt of the other lines alone, never of its own judgments: how it does on a line it never saw — the
 * correction of the closeness by meaning of the others' snippets, the weights of the order of the others searched with
 * the correction kept. A work shown with a snippet not judged is counted apart, neither fitting nor not: a search that
 * brings up what was never seen is not told better or worse by it.
 */
@Component
class SearchQuality(private val stores: Stores, private val search: Search, private val learning: SearchLearning) {

    /**
     * The measure of the type's search over the first [top] works found, the lines read as the [language]; null for no
     * such type.
     */
    fun measure(typeId: String, top: Int, language: String): ApiSearchQuality? {
        val type = stores.type(typeId) ?: return null
        val kept = search.kept(type)
        val meaning = learning.snippetLines(type)
        // The order is learnt of the lines searched with the correction kept; the reranker's alone, of none
        val judged = learning.lines(type, language, kept.shift).associate { it.found.line to it.judgedLine() }
        val rows =
            type.searchJudgments.all().toSortedMap().mapNotNull { (query, snippets) ->
                val reranked = search.search(typeId, query, language, with = SearchLearnt(null, null))
                val shift = QueryShift.fit(meaning.filterKeys { it != query }.values.toList())
                val others = SearchLearnt(shift, SearchWeights.fit(judged.filterKeys { it != query }.values.toList()))
                val learnt = search.search(typeId, query, language, with = others)
                val plain = search.search(typeId, query, language, rerank = false)
                fun ranksOf(found: SearchResult?) = found?.let {
                    ranks(it.keys, search.judgedShown(typeId, it, snippets), top)
                }
                reranked?.let {
                    val fits = snippets.values.flatMap { it.values }
                    val asReranked = ranksOf(it)!!
                    ApiQueryQuality(
                        query = query,
                        relevant = fits.count { f -> f == SearchFit.FITS },
                        partly = fits.count { f -> f == SearchFit.PARTLY },
                        irrelevant = fits.count { f -> f == SearchFit.NOT },
                        learnt = ranksOf(learnt) ?: asReranked,
                        reranked = asReranked,
                        plain = ranksOf(plain) ?: asReranked,
                        snippetsLearnt = meaning[query]?.let { l -> snippetsOrdered(l, shift) },
                        snippetsPlain = meaning[query]?.let { l -> snippetsOrdered(l, null) },
                    )
                }
            }
        val weights = type.searchWeights.read()
        return ApiSearchQuality(
            top = top,
            queries = rows,
            learnt = sum(rows.map { it.learnt }, rows),
            reranked = sum(rows.map { it.reranked }, rows),
            plain = sum(rows.map { it.plain }, rows),
            weights = weights?.first?.features?.map { (name, f) -> ApiSearchFeatureWeight(name, f.weight) }.orEmpty(),
            fittedAt = weights?.second?.atOffset(ZoneOffset.UTC),
            shiftedAt = kept.shift?.let { learning.shiftedAt(type) }?.atOffset(ZoneOffset.UTC),
            snippetsLearnt = rows.mapNotNull { it.snippetsLearnt }.takeIf { it.isNotEmpty() }?.average(),
            snippetsPlain = rows.mapNotNull { it.snippetsPlain }.takeIf { it.isNotEmpty() }?.average(),
        )
    }

    /**
     * How well the closeness by meaning — the line's vector moved by [shift], or the encoder's own of none — orders the
     * snippets judged of the line [l], 0..1 ([ndcg] of every one of them); null of a line no snippet answers.
     */
    private fun snippetsOrdered(l: SnippetLine, shift: QueryShift?): Double? {
        val q = shift?.of(l.query) ?: l.query
        val ordered = l.snippets.sortedByDescending { (p, _) -> p.indices.sumOf { q[it].toDouble() * p[it] } }
        return ndcgOf(ordered.map { it.second.gain }, l.snippets.map { it.second.gain }, l.snippets.size)
    }

    private fun ranks(found: List<ItemKey>, judged: Map<ItemKey, SearchFit>, top: Int): ApiQueryRanks {
        val first = found.indexOfFirst { judged[it] == SearchFit.FITS }.takeIf { it >= 0 }?.plus(1)
        val head = found.take(top)
        return ApiQueryRanks(
            relevantInTop = head.count { judged[it] == SearchFit.FITS },
            partlyInTop = head.count { judged[it] == SearchFit.PARTLY },
            irrelevantInTop = head.count { judged[it] == SearchFit.NOT },
            unjudgedInTop = head.count { it !in judged },
            relevantFound = found.count { judged[it] == SearchFit.FITS },
            firstRelevant = first,
            ndcg = ndcg(found, judged, top),
        )
    }

    private fun sum(ranks: List<ApiQueryRanks>, rows: List<ApiQueryQuality>) =
        ApiSearchQualitySum(
            meanReciprocalRank =
                if (ranks.isEmpty()) 0.0
                else ranks.sumOf { r -> r.firstRelevant?.let { 1.0 / it } ?: 0.0 } / ranks.size,
            meanNdcg = ranks.mapNotNull { it.ndcg }.takeIf { it.isNotEmpty() }?.average(),
            relevantInTop = ranks.sumOf { it.relevantInTop },
            partlyInTop = ranks.sumOf { it.partlyInTop },
            irrelevantInTop = ranks.sumOf { it.irrelevantInTop },
            unjudgedInTop = ranks.sumOf { it.unjudgedInTop },
            relevantFound = ranks.sumOf { it.relevantFound },
            relevant = rows.sumOf { it.relevant },
            partly = rows.sumOf { it.partly },
        )

    companion object {
        /**
         * How well the first [top] of the works [judged] are ordered as [found], 0..1: of the works judged, in the
         * order found — those not judged left out, neither for nor against — the first [top], each bringing its gain
         * ([SearchFit.gain]) the less the lower it stands, 1 / log2(place + 1), against the same of every work judged
         * in the best order, those not found among them. Null of a line no work answers at all.
         */
        fun ndcg(found: List<ItemKey>, judged: Map<ItemKey, SearchFit>, top: Int): Double? =
            ndcgOf(found.mapNotNull { judged[it]?.gain }, judged.values.map { it.gain }, top)

        /**
         * The gains [ordered] as they stand, the first [top], against the best order of [all] the gains; null when none
         * gains anything.
         */
        fun ndcgOf(ordered: List<Double>, all: List<Double>, top: Int): Double? {
            fun dcg(gains: List<Double>) = gains.take(top).withIndex().sumOf { (n, g) -> g / log2(n + 2.0) }
            val ideal = dcg(all.sortedDescending())
            return if (ideal <= 0) null else dcg(ordered) / ideal
        }
    }
}
