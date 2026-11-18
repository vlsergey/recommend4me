package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.item.FacetChoice
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemOrder
import io.github.vlsergey.recommend4me.item.ItemView
import io.github.vlsergey.recommend4me.item.NumberRange
import io.github.vlsergey.recommend4me.model.FeatureNames
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.WorkClusters
import org.apache.lucene.document.DoubleField
import org.apache.lucene.document.NumericDocValuesField
import org.apache.lucene.index.Term
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.MatchAllDocsQuery
import org.apache.lucene.search.MatchNoDocsQuery
import org.apache.lucene.search.Query
import org.apache.lucene.search.Sort
import org.apache.lucene.search.SortField
import org.apache.lucene.search.SortedNumericSortField
import org.apache.lucene.search.TermInSetQuery
import org.apache.lucene.search.TermQuery
import org.apache.lucene.util.BytesRef

/**
 * What the list is asked for: the [view] and the [order] — by the number [sortNumber] of an order by a number — the
 * works a search found in the order it found them ([relevance]) when searched, the [choices] of the filter facets, the
 * [sources], the [ranges] of the numbers, and of an order by the surprise how far the model that had not seen a graded
 * work puts it from its grade ([surprise], by item).
 */
class ListAsked(
    val view: ItemView,
    val order: ItemOrder,
    val sortNumber: String?,
    val relevance: Map<ItemKey, Int>?,
    val choices: List<FacetChoice>,
    val sources: Set<String>?,
    val ranges: List<NumberRange>,
    val surprise: Map<ItemKey, Double> = emptyMap(),
)

/**
 * A page of the list: how many works it has in all, the items the works of the page are shown with, and the works of
 * several items among them ([clusters]) as the cards read them.
 */
class ListPage(val total: Int, val keys: List<ItemKey>, val clusters: WorkClusters)

/**
 * THE LIST ASKED OF ITS INDEX ([ListIndex]): the filters a query, a work of several items one row — of those that pass,
 * the one the work is shown with first ([ListRow.rank]), of a search the one it found best — sorted, counted and cut
 * into a page, no database read. A work of several items is rare: every item is sorted as it is and the works of
 * several collapsed after ([ListWorks]), no group looked up for every item.
 */
internal object ListQuery {

    /** The page of [limit] works from [offset] of what is [asked], of the content type [type]. */
    fun page(index: ListIndex, type: TypeStore, asked: ListAsked, offset: Int, limit: Int): ListPage =
        index.searching { s ->
            val query = query(s, type, asked)
            val (total, keys) =
                when {
                    asked.relevance != null -> byRelevance(s, query, asked.relevance, offset, limit)
                    asked.order == ItemOrder.SURPRISE -> bySurprise(s, query, asked.surprise, offset, limit)
                    else -> sorted(s, query, sort(asked), offset, limit)
                }
            ListPage(total, keys, ListWorks.clustersOf(s, keys))
        }

    /**
     * The works of [query] in the order of [sort] from [offset], [limit] of them, each shown with its first item that
     * passes; and how many works there are. Every item sorted, so many more than the page as there are items of works
     * of several that pass — each of them may stand for a work already shown.
     */
    private fun sorted(s: IndexSearcher, query: Query, sort: Sort, offset: Int, limit: Int): Pair<Int, List<ItemKey>> {
        val linked = ListWorks.linkedOf(s, query)
        val shown = linked.values.groupBy { it.work }.mapValues { (_, items) -> items.minBy { it.rank }.key }
        val total = s.count(query) - linked.values.groupBy { it.work }.values.sumOf { it.size - 1 }
        val start = offset.coerceAtLeast(0)
        val wanted = start + limit.coerceAtLeast(1) + linked.size
        val top = s.search(query, wanted.coerceAtMost(s.indexReader.maxDoc().coerceAtLeast(1)), sort)
        val stored = s.storedFields()
        val works = LinkedHashSet<ItemKey>()
        top.scoreDocs.forEach { d ->
            val key = ItemKey.parse(stored.document(d.doc, KEY_ONLY).get(ListDocuments.KEY))
            works += linked[key]?.work ?: key
        }
        val page = works.drop(start).take(limit.coerceAtLeast(1)).map { shown[it] ?: it }
        return total to page
    }

