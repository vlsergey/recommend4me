package io.github.vlsergey.recommend4me.worklist

/**
 * SINCE WHEN EACH STAGE OF THE TASKS OVER THE WORKS HOLDS, of one index of the list ([ListIndex.stages]), kept with its
 * commits: the models of a source's fields fitted, a pass of the expectations begun, the index of the search made anew
 * — a document done of the stage before is to be done again ([WorkStages]). Read of the last commit as the index opens.
 */
class StageTimes internal constructor(kept: Map<String, String>) {
    /** The times by the stage's key, milliseconds; guarded by itself. */
    private val times = HashMap(kept)

    /** Since when the stage [what] holds; none when it never changed. */
    fun since(what: String): Long? = synchronized(times) { times[what]?.toLongOrNull() }

    /** The stage [what] holds since [at] ([since]), kept with the next commit of the index. */
    fun changed(what: String, at: Long) = synchronized(times) { times[what] = at.toString() }

    /** The times as the data of a commit. */
    internal fun data(): Map<String, String> = synchronized(times) { HashMap(times) }
}
