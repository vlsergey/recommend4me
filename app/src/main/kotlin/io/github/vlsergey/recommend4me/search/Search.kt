package io.github.vlsergey.recommend4me.search

import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.item.ItemKey
import io.github.vlsergey.recommend4me.item.ItemsChanged
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.Stores
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.textvector.PhraseVectors
import io.github.vlsergey.recommend4me.textvector.TextVectors
import io.github.vlsergey.recommend4me.textvector.TextVectorsChanged
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val log = LoggerFactory.getLogger(Search::class.java)

/**
 * The search over the works of a content type, by the search plugin: the documents of the items —
 * their corrected titles, facets and texts with the vectors of the texts — made here, the index
 * the plugin's. The whole index is made again on every start, in the background; an item written
 * since it was indexed is indexed again before the next search (the queue of their keys is all
 * that is kept here).
 */
@Service
class Search(
    private val stores: Stores,
    private val plugins: Plugins,
    private val textVectors: TextVectors,
    private val phrases: PhraseVectors,
) {
    private val pending = ConcurrentHashMap<String, MutableSet<ItemKey>>()
    private val built = ConcurrentHashMap<String, CountDownLatch>()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "search-index").apply { isDaemon = true } }

    @EventListener
    fun itemsChanged(event: ItemsChanged) = queue(event.source, event.ids)

    @EventListener
    fun vectorsChanged(event: TextVectorsChanged) = queue(event.source, event.ids)

    private fun queue(source: String, ids: Collection<String>) {
        val type = stores.typeOf(source).id
        pending.computeIfAbsent(type) { ConcurrentHashMap.newKeySet() } += ids.map { ItemKey(source, it) }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun build() {
        val provider = plugins.search ?: return
        stores.types.forEach { built[it.id] = CountDownLatch(1) }
        worker.execute {
            stores.types.forEach { type ->
                try {
                    provider.rebuild(type.id, documentsOf(type))
                } catch (e: Exception) {
                    log.error("{}: the search index was not made", type.id, e)
                } finally {
                    built[type.id]?.countDown()
                }
            }
        }
    }

    /** Every document of a type, streamed: the items read a chunk at a time. */
    private fun documentsOf(type: TypeStore): Sequence<SearchDocument> = sequence {
        for (s in type.sources) {
            val ids = s.items.keys().map { it.id }
            for (chunk in ids.chunked(CHUNK)) yieldAll(documents(s, chunk).values)
        }
    }

    /** The documents of the items [ids] of a source, read in one batch per table. */
    fun documents(s: SourceStore, ids: List<String>): Map<ItemKey, SearchDocument> {
        val schema = s.source.schema
        val heads = s.items.heads(ids)
        val facets = s.items.facetsOf(ids)
        val facetCorrections = s.corrections.facetsOf(ids)
        val fields = s.corrections.fieldsOf(ids)
        val texts = s.items.textsOf(ids)
        val searchedFacets = schema.facets.filter { it.searchWeight > 0 }
        val names = searchedFacets.associate { def ->
            def.key to s.items.facetNames(def.key, ids.flatMap { facets[it]?.get(def.key).orEmpty() }) +
                facetCorrections.values.flatten().filter { it.facet == def.key && it.name != null }.associate { it.key to it.name!! }
        }
        val vectors = plugins.textEncoder()?.let { e -> s.textVectors.ofMany(ids, e.id) }.orEmpty()
        val meaningful = schema.texts.filter { it.searchByMeaning }.map { it.key }.toSet()
        return heads.mapNotNull { (id, head) ->
            val own = fields[id].orEmpty()
            val itemFacets = Corrected.facets(facets[id].orEmpty(), facetCorrections[id].orEmpty())
            val itemTexts = Corrected.texts(texts[id].orEmpty(), own)
            val docFields = ArrayList<SearchField>()
            docFields += SearchField("title", "Название", 1.0f, listOf(Corrected.title(head.title, own)), naming = true)
            searchedFacets.forEach { def ->
                val keys = itemFacets[def.key].orEmpty()
                if (keys.isNotEmpty()) docFields += SearchField(
                    "facet.${def.key}", def.label, def.searchWeight, keys.map { names[def.key]?.get(it) ?: it }, list = true, naming = def.names,
                )
            }
            schema.texts.filter { it.searchWeight > 0 }.forEach { def ->
                itemTexts[def.key]?.let { docFields += SearchField(def.key, def.label, def.searchWeight, listOf(it)) }
            }
            val docVectors = vectors[id].orEmpty().filterKeys { it in meaningful }.map { (k, v) -> SearchVector(k, v) }
            ItemKey(s.id, id) to SearchDocument(ItemKey(s.id, id), docFields, docVectors)
        }.toMap()
    }

    /** The items written since they were indexed, indexed now — once the index of the start is made. */
    private fun catchUp(type: TypeStore) {
        val provider = plugins.search ?: return
        built[type.id]?.let { if (it.count > 0) return }
        val keys = pending[type.id]?.let { set -> set.toList().also { set.removeAll(it.toSet()) } }.orEmpty()
        if (keys.isEmpty()) return
        val docs = HashMap<ItemKey, SearchDocument>()
        keys.groupBy { it.source }.forEach { (source, list) ->
            stores.source(source)?.let { docs += documents(it, list.map { k -> k.id }) }
        }
        provider.update(type.id, docs.values.toList(), keys.filter { it !in docs })
    }

    private fun context(type: TypeStore) = object : SearchContext {
        override fun queryVector(line: String): FloatArray? = textVectors.query(line)
        override fun phraseVectors(texts: Collection<String>): Map<String, FloatArray> =
            type.sources.firstOrNull()?.let { phrases.of(it, texts) }.orEmpty()
        override fun documents(keys: Collection<ItemKey>): Map<ItemKey, SearchDocument> =
            keys.groupBy { it.source }.flatMap { (source, list) ->
                stores.source(source)?.let { documents(it, list.map { k -> k.id }).entries }.orEmpty()
            }.associate { it.key to it.value }
    }

    /** The works matching [line], most relevant first; null without a search plugin. */
    fun search(typeId: String, line: String): SearchResult? {
        val provider = plugins.search ?: return null
        val type = stores.type(typeId) ?: return null
        built[typeId]?.await(WAIT_FOR_INDEX_SECONDS, TimeUnit.SECONDS)
        catchUp(type)
        return provider.search(typeId, line, context(type))
    }

    fun matches(typeId: String, result: SearchResult, keys: Collection<ItemKey>): Map<ItemKey, SearchMatch> {
        val provider = plugins.search ?: return emptyMap()
        val type = stores.type(typeId) ?: return emptyMap()
        return provider.matches(typeId, result, keys, context(type))
    }

    @PreDestroy
    fun shutdown() {
        worker.shutdownNow()
    }

    companion object {
        private const val CHUNK = 500

        /** How long a search waits for the index of the start; the index of the last run serves meanwhile. */
        private const val WAIT_FOR_INDEX_SECONDS = 0L
    }
}
