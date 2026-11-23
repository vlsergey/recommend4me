package io.github.vlsergey.recommend4me.worklist

import java.time.Instant

/**
 * Where the writing of the list stands among the changes of a source's items, in their order — by the date of their
 * change, then by their key: after the item [id] changed [at] (none: among the items of no date of change).
 */
data class ItemCursor(val at: Instant?, val id: String)

/** Where the writing of the list stands among the changes of the normalized works: after the work [id] changed [at]. */
data class WorkCursor(val at: Instant, val id: Long)

/**
 * WHERE THE WRITING OF AN INDEX OF THE LIST STANDS (owner, 2026-11-22: "it picks of the database every document changed
 * after the mark, sorted, and puts them into the index in portions, moving its watermark"), kept with its commits
 * ([StageTimes]): of every source the item it wrote last in the order of their changes ([ItemCursor]), of the
 * normalized works the work ([WorkCursor]); and of every source the date up to which its documents hold every change of
 * the databases ([written]) — of its items and of their works —, what the stages take its works up to. Of none, nought:
 * every item of the source to be written, from the first.
 */
internal class ListCursors(private val index: ListIndex) {

    /** Where the writing of the source's items stands; none — from the first. */
    fun of(source: String): ItemCursor? =
        index.stages.text(itemKey(source))?.let { kept ->
            val (at, id) = kept.split(SEPARATOR, limit = 2)
            ItemCursor(at.takeIf { it.isNotEmpty() }?.let(Instant::parse), id)
        }

    /** The writing of the source's items stands at [cursor] — none: from the first —, kept with the next commit. */
    fun set(source: String, cursor: ItemCursor?) =
        if (cursor == null) index.stages.forget { it == itemKey(source) }
        else index.stages.changed(itemKey(source), "${cursor.at ?: ""}$SEPARATOR${cursor.id}")

    /** Where the writing of the normalized works stands; none — from the first. */
    fun works(): WorkCursor? =
        index.stages.text(WORKS)?.let { kept ->
            val (at, id) = kept.split(SEPARATOR, limit = 2)
            WorkCursor(Instant.parse(at), id.toLong())
        }

    /** The writing of the normalized works stands at [cursor], kept with the next commit. */
    fun setWorks(cursor: WorkCursor) = index.stages.changed(WORKS, "${cursor.at}$SEPARATOR${cursor.id}")

    /** Up to when the documents of the source hold every change of the databases, milliseconds; nought of none. */
    fun written(source: String): Long = index.stages.since(writtenKey(source)) ?: 0L

    /** The documents of the source hold every change up to [at], kept with the next commit. */
    fun written(source: String, at: Long) = index.stages.changed(writtenKey(source), at)

    /** Up to when the documents hold every change of the normalized works, milliseconds; nought of none. */
    fun worksWritten(): Long = index.stages.since(WORKS_WRITTEN) ?: 0L

    /** The documents hold every change of the normalized works up to [at], kept with the next commit. */
    fun worksWritten(at: Long) = index.stages.changed(WORKS_WRITTEN, at)

    /** Up to when the documents of the source hold every change of its items and of their works, milliseconds. */
    fun held(source: String): Long = minOf(written(source), worksWritten())

    companion object {
        /**
         * Every source and the works of the [index] to be written from the first — the index current as of nought —,
         * kept with its next commit.
         */
        fun nought(index: ListIndex) = index.stages.forget { it.startsWith(PREFIX) }

        private const val PREFIX = "list:"
        private const val WORKS = "${PREFIX}works"
        private const val WORKS_WRITTEN = "${PREFIX}worksWritten"
        private const val SEPARATOR = "|"

        private fun itemKey(source: String) = "${PREFIX}items:$source"

        private fun writtenKey(source: String) = "${PREFIX}written:$source"
    }
}
