package io.github.vlsergey.recommend4me.laps

/**
 * The time of every step of a computation, in the order taken: what a slow one is made of, in the log. A step of a part
 * handed on is named with the part's prefix ([within]).
 */
class Laps private constructor(private val prefix: String, private val laps: ArrayList<String>) {
    constructor() : this("", ArrayList())

    private var last = System.currentTimeMillis()

    fun lap(step: String, count: Int? = null) {
        val now = System.currentTimeMillis()
        laps += if (count == null) "$prefix$step ${now - last}" else "$prefix$step ${now - last} ($count)"
        last = now
    }

    /**
     * A part of the next lap timed apart — summed of its pieces done in turns with others: told before the lap, which
     * it is within.
     */
    fun took(step: String, millis: Long) {
        laps += "$prefix$step $millis"
    }

    /** The laps of a part, written among these, its steps named "[part] step"; time it from now. */
    fun within(part: String) = Laps("$prefix$part ", laps)

    /** Its next step timed from now: what was done since the last lap is told by another. */
    fun restart() {
        last = System.currentTimeMillis()
    }

    override fun toString() = laps.joinToString(", ")
}
