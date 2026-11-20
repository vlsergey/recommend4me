package io.github.vlsergey.recommend4me.size

import io.github.vlsergey.recommend4me.database.model.tables.references.WORK_SIZE_MODEL
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.time.Instant
import org.jooq.DSLContext

/**
 * The model of how many words a site item has to a character, in the model database of its content type: its weight of
 * every feature by the feature's name ([KeptRatios]) and its own numbers ([Variances]), packed in one row — read whole
 * by every reader, written whole by [WordsPerCharLearning] (owner, 2026-11-20: a row a weight, 345 thousand of them,
 * took 5 s to write).
 */
class WorkSizeRepository(private val db: DSLContext) {

    /** The model kept: of none fitted, no weight. */
    fun model(): KeptRatios = KeptRatios(weights())

    /** The weights kept by their names: of none fitted, none. */
    fun weights(): Map<String, Double> =
        db.select(WORK_SIZE_MODEL.CONTENT).from(WORK_SIZE_MODEL).limit(1).fetchOne()?.value1()?.let(::unpack)
            ?: emptyMap()

    /** The [weights] of the model fitted [now], in place of the last. */
    fun keep(weights: Map<String, Double>, now: Instant) = db.transaction { c ->
        c.dsl().deleteFrom(WORK_SIZE_MODEL).execute()
        c.dsl()
            .insertInto(WORK_SIZE_MODEL)
            .set(WORK_SIZE_MODEL.FITTED_AT, now)
            .set(WORK_SIZE_MODEL.CONTENT, pack(weights))
            .execute()
    }

    private fun pack(weights: Map<String, Double>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeInt(weights.size)
            weights.forEach { (name, weight) ->
                out.writeUTF(name)
                out.writeDouble(weight)
            }
        }
        return bytes.toByteArray()
    }

    private fun unpack(bytes: ByteArray): Map<String, Double> =
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            val n = input.readInt()
            HashMap<String, Double>(n * 2).also { map -> repeat(n) { map[input.readUTF()] = input.readDouble() } }
        }
}
