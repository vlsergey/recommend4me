package io.github.vlsergey.recommend4me.database

/**
 * A COMPUTATION OF THE FACETS READS NOTHING OF THE DATABASE (user, 2026-11-12): what the chances of a facet are made of
 * — the works, the model kept, the dictionary's word — is read before, in one pass, and handed to the computation,
 * which works on that alone. A page of one work read the dictionary, the models and the works' values again and again
 * between the steps of its chances, a query at a time, and waited a second for them.
 *
 * [during] marks the thread of a computation for its time: every connection asked of the database on it
 * ([InlineBlobs.acquire]) fails at once — in the tests as on the user's machine — naming the computation that asked for
 * it, instead of quietly reading what was to be read before.
 */
object DatabaseRefused {
    /** The computation the current thread is in, if any. */
    private val computing = ThreadLocal<String?>()

    /** [computation], named [what], with the database refused to it. */
    fun <T> during(what: String, computation: () -> T): T {
        val outer = computing.get()
        computing.set(what)
        try {
            return computation()
        } finally {
            computing.set(outer)
        }
    }

    /** Fails when the current thread is in a computation that reads nothing of the database. */
    fun check() {
        val what = computing.get() ?: return
        error(
            "the database asked for during $what: a computation of the facets reads nothing of it — what it needs is " +
                "read before it and handed to it"
        )
    }
}
