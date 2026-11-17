package io.github.vlsergey.recommend4me.model

import io.github.vlsergey.recommend4me.database.model.tables.references.SCORE_SWEEP
import java.time.Instant
import org.jooq.DSLContext

/**
 * Where a sweep of the scores over every work stands ([ScoreSweep]): the [model] it scores by, as when it was trained;
 * the [basis] of the vectors the average work is counted of ([AverageWork.basis]); the next portion of the works ([at]:
 * none of a sweep just begun); since when it goes, how many items it scored and how many of their predictions changed;
 * the sums of the average work counted so far ([AverageCount.pack]; none of a sweep just begun).
 */
class SweepState(
    val model: Instant,
    val basis: String,
    val at: PortionCursor?,
    val began: Instant,
    val scored: Int,
    val changed: Int,
    val counted: ByteArray?,
)

/** Where the sweep of the scores of a content type stands, in its model database: one row while one goes on. */
class ScoreSweepRepository(private val db: DSLContext) {

    fun load(): SweepState? =
        db.select(
                SCORE_SWEEP.MODEL_TRAINED_AT,
                SCORE_SWEEP.BASIS,
                SCORE_SWEEP.SOURCE,
                SCORE_SWEEP.AFTER_ID,
                SCORE_SWEEP.BEGAN_AT,
                SCORE_SWEEP.SCORED,
                SCORE_SWEEP.CHANGED,
                SCORE_SWEEP.COUNTED,
            )
            .from(SCORE_SWEEP)
            .where(SCORE_SWEEP.ID.eq(ONE))
            .fetchOne {
                SweepState(
                    it.value1()!!,
                    it.value2()!!,
                    it.value3()?.let { source -> PortionCursor(source, it.value4()) },
                    it.value5()!!,
                    it.value6()!!,
                    it.value7()!!,
                    it.value8(),
                )
            }

    /** The sweep as it stands now, written over the one before. */
    fun save(state: SweepState) {
        db.insertInto(SCORE_SWEEP)
            .set(SCORE_SWEEP.ID, ONE)
            .set(SCORE_SWEEP.MODEL_TRAINED_AT, state.model)
            .set(SCORE_SWEEP.BASIS, state.basis)
            .set(SCORE_SWEEP.SOURCE, state.at?.source)
            .set(SCORE_SWEEP.AFTER_ID, state.at?.after)
            .set(SCORE_SWEEP.BEGAN_AT, state.began)
            .set(SCORE_SWEEP.SCORED, state.scored)
            .set(SCORE_SWEEP.CHANGED, state.changed)
            .set(SCORE_SWEEP.COUNTED, state.counted)
            .onDuplicateKeyUpdate()
            .set(SCORE_SWEEP.MODEL_TRAINED_AT, state.model)
            .set(SCORE_SWEEP.BASIS, state.basis)
            .set(SCORE_SWEEP.SOURCE, state.at?.source)
            .set(SCORE_SWEEP.AFTER_ID, state.at?.after)
            .set(SCORE_SWEEP.BEGAN_AT, state.began)
            .set(SCORE_SWEEP.SCORED, state.scored)
            .set(SCORE_SWEEP.CHANGED, state.changed)
            .set(SCORE_SWEEP.COUNTED, state.counted)
            .execute()
    }

    /** The sweep done, or of no model: none goes on. */
    fun forget() {
        db.deleteFrom(SCORE_SWEEP).execute()
    }

    private companion object {
        /** The one row. */
        const val ONE = 1
    }
}
