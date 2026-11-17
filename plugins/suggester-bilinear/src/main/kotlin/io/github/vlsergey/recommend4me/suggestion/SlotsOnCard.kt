package io.github.vlsergey.recommend4me.suggestion

import io.github.vlsergey.recommend4me.matrix.Cublas
import io.github.vlsergey.recommend4me.matrix.Floats

/**
 * THE POINT AND THE GRADIENT OF EVERY SLOT OF AN ENSEMBLE ON THE CARD, where the products read and write them: five
 * pieces of each — the weights A, B, E, the biases, the pair's own weights — [sizes] numbers of each piece a slot, the
 * slots one after another ([point], [gradient]). A model's numbers are the pieces' one after another
 * ([TransposedLayout]): [size] of them.
 */
internal class SlotsOnCard(
    val point: List<Cublas.DeviceFloats>,
    val gradient: List<Cublas.DeviceFloats>,
    val sizes: IntArray,
) {
    init {
        require(point.size == PIECES && gradient.size == PIECES && sizes.size == PIECES) { "not five pieces" }
    }

    /** A model's numbers. */
    val size: Int = sizes.sum()

    /**
     * The pieces of the first [k] slots of the point but A — which the items' features' product sends
     * ([ItemProducts.coordinates]) — sent from the main memory's [points] (each slot's, [Ensemble]'s).
     */
    fun send(k: Int, points: Array<Pieces>) =
        moved(k, points) { device, at, host, hostAt, n -> device.upload(host, hostAt, at, n) }

    /** The pieces of the first [k] slots of the gradient but A — taken back by its own — taken into [grads]. */
    fun bring(k: Int, grads: Array<Pieces>) =
        moved(k, grads, gradient = true) { device, at, host, hostAt, n -> device.download(host, hostAt, at, n) }

    private inline fun moved(
        k: Int,
        pieces: Array<Pieces>,
        gradient: Boolean = false,
        move: (Cublas.DeviceFloats, Long, Floats, Int, Int) -> Unit,
    ) {
        val on = if (gradient) this.gradient else point
        for (slot in 0 until k) for (q in 1 until PIECES) {
            val host = pieces[slot]
            move(on[q], slot.toLong() * sizes[q], host.floats[q], host.at[q], sizes[q])
        }
    }

    companion object {
        /** A, B, E, the biases, the pair's own weights. */
        const val PIECES = 5
    }
}
