package io.github.vlsergey.recommend4me.duplicate

import io.github.vlsergey.recommend4me.matrix.ByteMatrix
import io.github.vlsergey.recommend4me.matrix.HalfMatrix
import io.github.vlsergey.recommend4me.matrix.Rows
import io.github.vlsergey.recommend4me.matrix.SparseRows
import java.util.Random
import kotlin.math.sqrt

/**
 * Searches of random works of three sites, and models of them: what the tests of the search and of its plan are made
 * of.
 */
internal object RandomSearches {
    val families = listOf("characters", "status", "tags")
    const val VALUES = 60
    val familyOf = IntArray(VALUES) { it % families.size }

    /**
     * A site of [count] works; of every work one of [titleKinds] titles at random, when not nought, else a title of its
     * own.
     */
    fun site(
        random: Random,
        source: String,
        count: Int,
        width: Int,
        vectored: Int = TITLE_VECTORS,
        titleKinds: Int = 0,
    ): SiteWorks {
        val titles = titles(random, count, width, titleKinds)
        val titleLengths = FloatArray(count) { 2f + random.nextInt(6) }
        // The names of the authors of some, of others none
        val authorLengths = FloatArray(count) { if (random.nextInt(3) == 0) 0f else 1f + random.nextInt(4) }
        val authorNames =
            HalfMatrix(count, width).also { m ->
                for (i in 0 until count) if (authorLengths[i] > 0f) m.put(i, unit(random, width, counts = true))
            }
        val described = BooleanArray(count) { random.nextInt(4) != 0 }
        val descriptions =
            HalfMatrix(count, width).also { m ->
                for (i in 0 until count) if (described[i]) m.put(i, unit(random, width))
            }
        val values =
            Array(count) { IntArray(random.nextInt(6)) { random.nextInt(VALUES) }.distinct().sorted().toIntArray() }
        // A work's values of each facet of unit length, as the application weighs them
        val weights =
            Array(count) { i ->
                val raw = FloatArray(values[i].size) { 0.2f + random.nextFloat() }
                val lengths = HashMap<Int, Double>()
                values[i].forEachIndexed { k, v ->
                    lengths.merge(familyOf[v], (raw[k] * raw[k]).toDouble(), Double::plus)
                }
                FloatArray(raw.size) { k -> (raw[k] / sqrt(lengths.getValue(familyOf[values[i][k]]))).toFloat() }
            }
        val facets = LongArray(count) { i -> values[i].fold(0L) { bits, v -> bits or (1L shl familyOf[v]) } }
        val sizes =
            Array(2) { DoubleArray(count) { if (random.nextInt(3) == 0) 0.0 else 1000.0 + random.nextInt(100_000) } }
        val authors = Array(count) { LongArray(random.nextInt(2)) { random.nextInt(40).toLong() } }
        // An author one with the next, in part
        val kin = Array(count) { i -> authors[i].flatMap { listOf(it, it + 1) }.distinct().toLongArray() }
        // The titles' vectors of most, of an encoder of the site's; the descriptions' trigrams of some
        val titled = BooleanArray(count) { random.nextInt(5) != 0 }
        val letterLengths =
            LetteredText.entries.map {
                FloatArray(count) { if (random.nextInt(3) == 0) 0f else 5f + random.nextInt(50) }
            }
        // Kept as the application keeps them: the short texts' trigrams sparse, the vectors and the description's
        // trigrams a byte a number
        return SiteWorks(
            source,
            List(count) { "%05d".format(it) },
            sparse(titles),
            titleLengths,
            sparse(authorNames),
            authorLengths,
            bytes(descriptions),
            described,
            values,
            weights,
            facets,
            sizes,
            authors,
            kin,
            bytes(rows(random, titled, vectored, counts = false)),
            titled,
            letterLengths.mapIndexed { k, l ->
                val m = rows(random, BooleanArray(count) { l[it] > 0f }, width, counts = true)
                if (LetteredText.entries[k] == LetteredText.DESCRIPTION) bytes(m) else sparse(m)
            },
            letterLengths,
        )
    }

    /** The titles' trigrams of [count] works: of every work one of [kinds] at random, of none a title of its own. */
    private fun titles(random: Random, count: Int, width: Int, kinds: Int): HalfMatrix {
        val borne = Array(kinds) { unit(random, width, counts = true) }
        return HalfMatrix(count, width).also { m ->
            for (i in 0 until count) {
                m.put(i, if (kinds == 0) unit(random, width, counts = true) else borne[random.nextInt(kinds)])
            }
        }
    }

