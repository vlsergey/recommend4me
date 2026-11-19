package io.github.vlsergey.recommend4me.database

/**
 * A WORK'S CARD READS THAT WORK ALONE (owner, 2026-11-19: "you have the input: the work alone. You run it through the
 * cascade of the models. You get a set of likely candidates, their ids. By the ids you load their names of the
 * dictionary. An attempt to load ALL the values of any dictionary while the card of one work is loading must end in an
 * error"): its items, their values, their inputs and the models; of every dictionary the entries of the ids it names —
 * never every value of one. Of ficbook's work of two sites a page took 2.6 s, nearly all of it the dictionary of the
 * fandoms read whole for every facet, every work of several items grouped, every value of a facet counted.
 *
 * [during] marks the thread of a work's card for its time: a reading of every value of a dictionary ([check], asked by
 * every such reading) fails at once — in the tests as on the user's machine — naming the reading and the card.
 */
object WholeReadRefused {
    /** The card the current thread makes, if any. */
    private val card = ThreadLocal<String?>()

    /** [made], the card named [what], with every reading of a whole dictionary refused to it. */
    fun <T> during(what: String, made: () -> T): T {
        val outer = card.get()
        card.set(what)
        try {
            return made()
        } finally {
            card.set(outer)
        }
    }

    /** Fails when the current thread makes a work's card: [reading] reads every value of a dictionary. */
    fun check(reading: String) {
        val what = card.get() ?: return
        error("$reading read whole during $what: a work's card reads the values of its own ids alone")
    }
}
