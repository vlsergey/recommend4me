package io.github.vlsergey.recommend4me.capture

import io.github.vlsergey.recommend4me.database.source.tables.references.CAPTURED_PAGE
import org.jooq.DSLContext
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** A kept page: its address, the item it is of, when it came and which parser read it. */
data class KeptPage(val url: String, val itemId: String?, val capturedAt: Instant, val parserVersion: Int)

/** The pages one source keeps to read again when its parser changes, gzipped. */
class CapturedPageRepository(private val db: DSLContext) {

    fun keep(url: String, html: String, itemId: String?, now: Instant, parserVersion: Int) {
        val packed = gzip(html)
        db.insertInto(CAPTURED_PAGE)
            .set(CAPTURED_PAGE.URL, url.take(2000)).set(CAPTURED_PAGE.ITEM_ID, itemId).set(CAPTURED_PAGE.CAPTURED_AT, now)
            .set(CAPTURED_PAGE.PARSER_VERSION, parserVersion).set(CAPTURED_PAGE.HTML, packed)
            .onDuplicateKeyUpdate()
            .set(CAPTURED_PAGE.ITEM_ID, itemId).set(CAPTURED_PAGE.CAPTURED_AT, now)
            .set(CAPTURED_PAGE.PARSER_VERSION, parserVersion).set(CAPTURED_PAGE.HTML, packed)
            .execute()
    }

    /** The addresses of the pages read by a parser older than [version], oldest first. */
    fun older(version: Int): List<String> =
        db.select(CAPTURED_PAGE.URL).from(CAPTURED_PAGE).where(CAPTURED_PAGE.PARSER_VERSION.lt(version))
            .orderBy(CAPTURED_PAGE.CAPTURED_AT).fetch { it.value1()!! }

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
        db.select(CAPTURED_PAGE.URL, CAPTURED_PAGE.ITEM_ID, CAPTURED_PAGE.CAPTURED_AT, CAPTURED_PAGE.PARSER_VERSION).from(CAPTURED_PAGE)
            .orderBy(CAPTURED_PAGE.CAPTURED_AT.desc()).limit(limit)
            .fetch { KeptPage(it.value1()!!, it.value2(), it.value3()!!, it.value4()!!) }

    companion object {
        fun gzip(text: String): ByteArray {
            val bytes = ByteArrayOutputStream()
            GZIPOutputStream(bytes).use { it.write(text.toByteArray(Charsets.UTF_8)) }
            return bytes.toByteArray()
        }

        fun gunzip(bytes: ByteArray): String = GZIPInputStream(bytes.inputStream()).use { String(it.readBytes(), Charsets.UTF_8) }
    }
}
