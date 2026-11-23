package io.github.vlsergey.recommend4me.worklist

import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_NUMBER
import io.github.vlsergey.recommend4me.database.worklist.tables.references.ITEM_VALUE
import io.github.vlsergey.recommend4me.item.FacetChoice
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemOrder
import io.github.vlsergey.recommend4me.item.ItemView
import io.github.vlsergey.recommend4me.item.NumberRange
import io.github.vlsergey.recommend4me.model.FeatureNames
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.work.WorkClusters
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.SortField
import org.jooq.impl.DSL

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
 * THE LIST ASKED OF ITS INDEX ([ListIndex]): the filters a condition of the rows of the items, a work of several items
 * one row — of those that pass, the one the work is shown with first ([ListRow.rank]), of a search the one it found
 * best — sorted, counted and cut into a page, no database read. A work of several items is rare: every item is sorted
 * as it is and the works of several collapsed after ([ListWorks]), no group looked up for every item.
 */
internal object ListQuery {

    /** The page of [limit] works from [offset] of what is [asked], of the content type [type]. */
    fun page(index: ListIndex, type: TypeStore, asked: ListAsked, offset: Int, limit: Int): ListPage =
        index.searching { db ->
            val where = condition(type, asked)
            val (total, keys) =
                when {
                    asked.relevance != null -> byRelevance(db, where, asked.relevance, offset, limit)
                    asked.order == ItemOrder.SURPRISE -> bySurprise(db, where, asked.surprise, offset, limit)
                    else -> sorted(db, where, asked, offset, limit)
                }
            ListPage(total, keys, ListWorks.clustersOf(db, keys))
        }

    /**
     * The works the [where] picks in the order [asked] from [offset], [limit] of them, each shown with its first item
     * that passes; and how many works there are. Every item sorted, so many more than the page as there are items of
     * works of several that pass — each of them may stand for a work already shown.
     */
    private fun sorted(
        db: DSLContext,
        where: Condition,
        asked: ListAsked,
        offset: Int,
        limit: Int,
    ): Pair<Int, List<ItemKey>> {
        val linked = ListWorks.linkedOf(db, where)
        val shown = linked.values.groupBy { it.work }.mapValues { (_, items) -> items.minBy { it.rank }.key }
        val total =
            (db.selectCount().from(ITEM).where(where).fetchOne(0, Int::class.java) ?: 0) -
                linked.values.groupBy { it.work }.values.sumOf { it.size - 1 }
        val start = offset.coerceAtLeast(0)
        val wanted = start + limit.coerceAtLeast(1) + linked.size
        val number = asked.sortNumber?.takeIf { asked.order == ItemOrder.NUMBER }
        val n = ITEM_NUMBER.`as`("sorted_by")
        val from =
            if (number == null) ITEM else ITEM.leftJoin(n).on(n.ITEM_KEY.eq(ITEM.ITEM_KEY), n.NUMBER_ID.eq(number))
        val works = LinkedHashSet<ItemKey>()
        db.select(ITEM.ITEM_KEY)
            .from(from)
            .where(where)
            .orderBy(sort(asked.order, n.CONTENT.takeIf { number != null }))
            .limit(wanted)
            .fetch {
                val key = ItemKey.parse(it.value1()!!)
                works += linked[key]?.work ?: key
            }
        val page = works.drop(start).take(limit.coerceAtLeast(1)).map { shown[it] ?: it }
        return total to page
    }

    /** Of a search, the works it found that pass in the order it found them, each shown with the item it found best. */
    private fun byRelevance(
        db: DSLContext,
        where: Condition,
        relevance: Map<ItemKey, Int>,
        offset: Int,
        limit: Int,
    ): Pair<Int, List<ItemKey>> {
        if (relevance.isEmpty()) return 0 to emptyList()
        val found =
            ListAmong.of(db, relevance.keys.map { it.toString() }) { ListWorks.found(db, where.and(ListIndex.AMONG)) }
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
        db: DSLContext,
        where: Condition,
        surprise: Map<ItemKey, Double>,
        offset: Int,
        limit: Int,
    ): Pair<Int, List<ItemKey>> {
        val byUpdate = ListAsked(ItemView.ALL, ItemOrder.UPDATED, null, null, emptyList(), null, emptyList())
        val gradedWhere = where.and(ITEM.GRADED.isTrue)
        val graded = db.selectCount().from(ITEM).where(gradedWhere).fetchOne(0, Int::class.java) ?: 0
        val first =
            if (graded == 0) emptyList()
            else
                sorted(db, gradedWhere, byUpdate, 0, graded).second.sortedByDescending {
                    surprise[it] ?: Double.NEGATIVE_INFINITY
                }
        val start = offset.coerceAtLeast(0)
        val fromFirst = first.drop(start).take(limit.coerceAtLeast(1))
        val (others, rest) =
            sorted(
                db,
                where.and(ITEM.GRADED.isFalse),
                byUpdate,
                (start - first.size).coerceAtLeast(0),
                (limit - fromFirst.size).coerceAtLeast(1),
            )
        val keys = fromFirst + if (fromFirst.size < limit) rest else emptyList()
        return first.size + others to keys
    }

