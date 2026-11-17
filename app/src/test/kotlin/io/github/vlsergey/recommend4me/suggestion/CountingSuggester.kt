package io.github.vlsergey.recommend4me.suggestion

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.math.exp
import kotlin.math.ln
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

/**
 * A SUGGESTER OF COUNTS ALONE, for the tests of the passes over the works: of every value, how many works have it, and
 * of every column of the context, how many works of it have the value — sums over every work, whatever the portions
 * they came in. A work's score of a value: the log-odds of the value, with the log-odds of the value among the works of
 * each of its columns over them. Exactly the same model of the same works however they are cut into portions.
 */
class CountingSuggester : FacetSuggester {
    override val id = ID

    override fun fit(portions: TaskPortions, previous: FittedSuggester?, stop: () -> Boolean): FittedSuggester? {
        val side = portions.values
        val has = IntArray(side.valueCount)
        val columns = IntArray(side.contextCount)
        val together = HashMap<Long, Int>()
        var works = 0
        portions.forEach { task ->
            works += task.itemCount
            (0 until task.itemCount).forEach { i ->
                task.assigned[i].forEach { has[it]++ }
                task.context[i].forEach { c ->
                    columns[c]++
                    task.assigned[i].forEach { v -> together.merge(pair(c, v), 1, Int::plus) }
                }
            }
        }
        if (has.all { it == 0 }) return null
        val prior = DoubleArray(side.valueCount) { logit(has[it], works) }
        val weights =
            together.entries
                .sortedBy { it.key }
                .map { (k, n) ->
                    val c = (k ushr Int.SIZE_BITS).toInt()
                    val v = k.toInt()
                    Triple(side.contextKeys[c], v, logit(n, columns[c]) - prior[v])
                }
        val model = Model(side.valueKeys, prior, weights).pack()
        return object : FittedSuggester {
            override fun model(task: SuggestionTask) = model

            override fun pack() = model
        }
    }

    override fun unpack(bytes: ByteArray, task: SuggestionTask): FittedSuggester? =
        Model.of(bytes)
            ?.takeIf { it.values == task.valueKeys }
            ?.let {
                object : FittedSuggester {
                    override fun model(task: SuggestionTask) = bytes

                    override fun pack() = bytes
                }
            }

    override fun scoring(model: ByteArray, task: SuggestionTask): SuggestionScores? {
        val read = Model.of(model)
        val place = read?.values?.withIndex()?.associate { (i, k) -> k to i }.orEmpty()
        val of = task.valueKeys.mapNotNull { place[it] }
        if (read == null || of.size != task.valueCount) return null
        val byColumn = read.weights.groupBy({ it.first }, { it.second to it.third })
        val columns = task.contextKeys.map { byColumn[it].orEmpty() }
        return SuggestionScores { rows ->
            rows.map { i ->
                val score = DoubleArray(of.size) { read.prior[of[it]] }
                task.context[i].forEach { c ->
                    columns[c].forEach { (v, w) ->
                        val at = of.indexOf(v)
                        if (at >= 0) score[at] += w
                    }
                }
                FloatArray(of.size) { (1 / (1 + exp(-score[it]))).toFloat() }
            }
        }
    }

    /** What is fitted: the values' keys, their log-odds, the weights of the columns of the context by their keys. */
    private class Model(
        val values: List<String>,
        val prior: DoubleArray,
        val weights: List<Triple<String, Int, Double>>,
    ) {
        fun pack(): ByteArray {
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { out ->
                out.writeInt(values.size)
                values.forEachIndexed { k, v ->
                    out.writeUTF(v)
                    out.writeDouble(prior[k])
                }
                out.writeInt(weights.size)
                weights.forEach { (c, v, w) ->
                    out.writeUTF(c)
                    out.writeInt(v)
                    out.writeDouble(w)
                }
            }
            return bytes.toByteArray()
        }

        companion object {
            fun of(bytes: ByteArray): Model? = runCatching {
                DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                    val values = ArrayList<String>()
                    val prior =
                        DoubleArray(input.readInt()) {
                            values += input.readUTF()
                            input.readDouble()
                        }
                    val weights =
                        List(input.readInt()) {
                            Triple(input.readUTF(), input.readInt(), input.readDouble())
                        }
                    Model(values, prior, weights)
                }
            }
                .getOrNull()
        }
    }

    companion object {
        const val ID = "counting"

        /** The value and the column of the context as one key. */
        private fun pair(c: Int, v: Int): Long = (c.toLong() shl Int.SIZE_BITS) or v.toLong()

        /** The log-odds of [n] of [of], with half a yes and half a no besides. */
        private fun logit(n: Int, of: Int): Double = ln((n + HALF) / (of - n + HALF))

        private const val HALF = 0.5
    }

    /** The suggester as a bean of a test's application. */
    @TestConfiguration
    class Plugin {
        @Bean fun countingSuggester(): FacetSuggester = CountingSuggester()
    }
}