    /** The rows of [m] kept sparse. */
    private fun sparse(m: HalfMatrix): Rows =
        SparseRows.of(m.rows, m.cols) { i ->
            FloatArray(m.cols).also { m.take(i, it) }.takeIf { r -> r.any { it != 0f } }
        }

    /** The rows of [m] kept a byte a number. */
    private fun bytes(m: HalfMatrix): Rows =
        ByteMatrix(m.rows, m.cols).also { b ->
            for (i in 0 until m.rows) b.put(i, FloatArray(m.cols).also { m.take(i, it) })
        }

    /** Rows of [width] numbers, a unit row of each of those [had], of the others none. */
    private fun rows(random: Random, had: BooleanArray, width: Int, counts: Boolean): HalfMatrix =
        HalfMatrix(had.size, width).also { m ->
            for (i in had.indices) if (had[i]) m.put(i, unit(random, width, counts))
        }

    /** The numbers of a title's vector of the most sites. */
    const val TITLE_VECTORS = 8

    fun search(random: Random): PairSearch {
        val sites =
            listOf(site(random, "a", 300, 24), site(random, "b", 500, 24), site(random, "c", 40, 16, vectored = 12))
        // A few pairs answered on, passed over both ways: of two sites, and of one
        val answered = HashMap<Pair<Int, Int>, HashMap<Int, IntArray>>()
        fun answer(a: Int, i: Int, b: Int, j: Int) {
            answered.getOrPut(a to b) { HashMap() }.merge(i, intArrayOf(j)) { x, y -> x + y }
            answered.getOrPut(b to a) { HashMap() }.merge(j, intArrayOf(i)) { x, y -> x + y }
        }
        repeat(30) { answer(0, random.nextInt(300), 1, random.nextInt(500)) }
        repeat(30) { answer(0, random.nextInt(150), 0, 150 + random.nextInt(150)) }
        return PairSearch(sites, families, familyOf, listOf("words", "chars"), answered)
    }

    /** A learnt model of weights of its own, and the mean of the layers. */
    fun models(): List<Logistic> =
        listOf(
            Logistic(
                mapOf(
                    Logistic.CONSTANT to -3.0,
                    Logistic.TITLE to 2.5,
                    Logistic.TITLE_WITHIN to 0.6,
                    Logistic.TITLE_HOLDING to -0.4,
                    Logistic.AUTHORS to 1.1,
                    Logistic.has(Logistic.AUTHORS) to -0.2,
                    Logistic.AUTHORS_WITHIN to 0.5,
                    Logistic.AUTHORS_HOLDING to 0.3,
                    Logistic.DESCRIPTION to 1.5,
                    Logistic.has(Logistic.DESCRIPTION) to 0.2,
                    Logistic.SIZE to 0.7,
                    Logistic.has(Logistic.SIZE) to -0.1,
                    Logistic.SAME_AUTHOR to 1.2,
                    Logistic.PARTLY_SAME_AUTHOR to 0.6,
                    Logistic.TITLE_VECTOR to 1.3,
                    Logistic.has(Logistic.TITLE_VECTOR) to -0.3,
                    Logistic.letters(LetteredText.DESCRIPTION) to 0.9,
                    Logistic.has(Logistic.letters(LetteredText.DESCRIPTION)) to 0.1,
                    Logistic.letters(LetteredText.FANDOMS) to 0.7,
                    Logistic.has(Logistic.letters(LetteredText.FANDOMS)) to -0.2,
                    Logistic.letters(LetteredText.CHARACTERS) to 0.5,
                    Logistic.facet("characters") to 2.0,
                    Logistic.has(Logistic.facet("characters")) to 0.3,
                    Logistic.facet("tags") to 0.8,
                    // One site's works with each other unlikely, two sites' likelier: the sites' weight in the scores
                    Logistic.sites("a", "a") to -1.5,
                    Logistic.sites("a", "b") to 0.9,
                    Logistic.sites("b", "c") to 0.4,
                )
            ),
            Logistic(null),
        )

    /**
     * A unit row of [width] numbers; of counts of trigrams — a title's, the authors' names' — a few buckets of a count
     * each, of no number below nought.
     */
    private fun unit(random: Random, width: Int, counts: Boolean = false): FloatArray {
        val v =
            if (counts) FloatArray(width).also { v -> repeat(2 + random.nextInt(4)) { v[random.nextInt(width)] += 1f } }
            else FloatArray(width) { random.nextGaussian().toFloat() }
        val l = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(width) { v[it] / l }
    }
}