    /** Of a search, the works it found that pass in the order it found them, each shown with the item it found best. */
    private fun byRelevance(
        s: IndexSearcher,
        query: Query,
        relevance: Map<ItemKey, Int>,
        offset: Int,
        limit: Int,
    ): Pair<Int, List<ItemKey>> {
        if (relevance.isEmpty()) return 0 to emptyList()
        val found =
            ListWorks.found(
                s,
                with(query, TermInSetQuery(ListDocuments.KEY, relevance.keys.map { BytesRef(it.toString()) })),
            )
        val best =
            found
                .groupBy { it.work }
                .values
                .map { items -> items.minBy { relevance.getValue(it.key) }.key }
                .sortedBy { relevance.getValue(it) }
        return best.size to best.drop(offset.coerceAtLeast(0)).take(limit.coerceAtLeast(1))
    }

    /**
     * By the surprise: the graded works first, the most surprising first — a graded work the model put where its grade
     * stands after them, the newest update first —, then every other, the newest update first.
     */
    private fun bySurprise(
        s: IndexSearcher,
        query: Query,
        surprise: Map<ItemKey, Double>,
        offset: Int,
        limit: Int,
    ): Pair<Int, List<ItemKey>> {
        val gradedQuery = with(query, NumericDocValuesField.newSlowExactQuery(ListDocuments.GRADED, 1L))
        val graded = s.count(gradedQuery)
        val first =
            if (graded == 0) emptyList()
            else
                sorted(s, gradedQuery, BY_UPDATE, 0, graded).second.sortedByDescending {
                    surprise[it] ?: Double.NEGATIVE_INFINITY
                }
        val start = offset.coerceAtLeast(0)
        val fromFirst = first.drop(start).take(limit.coerceAtLeast(1))
        val (others, rest) =
            sorted(
                s,
                with(query, NumericDocValuesField.newSlowExactQuery(ListDocuments.GRADED, 0L)),
                BY_UPDATE,
                (start - first.size).coerceAtLeast(0),
                (limit - fromFirst.size).coerceAtLeast(1),
            )
        val keys = fromFirst + if (fromFirst.size < limit) rest else emptyList()
        return first.size + others to keys
    }

    /** The [query] narrowed by the [filter]. */
    private fun with(query: Query, filter: Query): Query =
        BooleanQuery.Builder().add(query, BooleanClause.Occur.FILTER).add(filter, BooleanClause.Occur.FILTER).build()

    /** The order of the list asked, the newest update first among the equal. */
    private fun sort(asked: ListAsked): Sort =
        when (asked.order) {
            ItemOrder.SCORE -> Sort(down(ListDocuments.EXPECTED), down(ListDocuments.PREDICTION), UPDATED_DOWN)
            ItemOrder.INTEREST -> Sort(down(ListDocuments.INTEREST), down(ListDocuments.PREDICTION), UPDATED_DOWN)
            ItemOrder.GRADE -> Sort(down(ListDocuments.PREDICTION), down(ListDocuments.INTEREST), UPDATED_DOWN)
            ItemOrder.ADDED -> Sort(SortField(ListDocuments.ADDED, SortField.Type.LONG, true), UPDATED_DOWN)
            ItemOrder.NUMBER ->
                asked.sortNumber?.let { number ->
                    Sort(
                        SortedNumericSortField(ListDocuments.numberField(number), SortField.Type.DOUBLE, true).apply {
                            missingValue = Double.NEGATIVE_INFINITY
                        },
                        UPDATED_DOWN,
                    )
                } ?: BY_UPDATE
            ItemOrder.UPDATED,
            ItemOrder.SURPRISE -> BY_UPDATE
        }

