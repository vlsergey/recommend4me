package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas

/**
 * THE VALUES' COORDINATES OF EVERY MODEL OF AN ENSEMBLE ON THE CARD, and their slopes: Vᵀ = Bᵀ Zᵀ + Eᵀ made there of
 * the weights B and E there, the values' features Z put there once; the slopes of the values there ([gE]), written by
 * the pairs and the names on the card ([PairsOnCard]), and those of B made of them there, dBᵀ = dVᵀ Z. What
 * [Ensemble.evaluate] makes on the processor — two products of hundreds of the models' rows by every value's features a
 * step — of [count] models of rank [r].
 */
internal class ValuesOnCard(p: Problem, private val cublas: Cublas, room: CardRoom, count: Int, private val r: Int) {
    private val m = p.m
    private val dz = p.design.valueWidth
    private val z = room.floats(m.toLong() * dz).also { it.upload(p.z.held, 0, 0, m * dz) }

    /** The weights B and E of every slot, and the slopes of B: where the searches write and read them. */
    val bT = room.floats(count.toLong() * r * dz)
    val eT = room.floats(count.toLong() * r * m)
    val gB = room.floats(count.toLong() * r * dz)

    /** The values' coordinates of every model, transposed: rank rows of every value. */
    val vT = room.floats(count.toLong() * r * m)

    /** Their slopes, written by the pairs and the names there. */
    val gE = room.floats(count.toLong() * r * m)

    /** The values' coordinates of the first [k] models made of their weights B and E here. */
    fun coordinates(k: Int) {
        val kr = k * r
        cublas.gemm(false, true, kr, m, dz, bT, 0, dz, z, 0, dz, vT, 0, m)
        cublas.axpy(1f, eT, 0, vT, 0, kr * m)
    }

    /** The slopes of B of the first [k] models made of the values' slopes here, dBᵀ = dVᵀ Z. */
    fun gradient(k: Int) = cublas.gemm(false, false, k * r, dz, m, gE, 0, m, z, 0, dz, gB, 0, dz)
}
