package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.api.model.QueryQuality as ApiQueryQuality
import io.github.vlsergey.recommend4me.api.model.QueryRanks as ApiQueryRanks
import io.github.vlsergey.recommend4me.api.model.SearchFeatureWeight as ApiSearchFeatureWeight
import io.github.vlsergey.recommend4me.api.model.SearchQuality as ApiSearchQuality
import io.github.vlsergey.recommend4me.api.model.SearchQualitySum as ApiSearchQualitySum
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.source.Stores
import java.time.ZoneOffset
import org.springframework.stereotype.Component

/**
 * HOW WELL THE SEARCH FINDS WHAT THE USER JUDGED: every line the user said of the works found which answer it and which
 * do not ([SearchJudgmentRepository]) searched again — with what is learnt ([QueryShift], [SearchWeights]), in the
 * reranker's order, and without the reranker — and where the works judged stand. What a line is searched with is learnt
 * of the other lines alone, never of its own judgments: how it does on a line it never saw — the correction of the
 * closeness by meaning of the others' vectors, the weights of the order of the others searched with the correction
 * kept. The works not judged are counted apart, neither fitting nor not: a search that brings up works never seen is
 * not told better or worse by them.
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
        val meaning = learning.meaningLines(type)
        // The order is learnt of the lines searched with the correction kept; the reranker's alone, of none
        val judged = learning.lines(type, language, kept.shift).associate { it.found.line to it.judgedLine() }
        val rows =
            learning.lines(type, language, null).map { line ->
                val query = line.found.line
                val others =
                    SearchLearnt(
                        QueryShift.fit(meaning.filterKeys { it != query }.values.toList()),
                        SearchWeights.fit(judged.filterKeys { it != query }.values.toList()),
                    )
                val learnt = search.search(typeId, query, language, with = others)?.keys.orEmpty()
                val plain = search.search(typeId, query, language, rerank = false)?.keys.orEmpty()
                ApiQueryQuality(
                    query,
                    line.judged.count { it.value },
                    line.judged.count { !it.value },
                    ranks(learnt, line.judged, top),
                    ranks(line.found.keys, line.judged, top),
                    ranks(plain, line.judged, top),
                )
            }
        val weights = type.searchWeights.read()
        return ApiSearchQuality(
            top,
            rows,
            sum(rows.map { it.learnt }, rows),
            sum(rows.map { it.reranked }, rows),
            sum(rows.map { it.plain }, rows),
            weights?.first?.features?.map { (name, f) -> ApiSearchFeatureWeight(name, f.weight) }.orEmpty(),
            weights?.second?.atOffset(ZoneOffset.UTC),
            kept.shift?.let { learning.shiftedAt(type) }?.atOffset(ZoneOffset.UTC),
        )
    }

    private fun ranks(found: List<ItemKey>, judged: Map<ItemKey, Boolean>, top: Int): ApiQueryRanks {
        val first = found.indexOfFirst { judged[it] == true }.takeIf { it >= 0 }?.plus(1)
        val head = found.take(top)
        return ApiQueryRanks(
            relevantInTop = head.count { judged[it] == true },
            irrelevantInTop = head.count { judged[it] == false },
            unjudgedInTop = head.count { it !in judged },
            relevantFound = found.count { judged[it] == true },
            firstRelevant = first,
        )
    }

    private fun sum(ranks: List<ApiQueryRanks>, rows: List<ApiQueryQuality>) =
        ApiSearchQualitySum(
            meanReciprocalRank =
                if (ranks.isEmpty()) 0.0
                else ranks.sumOf { r -> r.firstRelevant?.let { 1.0 / it } ?: 0.0 } / ranks.size,
            relevantInTop = ranks.sumOf { it.relevantInTop },
            irrelevantInTop = ranks.sumOf { it.irrelevantInTop },
            unjudgedInTop = ranks.sumOf { it.unjudgedInTop },
            relevantFound = ranks.sumOf { it.relevantFound },
            relevant = rows.sumOf { it.relevant },
        )
}
