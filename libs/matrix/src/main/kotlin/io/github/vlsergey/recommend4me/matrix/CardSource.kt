package io.github.vlsergey.recommend4me.matrix

/**
 * WHERE THE CARD IS GOT: the products' library of the process ([Cublas.open]), opened by whoever
 * knows where its libraries are — the one ONNX runtime of the process, whose CUDA libraries they are
 * — and handed to whoever multiplies on it: the application, which puts the inputs of an operation
 * there, and the plugins. Null without a card, or when the launch did not ask for it.
 */
fun interface CardSource {
    fun cublas(): Cublas?
}
