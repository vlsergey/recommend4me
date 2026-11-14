package io.github.vlsergey.recommend4me.manner

import io.github.vlsergey.recommend4me.database.model.tables.references.GROUP_MANNER
import io.github.vlsergey.recommend4me.vector.Vectors
import org.jooq.DSLContext

/** The groups of works a manner is kept of: a normalized author's works, a normalized series' works. */
enum class MannerGroup {
    /** Of a normalized author, by the author's id. */
    AUTHOR,

    /** Of a normalized series, by the series' key. */
    SERIES,
}

/** A group's manner as made: the mean of the manners of its [works], made of what [madeOf] tells. */
class GroupManner(val madeOf: String, val works: Int, val vector: FloatArray)

/**
 * The manners of the normalized authors and of the normalized series of a content type, in its model database: every
 * one the mean of the manners of its works, with the words it was measured by and the hash of what it was made of.
 */
class GroupMannerRepository(private val db: DSLContext) {

    /** What the manner of every group of the [kind] was made of: key to the hash. */
    fun made(kind: MannerGroup): Map<String, String> =
        db.select(GROUP_MANNER.GROUP_KEY, GROUP_MANNER.MADE_OF)
            .from(GROUP_MANNER)
            .where(GROUP_MANNER.KIND.eq(kind.name))
            .fetch()
            .associate { it.value1()!! to it.value2()!! }

    fun save(kind: MannerGroup, key: String, wordsId: Long, manner: GroupManner) {
        val packed = Vectors.half(manner.vector)
        db.insertInto(GROUP_MANNER)
            .set(GROUP_MANNER.KIND, kind.name)
            .set(GROUP_MANNER.GROUP_KEY, key)
            .set(GROUP_MANNER.WORDS_ID, wordsId)
            .set(GROUP_MANNER.MADE_OF, manner.madeOf)
            .set(GROUP_MANNER.WORKS, manner.works)
            .set(GROUP_MANNER.VEC, packed)
            .onDuplicateKeyUpdate()
            .set(GROUP_MANNER.WORDS_ID, wordsId)
            .set(GROUP_MANNER.MADE_OF, manner.madeOf)
            .set(GROUP_MANNER.WORKS, manner.works)
            .set(GROUP_MANNER.VEC, packed)
            .execute()
    }

    /** The manners of the groups [keys] of the [kind] — none of whose works has a text now — taken away. */
    fun remove(kind: MannerGroup, keys: Collection<String>) {
        keys.chunked(BATCH).forEach { chunk ->
            db.deleteFrom(GROUP_MANNER)
                .where(GROUP_MANNER.KIND.eq(kind.name), GROUP_MANNER.GROUP_KEY.`in`(chunk))
                .execute()
        }
    }

    /** The manner of every group of the [kind] measured by the words [wordsId]: key to vector. */
    fun all(kind: MannerGroup, wordsId: Long): Map<String, FloatArray> =
        db.select(GROUP_MANNER.GROUP_KEY, GROUP_MANNER.VEC)
            .from(GROUP_MANNER)
            .where(GROUP_MANNER.KIND.eq(kind.name), GROUP_MANNER.WORDS_ID.eq(wordsId))
            .fetch()
            .associate { it.value1()!! to Vectors.fromHalf(it.value2()!!) }

    /** The manners of the groups [keys] of the [kind] measured by the words [wordsId]: key to vector. */
    fun of(kind: MannerGroup, keys: Collection<String>, wordsId: Long): Map<String, FloatArray> {
        val out = HashMap<String, FloatArray>()
        keys.distinct().chunked(BATCH).forEach { chunk ->
            db.select(GROUP_MANNER.GROUP_KEY, GROUP_MANNER.VEC)
                .from(GROUP_MANNER)
                .where(
                    GROUP_MANNER.KIND.eq(kind.name),
                    GROUP_MANNER.GROUP_KEY.`in`(chunk),
                    GROUP_MANNER.WORDS_ID.eq(wordsId),
                )
                .fetch { out[it.value1()!!] = Vectors.fromHalf(it.value2()!!) }
        }
        return out
    }

    private companion object {
        /** The groups of one statement: the length of a query's list, not a meaning. */
        const val BATCH = 500
    }
}
