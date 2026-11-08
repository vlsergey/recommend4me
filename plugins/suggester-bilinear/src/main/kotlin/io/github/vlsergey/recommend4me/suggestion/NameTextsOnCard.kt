package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.CudaKernels
import io.github.vlsergey.recommend4me.matrix.Floats

/**
 * THE NAMES AS TEXTS OF A WORK ([Problem.hints]) ON THE CARD, of every model of an ensemble at once: what
 * [NamesOfEnsemble.coordinates] and [NamesOfEnsemble.gradient] make on the processor, where the weights A and their
 * slopes lie already ([ItemProducts.OnCard]). Every text block of theirs holds the name, so a name's coordinates are
 * the name times the text blocks' weights summed, plus the constant's and the blocks' marks' — the sums by a kernel,
 * the product by cuBLAS, the names' texts put there once; the slopes back the same way, onto the slopes of A there.
 * Nothing of it crosses to the main memory: thirty billion multiplications a step of the characters' forty-five models
 * were the processor's.
 */
internal class NameTextsOnCard(
    p: Problem,
    private val cublas: Cublas,
    private val kernels: CudaKernels,
    room: CardRoom,
    count: Int,
    r: Int,
    /** Where the names' coordinates are written, and their slopes read: [PairsOnCard]'s. */
    private val uh: Cublas.DeviceFloats,
    private val duh: Cublas.DeviceFloats,
) {
    private val h = p.hints.size
    private val hd = p.hintDim
    private val dx = p.design.itemWidth
    private val blocks = p.hintBlocks.size
    private val offsets = room.ints(IntArray(blocks) { p.design.blockOffsets[p.hintBlocks[it]] })
    private val scales = room.floatsOf(FloatArray(blocks) { p.design.blocks[p.hintBlocks[it]].scale })

    /** The names' texts, a row of each: on the card once for the fitting. */
    private val texts = room.floats(h.toLong() * hd).also { it.upload(p.hintText.held, 0, 0, h * hd) }
    private val sumT = room.floats(count.toLong() * r * hd)
    private val dSum = room.floats(count.toLong() * r * hd)
    private val base = room.floats(count.toLong() * r)
    private val total = room.floats(count.toLong() * r)
    private val ones =
        room.floats(h.toLong()).also { o -> o.upload(Floats(h).also { f -> for (i in 0 until h) f[i] = 1f }, 0, 0, h) }

    /** The names' coordinates of the first [kr] rows of the weights [aT]: Uhᵀ = (Σ_b s_b A_bᵀ) Hᵀ + the base. */
    fun coordinates(kr: Int, aT: Cublas.DeviceFloats) {
        kernels.launch("names_sum", kr.toLong() * hd, kr, dx, hd, blocks, offsets, scales, aT, sumT, base)
        cublas.gemm(false, true, kr, h, hd, sumT, 0, hd, texts, 0, hd, uh, 0, h)
        kernels.launch("add_base", kr.toLong() * h, kr, h, base, uh)
    }

    /**
     * The names' slopes of the first [kr] rows carried back onto the slopes [gA] of the text blocks' weights, the
     * constant's and the marks'.
     */
    fun gradient(kr: Int, gA: Cublas.DeviceFloats) {
        cublas.gemm(false, false, kr, hd, h, duh, 0, h, texts, 0, hd, dSum, 0, hd)
        cublas.gemm(false, false, kr, 1, h, duh, 0, h, ones, 0, 1, total, 0, 1)
        kernels.launch("names_back", kr.toLong() * hd, kr, dx, hd, blocks, offsets, scales, dSum, total, gA)
    }
}
