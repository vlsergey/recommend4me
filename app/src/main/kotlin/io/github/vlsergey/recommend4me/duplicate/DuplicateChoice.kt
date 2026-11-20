package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.api.model.DuplicateSort
import io.github.vlsergey.recommend4me.database.model.tables.Prediction
import io.github.vlsergey.recommend4me.database.model.tables.references.DUPLICATE_CANDIDATE
import io.github.vlsergey.recommend4me.database.model.tables.references.PREDICTION
import io.github.vlsergey.recommend4me.item.ItemKey
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.jooq.impl.SQLDataType

/** A pair of a page as chosen: its two works, the smaller key first, and the word kept with it ([PairScore]). */
class ChosenPair(val pair: Pair<ItemKey, ItemKey>, val answer: DuplicateAnswer?, val derived: Boolean)

/**
 * The user's marks of interest of works, by their items: what the sorts by the formula and by interest read over the
 * model's chance ([io.github.vlsergey.recommend4me.interest.Interest.chance]).
 */
class InterestMarks(val interesting: Collection<ItemKey>, val dull: Collection<ItemKey>)

/**
 * THE PAIRS OF A PAGE CHOSEN IN THE DATABASE (owner, 2026-11-20: "load the duplicates from the database with the
 * filters and the sorts: by the time they are read, 30 candidates for a page of 30"): of the candidates, those the
 * [PairChoice] holds — by the word kept with them, their authors and titles alike ([PairScore]) — in the order of its
 * sort, of the chances kept with them; the works' predictions joined of the same database, the user's marks of interest
 * given. The total counted by the same filter.
 */
class DuplicateChoice internal constructor(private val db: DSLContext) {
    private val c = DUPLICATE_CANDIDATE
    private val first: Prediction = PREDICTION.`as`("first_prediction")
    private val second: Prediction = PREDICTION.`as`("second_prediction")

