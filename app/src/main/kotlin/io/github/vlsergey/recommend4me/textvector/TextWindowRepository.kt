package io.github.vlsergey.recommend4me.textvector

import io.github.vlsergey.recommend4me.database.source.tables.references.TEXT_VECTOR
import io.github.vlsergey.recommend4me.database.source.tables.references.TEXT_WINDOW
import io.github.vlsergey.recommend4me.vector.Vectors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import org.jooq.DSLContext
import org.jooq.impl.DSL

/**
 * The windows of the long texts of one source's items: how many a text whose vector is kept has, and of one of more
 * than one the vector of every window — what the search finds a piece of a long text by. Written under the lock of the
 * texts' vectors ([writing]): a text's vector and its windows are written by one at a time.
 */
class TextWindowRepository(private val db: DSLContext, private val writing: ReentrantLock) {

    /**
     * How many windows the text whose vector is stored has ([count]), made by [by]
     * ([io.github.vlsergey.recommend4me.encoder.TextEncoder.windowsId]), and the vectors of them in order when more
     * than one — a text of one window is its vector alone.
     */
    fun saveWindows(itemId: String, textKey: String, count: Int, by: String, windows: List<FloatArray>) =
        writing.withLock {
            db.transaction { c ->
                val tx = c.dsl()
                tx.deleteFrom(TEXT_WINDOW)
                    .where(TEXT_WINDOW.ITEM_ID.eq(itemId), TEXT_WINDOW.TEXT_KEY.eq(textKey))
                    .execute()
                if (count > 1 && windows.isNotEmpty()) {
                    // One statement prepared and bound a window each
                    val batch =
                        tx.batch(
                            tx.insertInto(
                                    TEXT_WINDOW,
                                    TEXT_WINDOW.ITEM_ID,
                                    TEXT_WINDOW.TEXT_KEY,
                                    TEXT_WINDOW.WINDOW_NO,
                                    TEXT_WINDOW.VEC,
                                )
                                .values(null as String?, null as String?, null as Int?, null as ByteArray?)
                        )
                    windows.forEachIndexed { i, v -> batch.bind(itemId, textKey, i, Vectors.half(v)) }
                    batch.execute()
                }
                tx.update(TEXT_VECTOR)
                    .set(TEXT_VECTOR.WINDOWS, count)
                    .set(TEXT_VECTOR.WINDOWS_BY, by)
                    .where(TEXT_VECTOR.ITEM_ID.eq(itemId), TEXT_VECTOR.TEXT_KEY.eq(textKey))
                    .execute()
            }
        }

    /**
     * The texts of keys [keys] whose vector by [encoder] is stored and whose windows are not counted yet, or were made
     * by another than [by], at most [limit], in the order of the table's key from past [after] (from the first when
     * null): (item, text key). A pass goes on from the last it was given, not from the start of the table again a
     * batch.
     */
    fun withoutWindows(
        encoder: String,
        by: String,
        keys: Collection<String>,
        limit: Int,
        after: Pair<String, String>? = null,
    ): List<Pair<String, String>> =
        if (keys.isEmpty()) emptyList()
        else
            db.select(TEXT_VECTOR.ITEM_ID, TEXT_VECTOR.TEXT_KEY)
                .from(TEXT_VECTOR)
                .where(
                    TEXT_VECTOR.ENCODER.eq(encoder),
                    TEXT_VECTOR.WINDOWS.isNull.or(TEXT_VECTOR.WINDOWS_BY.isDistinctFrom(by)),
                    TEXT_VECTOR.TEXT_KEY.`in`(keys),
                )
                .and(
                    after?.let { (item, key) -> DSL.row(TEXT_VECTOR.ITEM_ID, TEXT_VECTOR.TEXT_KEY).gt(item, key) }
                        ?: DSL.noCondition()
                )
                .orderBy(TEXT_VECTOR.ITEM_ID, TEXT_VECTOR.TEXT_KEY)
                .limit(limit)
                .fetch { it.value1()!! to it.value2()!! }

    /**
     * The windows of the texts of the items [ids] that have more than one, of the texts' vectors by [encoder], made by
     * [by]: item to text key to windows in order.
     */
    fun windowsOf(ids: Collection<String>, encoder: String, by: String): Map<String, Map<String, List<FloatArray>>> {
        val out = HashMap<String, MutableMap<String, MutableList<FloatArray>>>()
        ids.distinct().chunked(TextVectorRepository.BATCH).forEach { chunk ->
            db.select(TEXT_WINDOW.ITEM_ID, TEXT_WINDOW.TEXT_KEY, TEXT_WINDOW.VEC)
                .from(TEXT_WINDOW)
                .join(TEXT_VECTOR)
                .on(TEXT_VECTOR.ITEM_ID.eq(TEXT_WINDOW.ITEM_ID), TEXT_VECTOR.TEXT_KEY.eq(TEXT_WINDOW.TEXT_KEY))
                .where(TEXT_WINDOW.ITEM_ID.`in`(chunk), TEXT_VECTOR.ENCODER.eq(encoder), TEXT_VECTOR.WINDOWS_BY.eq(by))
                .orderBy(TEXT_WINDOW.ITEM_ID, TEXT_WINDOW.TEXT_KEY, TEXT_WINDOW.WINDOW_NO)
                .fetch {
                    out.getOrPut(it.value1()!!) { HashMap() }.getOrPut(it.value2()!!) { ArrayList() } +=
                        Vectors.fromHalf(it.value3()!!)
                }
        }
        return out
    }
}
