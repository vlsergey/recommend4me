package io.github.vlsergey.recommend4me.search

import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * THE FINGERPRINT A DOCUMENT IS KEPT WITH in the index ([LuceneIndex]): what it is made of, as a number — the
 * application's ([SearchDocument.fingerprint]), else the index's own of the document whole ([whole]). Equal of a work
 * unchanged since it was indexed.
 */
internal object DocumentPrints {
    /** The fingerprint the document [doc] is kept with. */
    fun of(doc: SearchDocument): Long = doc.fingerprint ?: whole(doc)

    /**
     * What a document is made of, as a number: its language, every field — its key, its name, its weight, how it is
     * read, its values — and every passage with its vector.
     */
    fun whole(doc: SearchDocument): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        fun put(text: String) {
            digest.update(text.toByteArray(Charsets.UTF_8))
            digest.update(0)
        }
        put(doc.language.orEmpty())
        doc.fields.forEach { f ->
            put(f.key)
            put(f.name)
            put(f.group)
            put(f.weight.toString())
            put("" + f.list + f.naming + f.counted)
            f.values.forEach(::put)
        }
        doc.passages.forEach { p ->
            put(p.field)
            put(p.from.toString())
            put(p.to.toString())
            p.vector?.let { v ->
                ByteBuffer.allocate(v.size * Float.SIZE_BYTES)
                    .also { it.asFloatBuffer().put(v) }
                    .array()
                    .let(digest::update)
            }
        }
        return ByteBuffer.wrap(digest.digest(), 0, Long.SIZE_BYTES).long
    }
}
