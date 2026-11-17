package io.github.vlsergey.recommend4me

/** The pause between two looks of [waitFor], in milliseconds. */
private const val LOOK_EVERY_MS = 50L

/**
 * Waits up to [seconds] for [condition] — what the queue of the computations does in the background; true when it came.
 */
fun waitFor(seconds: Int = 30, condition: () -> Boolean): Boolean {
    val until = System.currentTimeMillis() + seconds * 1000L
    while (System.currentTimeMillis() < until) {
        if (condition()) return true
        Thread.sleep(LOOK_EVERY_MS)
    }
    return condition()
}
