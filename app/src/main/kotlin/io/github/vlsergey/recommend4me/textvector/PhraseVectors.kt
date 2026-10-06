package io.github.vlsergey.recommend4me.textvector

import io.github.vlsergey.recommend4me.encoder.TextEncoder
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.vector.Vectors
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(PhraseVectors::class.java)

/**
 * Vectors of short texts — whole reviews, phrases of the search's snippets — kept in a source's
 * database by the key of the text, so a text is encoded once.
 */
@Component
class PhraseVectors(private val plugins: Plugins) {

    fun encoder(): TextEncoder? = plugins.textEncoder()

    /** The vector of every text, those not kept yet encoded now. */
    fun of(store: SourceStore, texts: Collection<String>): Map<String, FloatArray> {
        val encoder = encoder() ?: return emptyMap()
        val stored = cached(store, texts, encoder)
        val missing = texts.filter { it !in stored }.distinct()
        return if (missing.isEmpty()) stored else stored + encode(store, missing, encoder)
    }

    /**
     * The vectors of the texts kept already, nothing encoded now: a reader that can do without the
     * rest for a while — they are encoded in the background — and must not wait for them.
     */
    fun cached(store: SourceStore, texts: Collection<String>, encoder: TextEncoder? = encoder()): Map<String, FloatArray> {
        encoder ?: return emptyMap()
        val keyed = texts.distinct().associateBy(Vectors::keyOf)
        val vectors = store.textVectors.phrases(keyed.keys, encoder.id)
        return keyed.mapNotNull { (key, text) -> vectors[key]?.let { text to it } }.toMap()
    }

    private fun encode(store: SourceStore, texts: List<String>, encoder: TextEncoder): Map<String, FloatArray> {
        val started = System.currentTimeMillis()
        val out = HashMap<String, FloatArray>()
        texts.chunked(PART).forEach { list ->
            val vectors = encoder.encode(list)
            val rows = list.mapIndexed { i, text -> text to (vectors[i] ?: FloatArray(encoder.dim)) }
            store.textVectors.savePhrases(rows.map { (text, v) -> Vectors.keyOf(text) to v }, encoder.id)
            rows.forEach { (text, v) -> out[text] = Vectors.fromHalf(Vectors.half(v)) }
        }
        if (texts.size > PART) log.info("{}: {} short texts encoded in {} s", store.id, texts.size, (System.currentTimeMillis() - started) / 1000)
        return out
    }

    companion object {
        private const val PART = 2048
    }
}
