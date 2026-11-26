package io.github.vlsergey.recommend4me.capture

import io.github.vlsergey.recommend4me.database.source.tables.references.CAPTURED_PAGE
import io.github.vlsergey.recommend4me.source.PageParser
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

/** A kept page: its address, the item it is of, when it came and which parser read it. */
data class KeptPage(val url: String, val itemId: Long?, val capturedAt: Instant, val parserVersion: Int)

/** The pages one source keeps to read again when its parser changes, gzipped. */
class CapturedPageRepository(private val db: DSLContext) {

    /** The page [html] of [url], downloaded [now], kept with the version of the way it was read by. */
    fun keep(url: String, html: String, itemId: Long?, now: Instant, parserVersion: Int) {
        val packed = gzip(html)
        db.insertInto(CAPTURED_PAGE)
            .set(CAPTURED_PAGE.URL, url.take(URL_WIDTH))
            .set(CAPTURED_PAGE.ITEM_ID, itemId)
            .set(CAPTURED_PAGE.CAPTURED_AT, now)
            .set(CAPTURED_PAGE.PARSER_VERSION, parserVersion)
            .set(CAPTURED_PAGE.HTML, packed)
            .onDuplicateKeyUpdate()
            .set(CAPTURED_PAGE.ITEM_ID, itemId)
            .set(CAPTURED_PAGE.CAPTURED_AT, now)
            .set(CAPTURED_PAGE.PARSER_VERSION, parserVersion)
            .set(CAPTURED_PAGE.HTML, packed)
            .execute()
    }

    /**
     * The addresses of the pages read by another version than the way of reading of their day of the [parsers] has
     * ([PageParser]), oldest first.
     */
    fun misread(parsers: List<PageParser>): List<String> =
        db.select(CAPTURED_PAGE.URL)
            .from(CAPTURED_PAGE)
            .where(misreadOf(parsers))
            .orderBy(CAPTURED_PAGE.CAPTURED_AT)
            .fetch { it.value1()!! }

    /** Whether a page was read by another version than the way of reading of its day ([misread]). */
    fun anyMisread(parsers: List<PageParser>): Boolean = db.fetchExists(CAPTURED_PAGE, misreadOf(parsers))

    /**
     * The pages read by another version than the way of reading of their day: of every way, the pages downloaded from
     * its date till the next one's.
     */
    private fun misreadOf(parsers: List<PageParser>): Condition {
        val sorted = parsers.sortedBy { it.since ?: Instant.MIN }
        return DSL.or(
            sorted.mapIndexed { i, p ->
                val until = sorted.getOrNull(i + 1)?.since
                (p.since?.let { CAPTURED_PAGE.CAPTURED_AT.ge(it) } ?: DSL.noCondition())
                    .and(until?.let { CAPTURED_PAGE.CAPTURED_AT.lt(it) } ?: DSL.noCondition())
                    .and(CAPTURED_PAGE.PARSER_VERSION.ne(p.version))
            }
        )
    }

    /** The page with its HTML. */
    fun read(url: String): Pair<KeptPage, String>? =
        db.selectFrom(CAPTURED_PAGE).where(CAPTURED_PAGE.URL.eq(url)).fetchOne()?.let {
            KeptPage(it.url!!, it.itemId, it.capturedAt!!, it.parserVersion!!) to gunzip(it.html!!)
        }

    fun markRead(url: String, version: Int) {
        db.update(CAPTURED_PAGE).set(CAPTURED_PAGE.PARSER_VERSION, version).where(CAPTURED_PAGE.URL.eq(url)).execute()
    }

    /** The pages kept last, newest first. */
    fun recent(limit: Int): List<KeptPage> =
        db.select(CAPTURED_PAGE.URL, CAPTURED_PAGE.ITEM_ID, CAPTURED_PAGE.CAPTURED_AT, CAPTURED_PAGE.PARSER_VERSION)
            .from(CAPTURED_PAGE)
            .orderBy(CAPTURED_PAGE.CAPTURED_AT.desc())
            .limit(limit)
            .fetch { KeptPage(it.value1()!!, it.value2(), it.value3()!!, it.value4()!!) }

    companion object {
        /** The longest address of a page kept: the width of its column. */
        private const val URL_WIDTH = 2000

        fun gzip(text: String): ByteArray {
            val bytes = ByteArrayOutputStream()
            GZIPOutputStream(bytes).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            return bytes.toByteArray()
        }

        fun gunzip(bytes: ByteArray): String =
            GZIPInputStream(bytes.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }
    }
}
