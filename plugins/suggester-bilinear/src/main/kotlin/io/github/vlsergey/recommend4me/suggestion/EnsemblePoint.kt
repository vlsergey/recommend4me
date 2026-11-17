package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Floats

/**
 * Where the numbers of one model's point — or its gradient — lie among those of many ([EnsemblePoint]): its pieces,
 * each [count] numbers at [at] of [floats], read one after another as one vector.
 */
internal class Pieces(val floats: Array<Floats>, val at: IntArray, val count: IntArray) {
    val size = count.sum()

    /** The numbers of [x] written there, its pieces one after another. */
    fun write(x: Floats) {
        var k = 0
        for (p in floats.indices) {
            floats[p].copy(x, k, at[p], count[p])
            k += count[p]
        }
    }

    /** What is there read into [into], its pieces one after another. */
    fun read(into: Floats) {
        var k = 0
        for (p in floats.indices) {
            into.copy(floats[p], at[p], k, count[p])
            k += count[p]
        }
    }
}

/**
 * THE POINT AND THE GRADIENT OF EVERY MODEL OF A FITTING, in the main memory, kept while the fitting walks the portions
 * — the ensemble of a portion ([Ensemble]) made over them and gone with the portion. Of [count] models of rank [r] over
 * [dx] features of an item, [dz] of a value and [m] values: five pieces of each — Aᵀ, Bᵀ, Eᵀ, the biases, the pair's
 * own weights — the models' one after another in every piece ([TransposedLayout]); and the values' coordinates of every
 * model made of them at a step ([vT]).
 */
internal class EnsemblePoint(val count: Int, val r: Int, val dx: Int, val dz: Int, val m: Int) {
    val layout = TransposedLayout(dx, dz, m, r)

    /** The numbers of a model in every piece: A, B, E, the biases, the pair's own weights. */
    val sizes = intArrayOf(r * dx, r * dz, r * m, m, Bilinear.PAIR)

    val aT = Floats(count * sizes[0])
    val bT = Floats(count * sizes[1])
    val eT = Floats(count * sizes[2])
    val bias = Floats(count * sizes[3])
    val pair = Floats(count * sizes[4])
    val gA = Floats(count * sizes[0])
    val gB = Floats(count * sizes[1])

    /** The gradient of E, which is dV: the values' coordinates' slopes, carried back to B from here. */
    val gE = Floats(count * sizes[2])
    val gBias = Floats(count * sizes[3])
    val gPair = Floats(count * sizes[4])

    /** The values' coordinates of every model, transposed: rank rows of every value. */
    val vT = Floats(count * r * m)

    /** Every piece of the point, and of the gradient, in their order: what a step of the descent goes over. */
    val point: Array<Floats> = arrayOf(aT, bT, eT, bias, pair)
    val gradient: Array<Floats> = arrayOf(gA, gB, gE, gBias, gPair)

    /** Where the pieces of the [model] lie, in the point or in the [gradient]. */
    fun pieces(model: Int, gradient: Boolean) =
        Pieces(
            if (gradient) this.gradient else point,
            IntArray(sizes.size) { model * sizes[it] },
            sizes,
        )

    /** The point of the [model] set to [theta], of [Bilinear]'s layout. */
    fun put(model: Int, theta: FloatArray) = pieces(model, gradient = false).write(Floats.of(layout.ordered(theta)))

    /** The point of the [model], of [Bilinear]'s layout. */
    fun take(model: Int): FloatArray = taken(model, gradient = false)

    /** The gradient of the [model], of [Bilinear]'s layout. */
    fun slope(model: Int): FloatArray = taken(model, gradient = true)

    private fun taken(model: Int, gradient: Boolean): FloatArray {
        val own = Floats(layout.size)
        pieces(model, gradient).read(own)
        return layout.standard(own.toArray())
    }
}
