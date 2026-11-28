package io.github.vlsergey.recommend4me.database

/**
 * WHAT READS BY KEYS ALONE, A WHOLE TABLE REFUSED TO IT: a work's card (owner, 2026-11-19: "you have the input: the
 * work alone. You run it through the cascade of the models. You get a set of likely candidates, their ids. By the ids
 * you load their names of the dictionary. An attempt to load ALL the values of any dictionary while the card of one
 * work is loading must end in an error"), and a work of the queue that takes what it is to do by dates or by keys
 * (owner, 2026-11-27: "every task with inputs remembers the date of the inputs it ran for, and the works are chosen by
 * it"; [io.github.vlsergey.recommend4me.task.TaskInputs]). Of ficbook's work of two sites a page took 2.6 s, nearly all
 * of it the dictionary of the fandoms read whole for every facet; the manners of the works read the part marks of every
 * one of the 1.38 million items of the books to find the one chapter read.
 *
 * [during] marks the thread for its time: a reading of every row of a table ([check], asked by every such reading)
 * fails at once — in the tests as on the user's machine — naming the reading and what refused it. [lifted] lifts it for
 * a part that reads whole by its meaning — a model fitted within a stage.
 */
object WholeReadRefused {
    /** What the current thread does that reads by keys alone, if anything. */
    private val refusing = ThreadLocal<String?>()

    /** [made], named [what], with every reading of a whole table refused to it. */
    fun <T> during(what: String, made: () -> T): T = within(what, made)

    /** [made] free to read whole, whatever the thread does around it. */
    fun <T> lifted(made: () -> T): T = within(null, made)

    private fun <T> within(what: String?, made: () -> T): T {
        val outer = refusing.get()
        refusing.set(what)
        try {
            return made()
        } finally {
            refusing.set(outer)
        }
    }

    /** What [read] reads, of every row of a table ([reading]): refused as [check] refuses it. */
    fun <T> checked(reading: String, read: () -> T): T {
        check(reading)
        return read()
    }

    /** Fails when the current thread reads by keys alone: [reading] reads every row of a table. */
    fun check(reading: String) {
        val what = refusing.get() ?: return
        error("$reading read whole during $what: it reads by dates or by keys alone")
    }
}
