package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.matrix.Matrix
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.scorer.RankingTask
import io.github.vlsergey.recommend4me.scorer.Scorer
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.workload.Work
import io.github.vlsergey.recommend4me.workload.Workload
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(InputMeasures::class.java)

/**
 * THE MEASURES OF THE MODEL'S INPUT ASKED FOR: what every part of it is worth to the ranking, whether weighing the
 * parts would rank better, and the rows every scorer is measured on. Each by the cross-validation the parameter is
 * chosen by; nothing is kept.
 */
@Component
class InputMeasures(
    private val stores: Stores,
    private val plugins: Plugins,
    private val reader: CatalogueReader,
    private val workload: Workload,
) {

    /**
     * What every scorer is measured on: the rows of the current grades — of the marks of interest, of the model of
     * [interest] — read under the type's lock; the measuring itself needs no lock — it reads nothing more. Null when
     * there are too few.
     */
    fun measuringInput(typeId: String, interest: Boolean = false): MeasuringInput? =
        workload.run(Work("scorersInput", type = typeId), mapOf("asked" to 1), asked = true, joinAlike = false) {
            val catalogue = reader.read(stores.contentType(typeId))
            val p = (if (interest) Prepared.interest(catalogue) else Prepared.grades(catalogue)) ?: return@run null
            MeasuringInput(p.task, p.xFor, p.grades.size)
        }

    /**
     * WHAT EVERY PART OF THE INPUT IS WORTH TO THE RANKING — the annotation, the cover, a facet with all its values, a
     * number: the out-of-fold quality of the type's scorer trained without that part (its columns zero — the average,
     * nothing to tell the works apart by) against trained on everything, by the same cross-validation and the same
     * choice of the parameter. A part whose removal costs nothing, or improves the quality, adds nothing the grades
     * confirm. Nothing is kept.
     */
    fun worth(typeId: String): ModelWorth? =
        workload.run(Work("worth", type = typeId), mapOf("asked" to 1), asked = true) {
            val type = stores.contentType(typeId)
            val scorer = plugins.scorer(typeId) ?: return@run null
            val p = Prepared.grades(reader.read(type)) ?: return@run null
            if (p.perItem.size < CrossValidation.MIN_WORKS) return@run null
            val full = CrossValidation.choose(CrossValidation.measureAll(p.task, scorer, p.xFor))?.metrics
            val columns = partColumns(p.layout)
            val labels = FeatureNames.partLabels(columns.keys, type.sources)
            fun without(m: Matrix, cols: List<Int>): Matrix {
                val out = Matrix(m.rows, m.cols)
                out.held.copy(m.held, 0, 0, m.rows * m.cols)
                for (r in 0 until m.rows) cols.forEach { out.held[out.row(r) + it] = 0f }
                return out
            }
            val parts = columns.map { (part, cols) ->
                val task = RankingTask(without(p.x, cols), p.grades, p.works, p.items)
                val xFor = p.xFor?.let { f -> { underTest: Set<Long> -> without(f(underTest), cols) } }
                PartWorth(
                    part,
                    labels.getValue(part),
                    cols.size,
                    CrossValidation.choose(CrossValidation.measureAll(task, scorer, xFor))?.metrics,
                )
            }
            ModelWorth(scorer.id, full, p.grades.size, parts.sortedBy { it.metrics?.concordance ?: Double.MAX_VALUE })
        }

    /** The columns of every part of the input in [layout], as the explanation names the parts. */
    private fun partColumns(layout: FeatureLayout): Map<String, List<Int>> {
        val columns = LinkedHashMap<String, MutableList<Int>>()
        layout.blocks.forEach { block ->
            val at = layout.offsetOf(block)!!
            columns.getOrPut(partOf(block)) { ArrayList() } += (at until at + layout.dimOf(block))
        }
        layout.categorical.forEachIndexed { i, name ->
            columns.getOrPut(partOf(name)) { ArrayList() } += layout.categoricalOffset + i
        }
        layout.numeric.forEachIndexed { i, name ->
            columns.getOrPut(partOf(name)) { ArrayList() } += layout.numericOffset + i
        }
        return columns
    }

    /**
     * WHETHER WEIGHING THE PARTS OF THE INPUT WOULD RANK BETTER ([GroupWeights]): every fold of the works ranked by a
     * model whose weights were chosen without it, against every weight 1 the same way — and the weights chosen on every
     * grade. The rows are read under the type's lock, the trainings — hundreds of them — run without it. Nothing is
     * kept. [cautious]: [GroupWeights.tune].
     */
    fun weighing(typeId: String, cautious: Boolean = true): Weighing? =
        workload.run(Work("weighing", type = typeId), mapOf("asked" to 1), joinAlike = false, asked = true) {
            val type = stores.contentType(typeId)
            val scorer = plugins.scorer(typeId)
            val p =
                scorer
                    ?.let { Prepared.grades(reader.read(type)) }
                    ?.takeIf { it.perItem.size >= CrossValidation.MIN_WORKS }
            if (scorer == null || p == null) null else weigh(type, scorer, p, cautious)
        }

    /** The weighing of the parts of the input of the [type], by [scorer] on the rows [p]. */
    private fun weigh(type: TypeStore, scorer: Scorer, p: Prepared, cautious: Boolean): Weighing {
        val started = System.currentTimeMillis()
        val columns = partColumns(p.layout)
        val groups = GroupWeights.groups(columns)
        val labels = FeatureNames.partLabels(columns.keys, type.sources)
        val folds = GroupWeights.nested(p.task, p.xFor, scorer, groups, cautious)
        val onAll = GroupWeights.tune(p.task, p.xFor, scorer, groups, cautious)
        log.info(
            "{}: the weighing of {} groups measured on {} grades in {} ms: {} folds, every weight 1 {} " +
                "against weighed {}; on every grade {}",
            type.id,
            groups.size,
            p.grades.size,
            System.currentTimeMillis() - started,
            folds.size,
            folds.map { "%.4f".format(it.baseline) },
            folds.map { "%.4f".format(it.weighed) },
            groups.indices.joinToString { "${groups[it].part}=${onAll.weights[it]}" },
        )
        return Weighing(
            scorer.id,
            p.grades.size,
            groups.mapIndexed { g, group ->
                WeighedGroup(
                    group.part,
                    labels[group.part] ?: I18nKeys.qualified(I18nKeys.STANDARD, "model.rest"),
                    group.columns.size,
                    onAll.weights[g],
                )
            },
            folds,
            onAll.metrics,
        )
    }
}
