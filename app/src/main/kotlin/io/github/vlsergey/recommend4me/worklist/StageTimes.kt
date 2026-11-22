package io.github.vlsergey.recommend4me.worklist

/**
 * THE DATES OF ONE INDEX OF THE LIST, kept with its commits ([ListIndex.stages]): where its writing stands and up to
 * when its documents hold every change of the databases ([ListCursors]), up to when each stage of the tasks over the
 * works did every change it reads and where its sweep under way stands ([StageSweeps]), and the facts a stage tells by
 * them (the encoder the vectors were made by). Read of the last commit as the index opens.
 */
class StageTimes internal constructor(kept: Map<String, String>) {
    /** The times by their keys, milliseconds; guarded by itself. */
    private val times = HashMap(kept)

    /** The time of [what]; none when never told. */
    fun since(what: String): Long? = synchronized(times) { times[what]?.toLongOrNull() }

    /** The time of [what] is [at] ([since]), kept with the next commit of the index. */
    fun changed(what: String, at: Long) = synchronized(times) { times[what] = at.toString() }

    /** What is kept of [what] as it was told ([changed]); none when never told. */
    fun text(what: String): String? = synchronized(times) { times[what] }

    /** What is kept of [what] is [text], kept with the next commit of the index. */
    fun changed(what: String, text: String) = synchronized(times) { times[what] = text }

    /** The times of the keys [what] picks forgotten, with the next commit of the index. */
    fun forget(what: (String) -> Boolean) = synchronized(times) { times.keys.removeIf(what) }

    /** The keys of the times [what] picks. */
    fun keys(what: (String) -> Boolean): List<String> = synchronized(times) { times.keys.filter(what) }

    /** The times as the data of a commit. */
    internal fun data(): Map<String, String> = synchronized(times) { HashMap(times) }
}
