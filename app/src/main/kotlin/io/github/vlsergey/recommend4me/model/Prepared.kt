package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.likeness.MarkLikeness
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.rating.Grades
import io.github.vlsergey.recommend4me.scorer.RankingTask
import io.github.vlsergey.recommend4me.vector.Vectors
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(Prepared::class.java)

/** The blocks of the likeness to the user's marks: a fold leaves out those of the works under test. */
internal val MARK_KEYS = setOf(MarkLikeness.PICTURES, MarkLikeness.REVIEWS)

/** The graded items as the model sees them, a row a grade; made by [grades] and [interest]. */
internal class Prepared(
    val used: List<TypedRating>,
    val grades: IntArray,
    val works: LongArray,
    /** The item of every row: two items of one work are compared as equal. */
    val items: LongArray,
    val perItem: List<ItemInput>,
    val layout: FeatureLayout,
    val x: Matrix,
    /** The input of every row, as [x] holds it. */
    val rows: List<ItemInput>,
    /** The items of every work among the rows. */
    val keysOfWork: Map<Long, List<ItemKey>>,
    val spreads: Map<String, VectorSpread>,
    val numbers: Map<String, NumberSpread>,
    /**
     * The rows as a fold sees them: the marks of the works under test not counted; null when nothing depends on them.
     */
    val xFor: ((Set<Long>) -> Matrix)?,
) {
    val everyRow = IntArray(used.size) { it }
    val task = RankingTask(x, grades, works, items)

    companion object {
        private const val MIN_RATINGS = 3

        /** A work of interest and one not, as grades of the model of interest: only their order matters. */
        const val INTERESTING = 2
        private const val NOT_INTERESTING = 1

        /**
         * The rows of the user's grades, a row a grade, each with the grade of an earlier version before it; null when
         * there are too few grades to train on.
         */
        fun grades(catalogue: Catalogue): Prepared? {
            val previousForRating = CatalogueReader.previousGradesOfRatings(catalogue.ratings)
            return of(catalogue, catalogue.ratings.filter { it.key in catalogue.byKey }, "grades") {
                previousForRating[it]
            }
        }

        /**
         * The rows of the model of interest: the user's marks of interest, the grades of earlier versions read as with
         * the grades.
         */
        fun interest(catalogue: Catalogue): Prepared? {
            val previous = CatalogueReader.previousGradesOfCurrentVersions(catalogue.ratings, catalogue::versionOf)
            return of(catalogue, interestRows(catalogue), "interest marks") { previous[it.key] }
        }

        /**
         * Every work the user marked of interest or not ([Interest]): the marks alone, no grade taken for one. A row a
         * work, at the time it was marked.
         */
        private fun interestRows(catalogue: Catalogue): List<TypedRating> =
            catalogue.items.mapNotNull { item ->
                val mark = catalogue.interest[item.key] ?: return@mapNotNull null
                TypedRating(
                    item.key,
                    item.version,
                    if (mark.interesting) INTERESTING else NOT_INTERESTING,
                    mark.markedAt,
                )
            }

        /**
         * The rows of [used] — the user's grades, or the works labelled interesting or not as grades [INTERESTING] and
         * [NOT_INTERESTING] — each with the grade of an earlier version [previousOf] gives it. A work is one row
         * however many sites have it ([Catalogue]).
         */
        private fun of(
            catalogue: Catalogue,
            used: List<TypedRating>,
            what: String,
            previousOf: (TypedRating) -> Int?,
        ): Prepared? {
            val likeness = catalogue.likeness
            val previousForCurrent =
                CatalogueReader.previousGradesOfCurrentVersions(catalogue.ratings, catalogue::versionOf)
            val clusters = catalogue.works
            val grades = IntArray(used.size) { used[it].grade }
            if (used.size < MIN_RATINGS || grades.distinct().size < 2) {
                log.info(
                    "{}: not enough {} to train: {} of {} values",
                    catalogue.type.id,
                    what,
                    used.size,
                    grades.distinct().size,
                )
                return null
            }
            val rows = used.map { r ->
                CatalogueReader.inputOf(catalogue.byKey.getValue(r.key), catalogue.vectorsOf(r.key), previousOf(r))
            }
            // The current input of every graded item: what the vocabulary and the importances are counted on
            val perItem =
                used
                    .map { it.key }
                    .distinct()
                    .map { key ->
                        CatalogueReader.inputOf(
                            catalogue.byKey.getValue(key),
                            catalogue.vectorsOf(key),
                            previousForCurrent[key],
                        )
                    }
            // Of every work: its blocks' as counted when the catalogue was read, the likeness to the marks' counted
            // here
            val spreads =
                catalogue.averages.vectors +
                    VectorSpread.of { action ->
                        likeness.pictures.forEach { _, v -> action(MarkLikeness.PICTURES, v) }
                        likeness.reviews.forEach { _, v -> action(MarkLikeness.REVIEWS, v) }
                    }
            val numbers = catalogue.averages.numbers
            val layout = FeatureLayout.of(perItem, spreads, numbers)
            val x = layout.matrix(rows)
            val works = LongArray(rows.size) { clusters.work(rows[it].key) }
            val items = LongArray(rows.size) { Vectors.keyOf(rows[it].key.toString()) }
            val keysOfWork = rows.indices.groupBy({ works[it] }, { rows[it].key })
            val xFor = if (likeness.empty) null else foldRows(catalogue, layout, rows, keysOfWork)
            return Prepared(used, grades, works, items, perItem, layout, x, rows, keysOfWork, spreads, numbers, xFor)
        }

        /**
         * The [rows] as a fold sees them, laid out by [layout]: the likeness of a graded item to the marks of the works
         * under test would tell the fold what it is tested on. Made once a fold for every measuring of the training,
         * the folds' made side by side.
         */
        private fun foldRows(
            catalogue: Catalogue,
            layout: FeatureLayout,
            rows: List<ItemInput>,
            keysOfWork: Map<Long, List<ItemKey>>,
        ): (Set<Long>) -> Matrix {
            val ofFold = ConcurrentHashMap<Set<Long>, Matrix>()
            return { underTest: Set<Long> ->
                ofFold.computeIfAbsent(underTest) {
                    val excluded =
                        underTest.flatMap { keysOfWork[it].orEmpty() }.flatMap(catalogue.works::members).toSet()
                    layout.matrix(
                        rows.map { input ->
                            input.withVectors(input.vectors - MARK_KEYS + catalogue.likenessOf(input.key, excluded))
                        }
                    )
                }
            }
        }
    }
}

/**
 * Share of the catalogue's items with every categorical feature of the [layout]: as the averages say, else counted of
 * the items read.
 */
internal fun categoricalShare(layout: FeatureLayout, catalogue: Catalogue): FloatArray {
    val index = layout.categorical.withIndex().associate { (i, name) -> name to i }
    val counts = IntArray(layout.categorical.size)
    catalogue.items.forEach { item -> item.categorical.forEach { name -> index[name]?.let { counts[it]++ } } }
    val total = catalogue.items.size
    val told = catalogue.averages.shares
    return FloatArray(counts.size) {
        told[layout.categorical[it]] ?: if (total > 0) counts[it].toFloat() / total else 0f
    }
}

/** How many of the [grades] are of every grade, from the lowest. */
internal fun gradeCounts(grades: IntArray) = IntArray(Grades.MAX).apply { grades.forEach { this[it - Grades.MIN]++ } }