    /** The order of the list asked, the newest update first among the equal, a value of none last. */
    private fun sort(order: ItemOrder, number: Field<Double?>?): List<SortField<*>> {
        val updated = ITEM.UPDATED.desc()
        val key = ITEM.ITEM_KEY.asc()
        return when (order) {
            ItemOrder.SCORE -> listOf(down(ITEM.EXPECTED), down(ITEM.PREDICTION), updated, key)
            ItemOrder.INTEREST -> listOf(down(ITEM.INTEREST), down(ITEM.PREDICTION), updated, key)
            ItemOrder.GRADE -> listOf(down(ITEM.PREDICTION), down(ITEM.INTEREST), updated, key)
            ItemOrder.ADDED -> listOf(ITEM.ADDED.desc(), updated, key)
            ItemOrder.NUMBER -> listOfNotNull(number?.let(::down), updated, key)
            ItemOrder.UPDATED,
            ItemOrder.SURPRISE -> listOf(updated, key)
        }
    }

    /** A value of the list's, the greatest first, one of none last. */
    private fun down(field: Field<Double?>) = field.desc().nullsLast()

    /**
     * The filters of what is [asked] as one condition: the view, the sources, every filter facet and every range of a
     * number — a source that has no such facet or number not limited by it.
     */
    private fun condition(type: TypeStore, asked: ListAsked): Condition {
        val all = ArrayList<Condition>()
        when (asked.view) {
            ItemView.RATED -> all += ITEM.GRADED.isTrue
            ItemView.UNRATED -> all += ITEM.GRADED.isFalse
            ItemView.ALL -> Unit
        }
        asked.sources?.let { all += ITEM.SOURCE.`in`(it) }
        asked.choices.forEach { choice ->
            val having =
                type.sources
                    .filter { st ->
                        st.schema.facets.any { it.filter && FeatureNames.facetId(st.source, it) == choice.facet }
                    }
                    .map { it.id }
            if (having.isNotEmpty()) all += orElseOf(type, having, facetCondition(choice))
        }
        asked.ranges.forEach { range ->
            val having =
                type.sources
                    .filter { st -> st.schema.numbers.any { FeatureNames.numberId(st.source, it) == range.numberId } }
                    .map { it.id }
            val n = ITEM_NUMBER
            val within =
                ITEM.ITEM_KEY.`in`(
                    DSL.select(n.ITEM_KEY)
                        .from(n)
                        .where(
                            n.NUMBER_ID.eq(range.numberId),
                            n.CONTENT.between(
                                range.from ?: Double.NEGATIVE_INFINITY,
                                range.to ?: Double.POSITIVE_INFINITY,
                            ),
                        )
                )
            if (having.isNotEmpty()) all += orElseOf(type, having, within)
        }
        return DSL.and(all)
    }

    /** The [condition] of the sources [having] what it asks of, every item of another source passing it. */
    private fun orElseOf(type: TypeStore, having: List<String>, condition: Condition): Condition {
        val others = type.sources.map { it.id }.filter { it !in having }
        return if (others.isEmpty()) condition else condition.or(ITEM.SOURCE.`in`(others))
    }

    /**
     * The items a facet's [choice] lets ([FacetChoice.passes]): of the values of its scale's range, of those shown, or
     * of any value not hidden; an item of no value unless [FacetChoice.withoutHidden].
     */
    private fun facetCondition(choice: FacetChoice): Condition {
        val v = ITEM_VALUE
        val values = choice.range ?: choice.shown
        val having =
            when {
                values == null ->
                    ITEM.ITEM_KEY.`in`(
                        DSL.select(v.ITEM_KEY).from(v).where(v.FACET.eq(choice.facet), v.VALUE_KEY.notIn(choice.hidden))
                    )
                values.isEmpty() -> DSL.falseCondition()
                else ->
                    ITEM.ITEM_KEY.`in`(
                        DSL.select(v.ITEM_KEY).from(v).where(v.FACET.eq(choice.facet), v.VALUE_KEY.`in`(values))
                    )
            }
        if (choice.withoutHidden) return having
        return having.or(ITEM.ITEM_KEY.notIn(DSL.select(v.ITEM_KEY).from(v).where(v.FACET.eq(choice.facet))))
    }
}