    /**
     * The pairs the [choice] holds from [offset], [limit] of them, in the order of its sort, with every work's [marks]
     * of interest; and how many it holds in all.
     */
    fun page(choice: PairChoice, marks: InterestMarks, offset: Int, limit: Int): Pair<Int, List<ChosenPair>> {
        val where = condition(choice)
        val total = db.selectCount().from(c).where(where).fetchOne(0, Int::class.java) ?: 0
        val worthy = choice.sort in WORTHY
        val from =
            if (!worthy) c
            else
                c.leftJoin(first)
                    .on(first.SOURCE.eq(c.SOURCE), first.ITEM_ID.eq(c.ITEM_ID))
                    .leftJoin(second)
                    .on(second.SOURCE.eq(c.OTHER_SOURCE), second.ITEM_ID.eq(c.OTHER_ITEM_ID))
        val rows =
            db.select(c.SOURCE, c.ITEM_ID, c.OTHER_SOURCE, c.OTHER_ITEM_ID, c.ANSWER, c.ANSWER_DERIVED)
                .from(from)
                .where(where)
                .orderBy(
                    priority(choice.sort, marks).desc(),
                    DSL.coalesce(c.SAME_CHANCE, NONE).desc(),
                    c.SOURCE,
                    c.ITEM_ID,
                    c.OTHER_SOURCE,
                    c.OTHER_ITEM_ID,
                )
                .offset(offset)
                .limit(limit)
                .fetch { r ->
                    ChosenPair(
                        ItemKey(r.value1()!!, r.value2()!!) to ItemKey(r.value3()!!, r.value4()!!),
                        r.value5()?.let(DuplicateAnswer::valueOf),
                        r.value6()!!,
                    )
                }
        return total to rows
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

    /**
     * What the [sort] ranks a pair by ([DuplicatePages.page]): its chance of one work or of one series, alone, times
     * its size, or times what the sort says of the work it was found for; or how near it is to where the user's answers
     * change.
     */
    private fun priority(sort: DuplicateSort, marks: InterestMarks): Field<Double> {
        val same = DSL.coalesce(c.SAME_CHANCE, 0.0)
        val series = DSL.coalesce(c.SERIES_CHANCE, 0.0)
        return when (sort) {
            DuplicateSort.CHANCE -> same
            DuplicateSort.SERIES -> series
            DuplicateSort.SIZE -> size().times(same)
            DuplicateSort.SERIES_SIZE -> size().times(series)
            DuplicateSort.UNCERTAIN ->
                doubt(c.SAME_CHANCE, boundary(c.SAME_CHANCE, c.ANSWER.eq(SAME), c.ANSWER.eq(DIFF)))
            DuplicateSort.UNCERTAIN_SERIES ->
                doubt(c.SERIES_CHANCE, boundary(c.SERIES_CHANCE, c.ANSWER.eq(SERIES), c.ANSWER.ne(SERIES)))
            DuplicateSort.FORMULA,
            DuplicateSort.INTEREST,
            DuplicateSort.GRADE -> sought(sort, marks).times(same)
        }
    }

    /** The mean of the two works' sizes in words, of those known: of a work of none, the other's alone. */
    private fun size(): Field<Double> {
        val words = c.WORDS.cast(SQLDataType.DOUBLE)
        val other = c.OTHER_WORDS.cast(SQLDataType.DOUBLE)
        return DSL.`when`(c.WORDS.isNull.and(c.OTHER_WORDS.isNull), 0.0)
            .`when`(c.WORDS.isNull, other)
            .`when`(c.OTHER_WORDS.isNull, words)
            .otherwise(words.plus(other).div(2.0))
    }

    /**
     * Where on the scale of the [chance] the user's answers change: between the mean of the pairs said to be of its
     * kind ([yes]) and of those said not ([no]); nought with neither kind of answer ([PairChances.boundary]).
     */
    private fun boundary(chance: Field<Double?>, yes: Condition, no: Condition): Double {
        val logit = logit(chance)
        val means =
            db.select(
                    DSL.avg(logit).filterWhere(yes.and(chance.isNotNull)),
                    DSL.avg(logit).filterWhere(no.and(chance.isNotNull)),
                )
                .from(c)
                .where(c.ANSWER.isNotNull)
                .fetchOne()
        val a = means?.value1()?.toDouble()
        val b = means?.value2()?.toDouble()
        return if (a == null || b == null) 0.0 else (a + b) / 2
    }

    /** How near the [chance] is to the [boundary] on its scale: one at it, less the farther; none of no chance. */
    private fun doubt(chance: Field<Double?>, boundary: Double): Field<Double> =
        DSL.`when`(chance.isNull, 0.0)
            .otherwise(DSL.inline(1.0).div(DSL.inline(1.0).plus(DSL.abs(logit(chance).minus(boundary)))))

    /** The [chance] on the scale a logistic model adds up on, a chance of nought or one held off its ends. */
    private fun logit(chance: Field<Double?>): Field<Double> {
        val held = DSL.greatest(DSL.least(chance, 1 - LEAST), DSL.inline(LEAST)).cast(SQLDataType.DOUBLE)
        return DSL.ln(held.div(DSL.inline(1.0).minus(held))).cast(SQLDataType.DOUBLE)
    }

    /**
     * What the [sort] says of the work the pair was found for — of both, the larger; of neither (an answered pair not
     * found by the search itself), the larger too.
     */
    private fun sought(sort: DuplicateSort, marks: InterestMarks): Field<Double> {
        val ofFirst = worth(sort, first, c.SOURCE, c.ITEM_ID, marks)
        val ofSecond = worth(sort, second, c.OTHER_SOURCE, c.OTHER_ITEM_ID, marks)
        return DSL.`when`(c.FOUND_FOR.eq(DuplicateCandidate.FIRST.toByte()), ofFirst)
            .`when`(c.FOUND_FOR.eq(DuplicateCandidate.SECOND.toByte()), ofSecond)
            .otherwise(DSL.greatest(ofFirst, ofSecond))
    }

    /**
     * What the [sort] says of a work of its [prediction]: of the predicted grade (not below nought) and the chance of
     * interest — the user's mark over the model's; without a model of interest, 1 —; nought of a work not predicted.
     */
    private fun worth(
        sort: DuplicateSort,
        prediction: Prediction,
        source: Field<String?>,
        item: Field<String?>,
        marks: InterestMarks,
    ): Field<Double> {
        val grade = DSL.greatest(prediction.SCORE, DSL.inline(0.0))
        val key = DSL.concat(source, DSL.inline(SEPARATOR), item)
        var interest = DSL.`when`(DSL.falseCondition(), 1.0)
        if (marks.interesting.isNotEmpty()) interest = interest.`when`(key.`in`(marks.interesting.map(::joined)), 1.0)
        if (marks.dull.isNotEmpty()) interest = interest.`when`(key.`in`(marks.dull.map(::joined)), 0.0)
        val chance = interest.otherwise(DSL.coalesce(prediction.INTEREST, 1.0))
        val worth =
            when (sort) {
                DuplicateSort.INTEREST -> chance
                DuplicateSort.GRADE -> grade
                else -> grade.times(chance)
            }
        return DSL.`when`(prediction.SCORE.isNull, 0.0).otherwise(worth)
    }

    private fun joined(key: ItemKey) = key.source + SEPARATOR + key.id

    private companion object {
        /** The sorts by what they say of the work a pair was found for. */
        val WORTHY = setOf(DuplicateSort.FORMULA, DuplicateSort.INTEREST, DuplicateSort.GRADE)

        val SAME = DuplicateAnswer.SAME.name
        val DIFF = DuplicateAnswer.DIFFERENT.name
        val SERIES = DuplicateAnswer.SERIES.name

        /** Of a pair of no chance kept, below any. */
        const val NONE = -1.0

        /** A chance of nought or one has no place on the scale of [logit]. */
        const val LEAST = 1e-12

        /** Between a work's source and its id in a key compared: a character neither has. */
        const val SEPARATOR = "\u0001"
    }
}
