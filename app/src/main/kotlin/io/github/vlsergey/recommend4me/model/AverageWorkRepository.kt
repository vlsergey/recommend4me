package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.database.model.tables.references.AVERAGE_WORK
import java.io.IOException
import java.time.Instant
import org.jooq.DSLContext
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger(AverageWorkRepository::class.java)

/**
 * THE AVERAGE WORK OF A CONTENT TYPE AS LAST COUNTED, in its model database: by the last pass of the expectations over
 * every work ([WorkPredictions]), or by the full pass of a training that found none ([EveryWork.averageWork]) — what a
 * training reads instead of every work. One of another [AverageWork.FORMAT], or counted of vectors made by another
 * basis ([AverageWork.basis]), is none.
 */
class AverageWorkRepository(private val db: DSLContext) {

    /** The average work kept, of the vectors of the [basis]; null when there is none such. */
    fun load(basis: String): AverageWork? =
        db.select(AVERAGE_WORK.FORMAT, AVERAGE_WORK.BASIS, AVERAGE_WORK.AVERAGE)
            .from(AVERAGE_WORK)
            .where(AVERAGE_WORK.ID.eq(ONE))
            .fetchOne()
            ?.takeIf { it.value1() == AverageWork.FORMAT && it.value2() == basis }
            ?.let { row ->
                try {
                    AverageWork.unpack(row.value3()!!)
                } catch (e: IOException) {
                    log.warn("The average work kept cannot be read, it is counted again: {}", e.message)
                    null
                }
            }

    /** The [average] counted of the vectors of the [basis] kept, the one kept before gone. */
    fun save(average: AverageWork, basis: String) {
        val bytes = average.pack()
        val now = Instant.now()
        db.insertInto(AVERAGE_WORK)
            .set(AVERAGE_WORK.ID, ONE)
            .set(AVERAGE_WORK.FORMAT, AverageWork.FORMAT)
            .set(AVERAGE_WORK.BASIS, basis)
            .set(AVERAGE_WORK.COUNTED_AT, now)
            .set(AVERAGE_WORK.AVERAGE, bytes)
            .onDuplicateKeyUpdate()
            .set(AVERAGE_WORK.FORMAT, AverageWork.FORMAT)
            .set(AVERAGE_WORK.BASIS, basis)
            .set(AVERAGE_WORK.COUNTED_AT, now)
            .set(AVERAGE_WORK.AVERAGE, bytes)
            .execute()
    }

    private companion object {
        /** The one row. */
        const val ONE = 1
    }
}
