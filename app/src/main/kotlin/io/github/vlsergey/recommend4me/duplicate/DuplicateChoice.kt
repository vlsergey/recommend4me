package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.model.DuplicateSort
import io.github.vlsergey.recommend4me.database.duplicates.tables.references.DUPLICATE_CANDIDATE
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.laps.Laps
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.SortField
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(DuplicateChoice::class.java)

/**
 * A pair of a page as chosen: its two works, the smaller key first, the word kept with it, and the key of the sort it
 * was chosen by ([priority], [PairScore]; none of one not known).
 */
class ChosenPair(
    val pair: Pair<ItemKey, ItemKey>,
    val answer: DuplicateAnswer?,
    val derived: Boolean,
    val priority: Double?,
)

/**
 * THE PAIRS OF A PAGE CHOSEN IN THE DATABASE (owner, 2026-11-20: "load the duplicates from the database with the
 * filters and the sorts: by the time they are read, 30 candidates for a page of 30"): of the candidates, those the
 * [PairChoice] holds — by the word kept with them, their authors and titles alike ([PairScore]) — in the order of its
 * sort, by the key of the sort the search wrote with them and its index: the open ones walk the index of the word and
 * the key from its start, a page's rows alone read. The total counted by the same filter.
 */
class DuplicateChoice internal constructor(private val db: DSLContext) {
    private val c = DUPLICATE_CANDIDATE

    /** The pairs the [choice] holds from [offset], [limit] of them, in the order of its sort; and how many in all. */
    fun page(choice: PairChoice, offset: Int, limit: Int): Pair<Int, List<ChosenPair>> {
        val laps = Laps()
        val where = condition(choice)
        val total = total(choice, where)
        laps.lap("counted")
        val field = keyOf(choice.sort)
        val key = field.desc()
        // Of the open pairs alone, the word first — none, of every one — as their index has it: walked, not sorted
        val order: List<SortField<*>> = if (choice.answers.isEmpty()) listOf(c.ANSWER.asc(), key) else listOf(key)
        val rows =
            db.select(c.SOURCE, c.ITEM_ID, c.OTHER_SOURCE, c.OTHER_ITEM_ID, c.ANSWER, c.ANSWER_DERIVED, field)
                .from(c)
                .where(where)
                .orderBy(order)
                .offset(offset)
                .limit(limit)
                .fetch { r ->
                    ChosenPair(
                        ItemKey(r.value1()!!, r.value2()!!) to ItemKey(r.value3()!!, r.value4()!!),
                        r.value5()?.let(DuplicateAnswer::valueOf),
                        r.value6()!!,
                        r.value7(),
                    )
                }
        laps.lap("chosen")
        log.info("a page of the pairs of duplicates by {} chosen: {}", choice.sort, laps)
        return total to rows
    }

    /**
     * How many pairs the [choice] holds: of the open ones, every pair but the answered — the table's count H2 keeps,
     * the answered few counted by the index of the word —, not a walk of a million rows of the index, each read by its
     * key (2026-11-20: 10 s and more of a page); of the authors or the titles alike, by the [where] of the page.
     */
    private fun total(choice: PairChoice, where: Condition): Int {
        fun count(condition: Condition?) =
            db.selectCount().from(c).where(condition ?: DSL.noCondition()).fetchOne(0, Int::class.java) ?: 0
        if (choice.sameAuthors || choice.sameTitle) return count(where)
        val words = choice.answers.map { it.name }
        val open = if (choice.open) count(null) - count(c.ANSWER.isNotNull) else 0
        return open + if (words.isEmpty()) 0 else count(c.ANSWER.`in`(words))
    }

    /** The pairs the [choice] holds: by the word on them, their authors and titles alike when asked. */
    private fun condition(choice: PairChoice): Condition {
        val words = choice.answers.map { it.name }
        val held =
            DSL.or(
                listOfNotNull(
                    c.ANSWER.isNull.takeIf { choice.open },
                    c.ANSWER.`in`(words).takeIf { words.isNotEmpty() },
                )
            )
        return DSL.and(
            listOfNotNull(
                held,
                c.SAME_AUTHORS.isTrue.takeIf { choice.sameAuthors },
                c.SAME_TITLE.isTrue.takeIf { choice.sameTitle },
            )
        )
    }

    /** The key the search wrote of the [sort] ([PairScore]): the larger first. */
    private fun keyOf(sort: DuplicateSort): Field<Double?> =
        when (sort) {
            DuplicateSort.CHANCE -> c.SAME_CHANCE
            DuplicateSort.SERIES -> c.SERIES_CHANCE
            DuplicateSort.SIZE -> c.BY_SIZE
            DuplicateSort.SERIES_SIZE -> c.BY_SERIES_SIZE
            DuplicateSort.UNCERTAIN -> c.BY_DOUBT
            DuplicateSort.UNCERTAIN_SERIES -> c.BY_SERIES_DOUBT
            DuplicateSort.FORMULA -> c.BY_FORMULA
            DuplicateSort.INTEREST -> c.BY_INTEREST
            DuplicateSort.GRADE -> c.BY_GRADE
        }
}
