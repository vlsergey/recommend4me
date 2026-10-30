package io.github.vlsergey.recommend4me.size

import io.github.vlsergey.recommend4me.database.model.tables.references.WORK_SIZE
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** How many words every normalized work has to a character, in the model database of its content type: made again by [WorkSizes]. */
class WorkSizeRepository(private val db: DSLContext) {

    /** The ratios kept of the normalized [works]. */
    fun of(works: Collection<Long>): Map<Long, Double> =
        works.distinct().chunked(BATCH).flatMap { chunk ->
            db.select(WORK_SIZE.WORK, WORK_SIZE.WORDS_PER_CHAR).from(WORK_SIZE).where(WORK_SIZE.WORK.`in`(chunk)).fetch { it.value1()!! to it.value2()!! }
        }.toMap()

    /** The [ratios] in place of the last. */
    fun keep(ratios: Map<Long, Double>) = db.transaction { c ->
        val tx = DSL.using(c)
        tx.deleteFrom(WORK_SIZE).execute()
        ratios.entries.chunked(BATCH).forEach { chunk ->
            val insert = tx.insertInto(WORK_SIZE, WORK_SIZE.WORK, WORK_SIZE.WORDS_PER_CHAR)
            chunk.forEach { (work, ratio) -> insert.values(work, ratio) }
            insert.execute()
        }
    }

    companion object {
        private const val BATCH = 1000
    }
}
