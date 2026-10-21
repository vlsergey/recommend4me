package io.github.vlsergey.recommend4me.tagtext

import io.github.vlsergey.recommend4me.database.dictionaries.tables.references.TAG_TEXT_MODEL
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.time.Instant
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * THE MODEL OF THE CLOSENESS of a tag and a paragraph, learnt from the user's answers on the tags
 * offered by the text ([TagTextVerdict]): the chance that the work has the tag by the paragraph is
 * σ(slope · closeness + intercept + the tag's own bias). The bias of a tag the user keeps calling
 * wrong falls — a structural tag, never in a paragraph, sinks by itself; of a tag never answered on,
 * nought: it is told by its closeness alone.
 */
class TagTextModel(val slope: Double, val intercept: Double, val bias: Map<Long, Double>) {

    fun chance(tag: Long, closeness: Double): Double = sigmoid(slope * closeness + intercept + (bias[tag] ?: 0.0))

    companion object {
        /** The penalty of a tag's bias: a tag of a few answers is pulled toward the others. */
        private const val TAG_PENALTY = 1.0

        /** A slight one of the slope and the intercept: the answers of one kind alone leave them finite. */
        private const val PENALTY = 1e-3

        private const val ROUNDS = 100

        /**
         * Fitted of the [answers] that teach ([TagTextVerdict.YES] and [TagTextVerdict.WRONG]) by
         * Newton's steps: of the slope and the intercept together, of every tag's bias apart. Null
         * while there are not answers of both kinds — nothing to tell them by.
         */
        fun fit(answers: List<TagTextAnswer>): TagTextModel? {
            val teaching = answers.filter { it.verdict != TagTextVerdict.ENOUGH_NOT }
            if (teaching.none { it.verdict == TagTextVerdict.YES } || teaching.none { it.verdict == TagTextVerdict.WRONG }) return null
            val x = DoubleArray(teaching.size) { teaching[it].closeness.toDouble() }
            val y = DoubleArray(teaching.size) { if (teaching[it].verdict == TagTextVerdict.YES) 1.0 else 0.0 }
            val tags = teaching.map { it.tag }.distinct()
            val tagOf = IntArray(teaching.size) { tags.indexOf(teaching[it].tag) }
            val b = DoubleArray(tags.size)
            var s = 0.0
            var c = 0.0
            val p = DoubleArray(teaching.size)
            fun chances() {
                for (i in x.indices) p[i] = sigmoid(s * x[i] + c + b[tagOf[i]])
            }
            repeat(ROUNDS) {
                chances()
                // The slope and the intercept: a step of the 2 × 2 Hessian
                var gs = PENALTY * s
                var gc = PENALTY * c
                var hss = PENALTY
                var hsc = 0.0
                var hcc = PENALTY
                for (i in x.indices) {
                    val r = p[i] - y[i]
                    val w = p[i] * (1 - p[i])
                    gs += r * x[i]
                    gc += r
                    hss += w * x[i] * x[i]
                    hsc += w * x[i]
                    hcc += w
                }
                val det = hss * hcc - hsc * hsc
                if (abs(det) > 1e-12) {
                    s -= (hcc * gs - hsc * gc) / det
                    c -= (hss * gc - hsc * gs) / det
                }
                chances()
                // Every tag's bias, a step each
                val g = DoubleArray(tags.size) { TAG_PENALTY * b[it] }
                val h = DoubleArray(tags.size) { TAG_PENALTY }
                for (i in x.indices) {
                    g[tagOf[i]] += p[i] - y[i]
                    h[tagOf[i]] += p[i] * (1 - p[i])
                }
                for (t in tags.indices) b[t] -= g[t] / h[t]
            }
            return TagTextModel(s, c, tags.indices.associate { tags[it] to b[it] })
        }

        fun sigmoid(z: Double): Double = if (z >= 0) 1 / (1 + exp(-z)) else exp(z).let { it / (1 + it) }

        /** The loss of the [model] on the [answers]: what a test measures the fitting by. */
        fun loss(model: TagTextModel, answers: List<TagTextAnswer>): Double = answers.filter { it.verdict != TagTextVerdict.ENOUGH_NOT }.sumOf { a ->
            val p = model.chance(a.tag, a.closeness.toDouble())
            -ln(max(1e-12, if (a.verdict == TagTextVerdict.YES) p else 1 - p))
        }
    }
}

/** The model of the closeness kept in the dictionaries: every tag's bias a row, the slope and the intercept the row of tag nought. */
class TagTextModelRepository(private val db: DSLContext) {

    fun read(): TagTextModel? {
        val rows = db.selectFrom(TAG_TEXT_MODEL).fetch()
        val common = rows.firstOrNull { it.tag == COMMON } ?: return null
        return TagTextModel(common.slope ?: 0.0, common.bias!!, rows.filter { it.tag != COMMON }.associate { it.tag!! to it.bias!! })
    }

    /** The [model] kept in place of the last, of [answers] answers; none when it is null. */
    fun keep(model: TagTextModel?, answers: Map<Long, Int>, now: Instant) {
        db.transaction { c ->
            val tx = DSL.using(c)
            tx.deleteFrom(TAG_TEXT_MODEL).execute()
            if (model == null) return@transaction
            tx.insertInto(TAG_TEXT_MODEL).set(TAG_TEXT_MODEL.TAG, COMMON).set(TAG_TEXT_MODEL.BIAS, model.intercept).set(TAG_TEXT_MODEL.SLOPE, model.slope)
                .set(TAG_TEXT_MODEL.ANSWERS, answers.values.sum()).set(TAG_TEXT_MODEL.FITTED_AT, now).execute()
            model.bias.forEach { (tag, bias) ->
                tx.insertInto(TAG_TEXT_MODEL).set(TAG_TEXT_MODEL.TAG, tag).set(TAG_TEXT_MODEL.BIAS, bias)
                    .set(TAG_TEXT_MODEL.ANSWERS, answers[tag] ?: 0).set(TAG_TEXT_MODEL.FITTED_AT, now).execute()
            }
        }
    }

    private companion object {
        /** The row of the slope and the intercept: no tag is nought. */
        const val COMMON = 0L
    }
}
