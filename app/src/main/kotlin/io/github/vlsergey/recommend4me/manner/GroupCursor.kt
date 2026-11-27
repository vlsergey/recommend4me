package io.github.vlsergey.recommend4me.manner

import java.time.Instant

/**
 * Where a reading of the authors or the series in the order of the changes of their works' lists stands: after the
 * group [id] whose list changed [at].
 */
data class GroupCursor(val at: Instant, val id: Long) {
    /** As kept among the marks of the index of the list. */
    override fun toString() = "${at.toEpochMilli()}|$id"

    companion object {
        /** The cursor kept as [text]; none of none. */
        fun parse(text: String?): GroupCursor? =
            text?.split('|', limit = 2)?.let { (at, id) -> GroupCursor(Instant.ofEpochMilli(at.toLong()), id.toLong()) }
    }
}
