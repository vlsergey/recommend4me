package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.Floats

/**
 * THE VALUES' COORDINATES OF EVERY MODEL OF AN ENSEMBLE ON THE CARD, and their slopes: Vᵀ = Bᵀ Zᵀ + Eᵀ made there of
 * the weights B and E sent, the values' features Z put there once; the slopes of the values there ([gE]), written by
 * the pairs and the names on the card ([PairsOnCard]), taken back, and those of B made of them there, dBᵀ = dVᵀ Z. What
 * [Ensemble.evaluate] makes on the processor — two products of hundreds of the models' rows by every value's features a
 * step — of [count] models of rank [r].
 */
internal class ValuesOnCard(p: Problem, private val cublas: Cublas, room: CardRoom, count: Int, private val r: Int) {
    private val m = p.m
    private val dz = p.design.valueWidth
    private val z = room.floats(m.toLong() * dz).also { it.upload(p.z.held, 0, 0, m * dz) }
    private val bT = room.floats(count.toLong() * r * dz)
    private val eT = room.floats(count.toLong() * r * m)
    private val gB = room.floats(count.toLong() * r * dz)

    /** The values' coordinates of every model, transposed: rank rows of every value. */
    val vT = room.floats(count.toLong() * r * m)

    /** Their slopes, written by the pairs and the names there. */
    val gE = room.floats(count.toLong() * r * m)

    /** The values' coordinates of the first [k] models made of their weights [bTHost] and [eTHost] sent. */
    fun coordinates(k: Int, bTHost: Floats, eTHost: Floats) {
        val kr = k * r
        bT.upload(bTHost, 0, 0, kr * dz)
        eT.upload(eTHost, 0, 0, kr * m)
        cublas.gemm(false, true, kr, m, dz, bT, 0, dz, z, 0, dz, vT, 0, m)
        cublas.axpy(1f, eT, 0, vT, 0, kr * m)
    }

    /**
     * The values' slopes of the first [k] models taken back into [gEHost], and those of B made of them into [gBHost].
     */
    fun gradient(k: Int, gEHost: Floats, gBHost: Floats) {
        val kr = k * r
        gE.download(gEHost, 0, 0, kr * m)
        cublas.gemm(false, false, kr, dz, m, gE, 0, m, z, 0, dz, gB, 0, dz)
        gB.download(gBHost, 0, 0, kr * dz)
    }
}
