package io.github.vlsergey.recommend4me.manner

import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * Where a reading of the authors or the series in the order of the changes of their works' lists stands: after the
 * group [id] whose list changed [at].
 */
data class GroupCursor(val at: Instant, val id: Long) {
    /**
     * As kept among the marks of the index of the list: the date whole, to its last digit as the database keeps it — of
     * it cut to milliseconds, every group of one date after it, the reading never past them (2026-11-27: the 4726
     * series a migration dated alike, the same thousand taken again and again).
     */
    override fun toString() = "$at|$id"

    companion object {
        /**
         * The cursor kept as [text]; none of none, and of one kept cut to milliseconds by the version before — it never
         * got past its first portion: the reading begins from the first.
         */
        fun parse(text: String?): GroupCursor? =
            text?.split('|', limit = 2)?.let { (at, id) ->
                try {
                    GroupCursor(Instant.parse(at), id.toLong())
                } catch (_: DateTimeParseException) {
                    null
                }
            }
    }
}
