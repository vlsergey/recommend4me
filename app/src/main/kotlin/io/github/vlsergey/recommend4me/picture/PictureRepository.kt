package io.github.vlsergey.recommend4me.picture

import io.github.vlsergey.recommend4me.database.source.tables.records.PictureRecord
import io.github.vlsergey.recommend4me.database.source.tables.references.ITEM_PICTURE_VECTOR
import io.github.vlsergey.recommend4me.database.source.tables.references.PICTURE
import io.github.vlsergey.recommend4me.vector.Vectors
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL

data class Picture(
    val itemId: String,
    val position: Int,
    val url: String,
    val previewFile: String?,
    val fullFile: String?,
    /** Analysed by the encoder in use. */
    val analyzed: Boolean,
    val error: String?,
    /** Its downloads failed in a row: what the next wait is counted by ([PictureQueue.saveError]). */
    val failures: Int,
)

/**
 * The pictures of the items of one source: what is known of each, what of it is on disk, and its vector by the picture
 * encoder whose id is kept with it. Vectors of another encoder are of another space: a picture counts as analysed only
 * by the encoder in use. The pictures waiting for the encoder are in [queue], their vectors one by one in [analysed],
 * the vectors of every item made of them in [itemVectors].
 */
class PictureRepository(private val db: DSLContext) {

    /** The stored vectors of the items' pictures. */
    val itemVectors = ItemPictureVectors(db)

    /** The pictures waiting for the encoder, and what it made of them. */
    val queue = PictureQueue(db, itemVectors)

    /** The vectors of the analysed pictures, one by one. */
    val analysed = AnalysedPictures(db)

    /**
     * The pictures of the item as the site lists them now, by position: 0 the cover, 1.. the screenshots; a null leaves
     * its position empty. A picture whose address changed is a new picture — its files and vector are forgotten.
     * Returns the files no longer anyone's.
     */
    fun sync(itemId: String, urls: List<String?>): List<String> {
        val wanted = urls.withIndex().filter { it.value != null }.associate { it.index to it.value!! }
        return db.transactionResult { c ->
                val tx = DSL.using(c)
                val known =
                    tx.selectFrom(PICTURE).where(PICTURE.ITEM_ID.eq(itemId)).fetch().associateBy { it.position!! }
                val orphans = ArrayList<String>()
                var vectorsChanged = false
                wanted.forEach { (position, url) ->
                    val old = known[position]
                    if (old == null) {
                        // Another page of the item read at the same moment may have added it already
                        tx.insertInto(PICTURE)
                            .set(PICTURE.ITEM_ID, itemId)
                            .set(PICTURE.POSITION, position)
                            .set(PICTURE.URL, url.take(URL_LENGTH))
                            .onDuplicateKeyIgnore()
                            .execute()
                    } else if (old.url != url.take(URL_LENGTH)) {
                        orphans += listOfNotNull(old.previewFile, old.fullFile)
                        tx.update(PICTURE)
                            .set(PICTURE.URL, url.take(URL_LENGTH))
                            .setNull(PICTURE.PREVIEW_FILE)
                            .setNull(PICTURE.FULL_FILE)
                            .setNull(PICTURE.VEC)
                            .setNull(PICTURE.ENCODER)
                            .setNull(PICTURE.ANALYZED_AT)
                            .setNull(PICTURE.ERROR)
                            .set(PICTURE.FAILURES, 0)
                            .setNull(PICTURE.RETRY_AT)
                            .where(PICTURE.ITEM_ID.eq(itemId), PICTURE.POSITION.eq(position))
                            .execute()
                        if (old.vec != null) vectorsChanged = true
                    }
                }
                val gone = known.keys - wanted.keys
                gone.forEach { position ->
                    known.getValue(position).let { orphans += listOfNotNull(it.previewFile, it.fullFile) }
                }
                if (gone.isNotEmpty()) {
                    tx.deleteFrom(PICTURE).where(PICTURE.ITEM_ID.eq(itemId), PICTURE.POSITION.`in`(gone)).execute()
                    if (gone.any { known.getValue(it).vec != null }) vectorsChanged = true
                }
                if (vectorsChanged)
                    tx.deleteFrom(ITEM_PICTURE_VECTOR).where(ITEM_PICTURE_VECTOR.ITEM_ID.eq(itemId)).execute()
                orphans
            }
            .also { refreshItemVectorsIfAny(itemId) }
    }