    /** A value of the list's, the greatest first. */
    private fun down(field: String) = SortField(field, SortField.Type.DOUBLE, true)

    /**
     * The filters of what is [asked] as one query: the view, the sources, every filter facet and every range of a
     * number — a source that has no such facet or number not limited by it.
     */
    private fun query(s: IndexSearcher, type: TypeStore, asked: ListAsked): Query {
        val q = BooleanQuery.Builder().add(MatchAllDocsQuery(), BooleanClause.Occur.MUST)
        when (asked.view) {
            ItemView.RATED -> q.add(NumericDocValuesField.newSlowExactQuery(ListDocuments.GRADED, 1L), FILTER)
            ItemView.UNRATED -> q.add(NumericDocValuesField.newSlowExactQuery(ListDocuments.GRADED, 0L), FILTER)
            ItemView.ALL -> Unit
        }
        asked.sources?.let { q.add(TermInSetQuery(ListDocuments.SOURCE, it.map(::BytesRef)), FILTER) }
        asked.choices.forEach { choice ->
            val having =
                type.sources
                    .filter { st ->
                        st.schema.facets.any { it.filter && FeatureNames.facetId(st.source, it) == choice.facet }
                    }
                    .map { it.id }
            if (having.isNotEmpty()) q.add(orElseOf(type, having, facetQuery(s, choice)), FILTER)
        }
        asked.ranges.forEach { range ->
            val having =
                type.sources
                    .filter { st -> st.schema.numbers.any { FeatureNames.numberId(st.source, it) == range.numberId } }
                    .map { it.id }
            val within =
                DoubleField.newRangeQuery(
                    ListDocuments.numberField(range.numberId),
                    range.from ?: Double.NEGATIVE_INFINITY,
                    range.to ?: Double.POSITIVE_INFINITY,
                )
            if (having.isNotEmpty()) q.add(orElseOf(type, having, within), FILTER)
        }
        return q.build()
    }

    /** The [query] of the sources [having] what it asks of, every item of another source passing it. */
    private fun orElseOf(type: TypeStore, having: List<String>, query: Query): Query {
        val others = type.sources.map { it.id }.filter { it !in having }
        if (others.isEmpty()) return query
        return BooleanQuery.Builder()
            .add(query, BooleanClause.Occur.SHOULD)
            .add(TermInSetQuery(ListDocuments.SOURCE, others.map(::BytesRef)), BooleanClause.Occur.SHOULD)
            .build()
    }

    /**
     * The items a facet's [choice] lets ([FacetChoice.passes]): of the values of its scale's range, of those shown, or
     * of any value not hidden — any value some item has but the hidden —; an item of no value unless
     * [FacetChoice.withoutHidden].
     */
    private fun facetQuery(s: IndexSearcher, choice: FacetChoice): Query {
        val field = ListDocuments.facetField(choice.facet)
        val values =
            choice.range?.map(::BytesRef)
                ?: choice.shown?.map(::BytesRef)
                ?: ListFacetValues.valuesOf(s, choice.facet).filter { it.utf8ToString() !in choice.hidden }
        val having = if (values.isEmpty()) MatchNoDocsQuery() else TermInSetQuery(field, values)
        if (choice.withoutHidden) return having
        return BooleanQuery.Builder()
            .add(having, BooleanClause.Occur.SHOULD)
            .add(TermQuery(Term(field, ListDocuments.NONE)), BooleanClause.Occur.SHOULD)
            .build()
    }

    private val FILTER = BooleanClause.Occur.FILTER

    private val KEY_ONLY = setOf(ListDocuments.KEY)

    private val UPDATED_DOWN = SortField(ListDocuments.UPDATED, SortField.Type.LONG, true)

    private val BY_UPDATE = Sort(UPDATED_DOWN)
}
