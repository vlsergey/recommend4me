package io.github.vlsergey.recommend4me.textvector

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.vector.Vectors

/** A text to encode: its item, its key, the text as the user corrected it and the hash it will be kept with. */
class TextToEncode(val itemId: String, val key: String, val text: String, val hash: String)

/**
 * THE TEXTS OF A SOURCE'S ITEMS AS THEY ARE NOW, held against the vectors kept by [encoder]: of every item, or of
 * [only] those — every text the models read or the search reads by meaning, and the title, as the user corrected them.
 * A text new or changed is to be encoded ([todo]); a text kept that is no more is gone ([gone]). Of one pass, kept
 * nowhere.
 */
class TextScan(private val store: SourceStore, private val only: Collection<String>?, encoder: String) {
    private val keys = store.schema.texts.filter { it.block != null || it.searchByMeaning }.map { it.key }
    private val stored =
        if (only == null) store.textVectors.hashes(encoder)
        else only.flatMap { store.textVectors.hashes(encoder, it).entries }.associate { it.toPair() }
    private val known =
        if (only == null) store.textVectors.keys() else only.flatMap { store.textVectors.keys(it) }.toSet()
    private val fields = if (only == null) store.corrections.allFields() else store.corrections.fieldsOf(only)

    /** The title of every item, as the user corrected it: a text the models read like any other. */
    private val titles =
        if (only == null) store.items.titles() else store.items.heads(only).mapValues { it.value.title }

    /** The texts new or changed since their vectors were made. */
    val todo = ArrayList<TextToEncode>()

    /** Every text there is now: item and key. */
    private val present = HashSet<Pair<String, String>>()

    init {
        if (only == null) {
            val considered = HashSet<String>()
            if (keys.isNotEmpty())
                store.items.forEachTexts(keys) { id, texts ->
                    considered += id
                    consider(id, texts)
                }
            // The items with a title and no other text
            titles.keys.filter { it !in considered }.forEach { consider(it, emptyMap()) }
        } else store.items.textsOf(only, keys).let { all -> only.forEach { id -> consider(id, all[id].orEmpty()) } }
    }

    /** The texts of the item [itemId], the site's [site] as the user corrected them, held against those kept. */
    private fun consider(itemId: String, site: Map<String, String>) {
        val own = fields[itemId].orEmpty()
        val texts =
            Corrected.texts(site, own) +
                listOfNotNull(titles[itemId]?.let { TextVectors.TITLE to Corrected.title(it, own) })
        (keys + TextVectors.TITLE).forEach { key ->
            val text = texts[key]?.takeIf { it.isNotBlank() } ?: return@forEach
            present += itemId to key
            val hash = Vectors.hash(text)
            if (stored[itemId to key] != hash) todo += TextToEncode(itemId, key, text, hash)
        }
    }

    /** The texts whose vectors are kept and that are no more: item and key. */
    fun gone(): List<Pair<String, String>> = known.filter { it !in present && (only == null || it.first in only) }
}