    private fun refreshItemVectorsIfAny(itemId: String) {
        val encoder =
            db.select(PICTURE.ENCODER)
                .from(PICTURE)
                .where(PICTURE.ITEM_ID.eq(itemId), PICTURE.ENCODER.isNotNull)
                .limit(1)
                .fetchOne(PICTURE.ENCODER)
        if (encoder != null) itemVectors.refreshItemVectors(listOf(itemId), encoder)
    }

    fun count(): Int = db.fetchCount(PICTURE)

    /**
     * The pictures analysed by [encoder]; counted by the encoder alone, which its index answers without reading the
     * rows.
     */
    fun countAnalyzed(encoder: String): Int = db.fetchCount(PICTURE, PICTURE.ENCODER.eq(encoder))

    fun countFailed(): Int = db.fetchCount(PICTURE, PICTURE.ERROR.isNotNull)

    fun saveFiles(itemId: String, position: Int, previewFile: String?, fullFile: String?) {
        db.update(PICTURE)
            .set(PICTURE.PREVIEW_FILE, previewFile)
            .set(PICTURE.FULL_FILE, fullFile)
            .where(PICTURE.ITEM_ID.eq(itemId), PICTURE.POSITION.eq(position))
            .execute()
    }

    fun find(itemId: String, position: Int, encoder: String?): Picture? =
        db.selectFrom(PICTURE).where(PICTURE.ITEM_ID.eq(itemId), PICTURE.POSITION.eq(position)).fetchOne {
            pictureOf(it, encoder)
        }

    fun ofItem(itemId: String, encoder: String?): List<Picture> =
        db.selectFrom(PICTURE).where(PICTURE.ITEM_ID.eq(itemId)).orderBy(PICTURE.POSITION).fetch {
            pictureOf(it, encoder)
        }

    /** How many pictures every item of [ids] has, and whether it has a cover. */
    fun countsOf(ids: Collection<String>): Map<String, Pair<Int, Boolean>> {
        val out = HashMap<String, Pair<Int, Boolean>>()
        ids.distinct().chunked(PICTURES_BATCH).forEach { chunk ->
            db.select(PICTURE.ITEM_ID, PICTURE.POSITION).from(PICTURE).where(PICTURE.ITEM_ID.`in`(chunk)).fetch { r ->
                val (n, cover) = out[r.value1()!!] ?: (0 to false)
                out[r.value1()!!] = (n + 1) to (cover || r.value2() == 0)
            }
        }
        return out
    }

    /** Drops the originals of an item; returns the files to delete. */
    fun forgetOriginals(itemId: String): List<String> {
        val files =
            db.select(PICTURE.FULL_FILE)
                .from(PICTURE)
                .where(PICTURE.ITEM_ID.eq(itemId), PICTURE.FULL_FILE.isNotNull)
                .fetch { it.value1()!! }
        db.update(PICTURE).setNull(PICTURE.FULL_FILE).where(PICTURE.ITEM_ID.eq(itemId)).execute()
        return files
    }

    companion object {
        /** The longest address of a picture its column holds. */
        private const val URL_LENGTH = 2000

        const val COVER = "img:cover"
        const val SCREENS = "img:screens"

        /** Cover alone; the screenshots as their mean direction, unit again. */
        fun combine(list: List<Pair<Int, FloatArray>>): Map<String, FloatArray> {
            val out = HashMap<String, FloatArray>()
            list.firstOrNull { it.first == 0 }?.let { out[COVER] = it.second }
            Vectors.meanDirection(list.filter { it.first > 0 }.map { it.second })?.let { out[SCREENS] = it }
            return out
        }
    }
}

/** The items read by one query at most: a list of keys in a query, not a meaning. */
internal const val PICTURES_BATCH = 500

/** The picture of the row, analysed when its vector is of [encoder]. */
internal fun pictureOf(r: PictureRecord, encoder: String?) =
    Picture(
        itemId = r.itemId!!,
        position = r.position!!,
        url = r.url!!,
        previewFile = r.previewFile,
        fullFile = r.fullFile,
        analyzed = r.vec != null && encoder != null && r.encoder == encoder,
        error = r.error,
        failures = r.failures!!,
    )

/** A picture with a vector of [encoder]. */
internal fun analyzedBy(encoder: String): Condition = PICTURE.VEC.isNotNull.and(PICTURE.ENCODER.eq(encoder))
