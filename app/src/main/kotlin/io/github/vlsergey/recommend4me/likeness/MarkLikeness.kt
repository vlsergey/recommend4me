package io.github.vlsergey.recommend4me.likeness

import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.matrix.Blas
import io.github.vlsergey.recommend4me.matrix.Floats
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlin.math.expm1
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Something of a work the user marked — a picture, a review — +1 "I would pick the work for this",
 * −1 "I would drop it for this": its item, its own ref within the kind (a picture's position, a
 * review's id) and its vector (by the picture encoder for a picture, the text encoder for a review).
 *
 * WHAT THE USER SAID OF ITS MATCHES: the vectors of the matches confirmed alike in the sense meant
 * ([toward]) and of those alike in another sense ([awayFrom]), and the latter by item and ref
 * ([rejected]), which never count as alike it.
 */
class Marked(
    val owner: ItemKey,
    val ref: String,
    val mark: Int,
    val vector: FloatArray,
    val toward: List<FloatArray> = emptyList(),
    val awayFrom: List<FloatArray> = emptyList(),
    val rejected: Set<Pair<ItemKey, String>> = emptySet(),
)

/**
 * How much a work looks like the things the user marked — pictures, or reviews — as more axes of
 * the model, beside the blocks the pictures and the reviews have already, not instead of them.
 * The mean of a work's screenshots and the set embeddings describe a work in the directions of
 * the catalogue; a mark says which one scene, which one opinion the user cares about, and a scene
 * or an opinion like it in another work is found here however rare it is in the catalogue.
 *
 * LIKENESS: the cosine of the vectors, both centred on the catalogue's mean — what all pictures
 * of works, or all reviews, share does not make two of them alike. A work's likeness to a mark is
 * that of its most alike item.
 *
 * THE STRENGTH IS A SURPRISE, not a cosine. Every mark has its own usual cosines (a marked menu is
 * alike half the catalogue, a marked scene a few pictures), and the best of thirty items beats
 * the best of three by chance alone. So the cosine is first placed among the cosines of that mark
 * to every item of the catalogue (F, counted in the same pass), and the best of a work's n items
 * becomes the chance that n random items of the catalogue would all fall below it, F^n; the
 * strength is −ln(1 − F^n). Chance gives about 1 whatever n is; 5 is one work in 150.
 *
 * THE USER'S WORD ON A MATCH MOVES THE MARK (relevance feedback, Rocchio): its direction becomes
 * mark + [TOWARD]·(mean of the confirmed matches) − [AWAY]·(mean of the rejected ones), all
 * centred and of unit length — a marked naked walk in the street that matched a naked scene in a
 * bedroom, told "not that", looks more at the street. A rejected match never counts at all.
 *
 * FOUR AXES: the strongest and the second strongest likeness to a mark for the work, the same to
 * a mark against it. A work's own marks never count for it — they would tell its grade — nor, in
 * cross-validation, those of the works under test.
 *
 * KEPT IN THE DATABASE, made again only when the marks or the words on them change or the
 * catalogue grows: the calibration ([pack] — the marks' directions and the catalogue's cosines to
 * them) and every work's strengths ([strengths]). A pass over every item takes seconds; a rating
 * changes neither.
 */
class MarkLikeness private constructor(
    val marks: List<Marked>,
    private val center: FloatArray,
    /** The marks centred and of unit length, d × marks for products: column j is mark j. */
    private val columns: Floats,
    /** Per mark, how many items of the catalogue fall below every bin of the cosine. */
    private val below: Array<LongArray>,
    private val total: Long,
    /** The works whose strengths to every mark are at hand. */
    private val rows: Map<ItemKey, Row>,
    /** The four axes of the other works, as stored, their own marks not counted. */
    private val axes: Map<ItemKey, FloatArray> = emptyMap(),
) {
    /** A work's strength of likeness to every mark, and its thing most alike it (null for a stored row, which keeps none). */
    class Row(val strength: FloatArray, val at: Array<String?>)

    /** A thing ([ref]) of a work alike a [mark], with the [strength] of that. */
    class Match(val ref: String?, val mark: Marked, val strength: Float)

    private val m = marks.size

    fun row(item: ItemKey): Row? = rows[item]

    /**
     * The axes of a work, its own marks and those of the works [excluded] not counted; null for a
     * work none of whose items has a vector. A work read with its axes alone has only its own marks
     * left out: the others are excluded in cross-validation, which reads the rated works whole.
     */
    fun vector(item: ItemKey, excluded: Set<ItemKey> = emptySet()): FloatArray? =
        rows[item]?.let { row -> vector(row) { it.owner == item || it.owner in excluded } } ?: axes[item]

    /** The axes of a work's [row], the marks [excluded] not counted. */
    fun vector(row: Row, excluded: (Marked) -> Boolean): FloatArray {
        val out = FloatArray(DIM)
        for (j in 0 until m) {
            if (excluded(marks[j])) continue
            val s = row.strength[j]
            val at = if (marks[j].mark > 0) 0 else 2
            if (s > out[at]) {
                out[at + 1] = out[at]
                out[at] = s
            } else if (s > out[at + 1]) out[at + 1] = s
        }
        return out
    }

    /** Every work's strengths of likeness to the marks, to be stored. */
    fun strengths(): Map<ItemKey, FloatArray> = rows.mapValues { it.value.strength }

    /** Every work's axes, its own marks not counted, to be stored. */
    fun axes(): Map<ItemKey, FloatArray> = rows.mapValues { (id, row) -> vector(row) { it.owner == id } } + axes

    /** The calibration — everything but the works' rows — to be stored; [unpack] reads it back. */
    fun pack(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(DeflaterOutputStream(bytes)).use { out ->
            val d = center.size
            out.writeInt(d)
            out.writeInt(m)
            out.writeLong(total)
            center.forEach(out::writeFloat)
            for (k in 0 until d * m) out.writeFloat(columns[k])
            marks.forEach { mark ->
                out.writeUTF(mark.owner.toString())
                out.writeUTF(mark.ref)
                out.writeInt(mark.mark)
                out.writeInt(mark.rejected.size)
                mark.rejected.forEach { (item, ref) -> out.writeUTF(item.toString()); out.writeUTF(ref) }
            }
            // Counts per bin rather than the running sums: mostly zeros, they shrink to little
            below.forEachIndexed { j, sums ->
                for (b in 0 until BINS) out.writeInt(((if (b + 1 < BINS) sums[b + 1] else total) - sums[b]).toInt())
            }
        }
        return bytes.toByteArray()
    }

    /** The axes of every work with items, its own marks not counted. */
    fun forEach(action: (item: ItemKey, vector: FloatArray) -> Unit) {
        rows.forEach { (id, row) -> action(id, vector(row) { it.owner == id }) }
        axes.forEach { (id, v) -> if (id !in rows) action(id, v) }
    }

    /** The row of the item [owner] from its [items] (ref, vector) given here — one taken out, or new ones. */
    fun rowOf(owner: ItemKey, items: List<Pair<String, FloatArray>>): Row? {
        if (m == 0 || items.isEmpty()) return null
        val cosines = cosines(items.map { it.second })
        val best = FloatArray(m) { -2f }
        val at = arrayOfNulls<String>(m)
        for (i in items.indices) for (j in 0 until m) {
            if (rejects(j, owner, items[i].first)) continue
            val c = cosines[i * m + j]
            if (c > best[j]) { best[j] = c; at[j] = items[i].first }
        }
        return Row(FloatArray(m) { strength(j = it, cosine = best[it], n = items.size) }, at)
    }

    /**
     * The marks of other works the [row] is most alike, strongest first, [limit] of each sign, from
     * [SHOWN_FROM] on — every one of them news: one item of the work alike three marks of one
     * other work is shown once.
     */
    fun matches(owner: ItemKey, row: Row, limit: Int): List<Match> =
        marks.indices
            .filter { marks[it].owner != owner && row.strength[it] >= SHOWN_FROM }
            .map { Match(row.at[it], marks[it], row.strength[it]) }
            .groupBy { it.mark.mark }
            .flatMap { (_, list) ->
                list.sortedByDescending { it.strength }.distinctBy { it.ref }.distinctBy { it.mark.owner }.take(limit)
            }
            .sortedByDescending { it.strength }

    private fun rejects(j: Int, owner: ItemKey, ref: String): Boolean =
        marks[j].rejected.isNotEmpty() && (owner to ref) in marks[j].rejected

    /** Cosines of the [vectors] to every mark, centred: item i, mark j at i·m + j. */
    private fun cosines(vectors: List<FloatArray>): FloatArray {
        val d = center.size
        val a = Floats(vectors.size * d)
        vectors.forEachIndexed { i, v -> a.put(unit(v, center), 0, i * d, d) }
        val out = Floats(vectors.size * m)
        Blas.product(a, d, vectors.size, d, columns, m, out)
        return out.toArray()
    }

    private fun strength(j: Int, cosine: Float, n: Int): Float {
        if (total == 0L) return 0f
        val bin = binOf(cosine)
        val inBin = (if (bin + 1 < BINS) below[j][bin + 1] else total) - below[j][bin]
        // Half the item's own bin below it; never all of the catalogue, an item is not past every one
        val f = ((below[j][bin] + inBin / 2.0) / total).coerceAtMost(1.0 - 0.5 / total)
        if (f <= 0.0) return 0f
        val chance = -expm1(n * ln(f))
        return (-ln(chance)).toFloat().coerceIn(0f, MAX_STRENGTH)
    }

    companion object {
        /** The axes of the likeness to the marked pictures. */
        const val PICTURES = "marks:pictures"

        /** The axes of the likeness to the marked reviews. */
        const val REVIEWS = "marks:reviews"
        const val DIM = 4

        /** A likeness from which a match is shown: one work in twenty would have it by chance. */
        const val SHOWN_FROM = 3f

        /** How far a mark moves toward its confirmed matches, and away from its rejected ones. */
        const val TOWARD = 0.5f
        const val AWAY = 0.5f
        private const val MAX_STRENGTH = 20f
        private const val BINS = 4000
        private const val CHUNK = 4096

        private fun binOf(cosine: Float): Int = ((cosine + 1f) / 2f * BINS).toInt().coerceIn(0, BINS - 1)

        private fun unit(v: FloatArray, center: FloatArray): FloatArray {
            val u = FloatArray(center.size) { v[it] - center[it] }
            var n = 0.0
            for (x in u) n += x.toDouble() * x
            n = sqrt(n)
            if (n > 1e-9) for (k in u.indices) u[k] = (u[k] / n).toFloat()
            return u
        }

        /**
         * The likeness of every work to the [marks], in one pass over every item of the catalogue
         * with a vector, which [items] streams item by item (ref, vector); [center] is the
         * catalogue's mean item.
         */
        fun of(
            marks: List<Marked>,
            center: FloatArray,
            items: ((owner: ItemKey, things: List<Pair<String, FloatArray>>) -> Unit) -> Unit,
        ): MarkLikeness {
            val d = center.size
            val m = marks.size
            val columns = Floats(d * m.coerceAtLeast(1))
            marks.forEachIndexed { j, mark ->
                val u = direction(mark, center)
                for (k in 0 until d) columns[k * m + j] = u[k]
            }
            if (m == 0) return MarkLikeness(marks, center, columns, emptyArray(), 0, emptyMap())

            val counts = Array(m) { LongArray(BINS) }
            val best = HashMap<ItemKey, Pair<FloatArray, Array<String?>>>()
            val sizes = HashMap<ItemKey, Int>()
            val a = Floats(CHUNK * d)
            val out = Floats(CHUNK * m)
            val owners = arrayOfNulls<ItemKey>(CHUNK)
            val ids = arrayOfNulls<String>(CHUNK)
            var rows = 0
            fun flush() {
                if (rows == 0) return
                Blas.product(a, d, rows, d, columns, m, out)
                val cos = FloatArray(rows * m).also { out.take(it, 0, 0, rows * m) }
                for (i in 0 until rows) {
                    val (b, at) = best.getOrPut(owners[i]!!) { FloatArray(m) { -2f } to arrayOfNulls(m) }
                    for (j in 0 until m) {
                        val c = cos[i * m + j]
                        // The catalogue's usual likeness counts every item, a rejected one too
                        counts[j][binOf(c)]++
                        if (c > b[j] && !(marks[j].rejected.isNotEmpty() && (owners[i]!! to ids[i]!!) in marks[j].rejected)) {
                            b[j] = c
                            at[j] = ids[i]
                        }
                    }
                }
                rows = 0
            }
            items { owner, list ->
                if (list.isEmpty()) return@items
                sizes[owner] = list.size
                list.forEach { (id, v) ->
                    if (rows == CHUNK) flush()
                    a.put(unit(v, center), 0, rows * d, d)
                    owners[rows] = owner
                    ids[rows] = id
                    rows++
                }
            }
            flush()

            val below = Array(m) { j -> LongArray(BINS).also { c -> for (b in 1 until BINS) c[b] = c[b - 1] + counts[j][b - 1] } }
            val total = counts[0].sum()
            val likeness = MarkLikeness(marks, center, columns, below, total, emptyMap())
            val strengths = best.mapValues { (id, pair) ->
                val n = sizes.getValue(id)
                Row(FloatArray(m) { j -> likeness.strength(j, pair.first[j], n) }, pair.second)
            }
            return MarkLikeness(marks, center, columns, below, total, strengths)
        }

        /** The direction of a mark, centred, moved by the user's word on its matches, of unit length. */
        private fun direction(mark: Marked, center: FloatArray): FloatArray {
            val u = unit(mark.vector, center)
            fun add(vectors: List<FloatArray>, weight: Float) {
                if (vectors.isEmpty()) return
                vectors.forEach { v -> unit(v, center).forEachIndexed { k, x -> u[k] += weight * x / vectors.size } }
            }
            add(mark.toward, TOWARD)
            add(mark.awayFrom, -AWAY)
            return unit(u, FloatArray(center.size))
        }

        /** The likeness [pack]ed, with the [strengths] of some works and the [axes] of the others stored beside it. */
        fun unpack(calibration: ByteArray, strengths: Map<ItemKey, FloatArray>, axes: Map<ItemKey, FloatArray> = emptyMap()): MarkLikeness =
            DataInputStream(InflaterInputStream(ByteArrayInputStream(calibration))).use { input ->
                val d = input.readInt()
                val m = input.readInt()
                val total = input.readLong()
                val center = FloatArray(d) { input.readFloat() }
                val columns = Floats(d * m.coerceAtLeast(1))
                for (k in 0 until d * m) columns[k] = input.readFloat()
                val marks = List(m) {
                    val owner = ItemKey.parse(input.readUTF())
                    val ref = input.readUTF()
                    val mark = input.readInt()
                    val rejected = List(input.readInt()) { ItemKey.parse(input.readUTF()) to input.readUTF() }.toSet()
                    // The direction is in the columns; the mark's own vector is not kept
                    Marked(owner, ref, mark, FloatArray(0), rejected = rejected)
                }
                val below = Array(m) {
                    val sums = LongArray(BINS)
                    var sum = 0L
                    for (b in 0 until BINS) {
                        sums[b] = sum
                        sum += input.readInt()
                    }
                    sums
                }
                val rows = strengths.filterValues { it.size == m }.mapValues { (_, s) -> Row(s, arrayOfNulls(m)) }
                MarkLikeness(marks, center, columns, below, total, rows, axes.filterKeys { it !in rows })
            }

        /** No marks, no likeness: the axes of every work are missing (the average). */
        fun none(d: Int): MarkLikeness = of(emptyList(), FloatArray(d)) { }

        /** The mean of [vectors], what the likeness is measured from; zero when there are none. */
        fun centerOf(vectors: List<FloatArray>, d: Int): FloatArray =
            FloatArray(d) { k -> if (vectors.isEmpty()) 0f else (vectors.sumOf { it[k].toDouble() } / vectors.size).toFloat() }
    }
}
