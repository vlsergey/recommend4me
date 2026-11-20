package io.github.vlsergey.recommend4me.series

import org.flywaydb.core.api.migration.BaseJavaMigration
import org.flywaydb.core.api.migration.Context

/**
 * THE WORDS "DIFFERENT" SAID BEFORE ON TWO WORKS OF ONE SERIES TAKEN BACK — done on the data once, on 2026-11-20 (36 of
 * 697), and nothing now: the start is loaded with no cleaning (owner, 2026-11-20); a word "different" on two works of
 * one series is not heeded where it is read, and is taken back as the works change ([SeriesDifferences]). Kept by its
 * number alone: Flyway refuses a history whose applied migration it cannot find.
 */
@Suppress("ClassName")
class V34__different_in_series_taken_back : BaseJavaMigration() {
    override fun migrate(context: Context) = Unit
}
