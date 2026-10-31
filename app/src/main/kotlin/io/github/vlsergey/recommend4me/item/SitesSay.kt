package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.model.SiteSaid
import io.github.vlsergey.recommend4me.api.model.SiteWord
import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.source.TypeStore
import io.github.vlsergey.recommend4me.tag.TagRepository
import io.github.vlsergey.recommend4me.universe.UniverseFacet
import io.github.vlsergey.recommend4me.universe.UniverseFacets
import io.github.vlsergey.recommend4me.work.Works
import org.springframework.stereotype.Component

/**
 * WHAT EVERY SITE OF A WORK SAYS OF A FIELD of the form, to stand right above the place of answering
 * on it: word by word as the site writes it, and what each word reached. A site that has no word on
 * the field is listed too, its silence told; a field no site can speak of has none listed.
 */
@Component
class SitesSay(private val works: Works, private val plugins: Plugins) {

    /** Of a facet of the layer ([FacetDef.editable]) of the [store]: what the [members] of the work say of it, those saying the most first. */
    fun of(type: TypeStore, store: SourceStore, members: List<ItemKey>): Map<String, List<SiteSaid>> {
        val fields = store.schema.facets.filter { it.editable }
        val encoder = plugins.imageEncoder()?.id
        val read = works.fullestFirst(type, members).mapNotNull { m ->
            val s = type.source(m.source) ?: return@mapNotNull null
            val head = s.items.find(m.id) ?: return@mapNotNull null
            Member(m, s, head.url, s.items.facets(m.id), s.items.texts(m.id), s.drafts.ofItems(listOf(m.id))[m.id].orEmpty())
        }
        val bySource = read.map { it.store }.distinctBy { it.id }
        val bound = bySource.associate { it.id to it.tags.bindingsOf(it.id) }
        val fandoms = bySource.associate { it.id to if (UniverseFacet.fandomFacet(it.schema) == null) emptyMap() else type.universes.fandomUniverses(it.id) }
        return fields.associate { field ->
            val speaking = read.any { m -> defOf(m.store, field).let { it.original != null || UniverseFacet.saidBy(it, m.store.schema).isNotEmpty() } }
            field.key to if (!speaking) emptyList() else read.map { m ->
                val def = defOf(m.store, field)
                val speakers = UniverseFacet.saidBy(def, m.store.schema)
                val words = speakers.flatMap { facet ->
                    val values = m.facets[facet].orEmpty()
                    val names = m.store.names(facet, values)
                    values.map { key ->
                        val name = names[key] ?: key
                        // The field's own word is searched for in it; the direction of pairs is no value of theirs
                        SiteWord(key, name, facet == speakers.first(), reaches(def, facet, key, name, m, bound[m.key.source].orEmpty(), fandoms[m.key.source].orEmpty()))
                    }
                }
                // A site that writes the field as one line and gave no word of it: the line as it is
                val line = if (words.isNotEmpty()) null else def.original?.let { m.texts[it] }
                SiteSaid(
                    source = m.key.source, item = m.key.id, url = m.url,
                    read = read(m, encoder),
                    words = words + listOfNotNull(line?.let { SiteWord(it, it, true) }),
                )
            }
        }
    }

    private fun defOf(store: SourceStore, field: FacetDef) = store.schema.facets.firstOrNull { it.key == field.key } ?: field

    /** Whether the page of the item was ever read: an item known by its card alone has nothing but its head. */
    private fun read(m: Member, encoder: String?): Boolean {
        val lines = m.store.schema.facets.mapNotNull { it.original }.toSet()
        return m.texts.keys.any { it !in lines } || m.store.parts.ofItem(m.key.id).isNotEmpty() ||
            m.store.reviews.countOf(m.key.id) > 0 || m.store.pictures.ofItem(m.key.id, encoder).isNotEmpty()
    }

    /**
     * The values of the field [def] a word reached, null when nothing tells it: a word of a site's
     * direction, of a fandom on the kind, a character's name on a site with no drafts.
     */
    private fun reaches(
        def: FacetDef, facet: String, key: String, name: String, m: Member,
        bound: Map<String, Long>, fandoms: Map<String, Set<String>>,
    ): List<String>? {
        val drafted = m.drafts.filter { it.facet == def.key && it.from == name }.map { it.key }
        val schema = m.store.schema
        return when {
            def.key == UniverseFacets.KIND -> null
            facet == UniverseFacet.fandomFacet(schema) -> (fandoms[key].orEmpty() + drafted).distinct()
            def.key in m.store.tagFacets -> listOfNotNull(bound[key]?.let(TagRepository::key))
            // Only a source that drafts of the words knows what they reached; others' are the author's own, to compare by eye
            facet == UniverseFacet.pairingsFacet(schema) -> drafted.takeIf { m.store.source.catalogueProperties.isNotEmpty() }
            facet == def.originalFacet -> listOf(key)
            else -> null
        }
    }

    private class Member(
        val key: ItemKey,
        val store: SourceStore,
        val url: String,
        val facets: Map<String, List<String>>,
        val texts: Map<String, String>,
        val drafts: List<io.github.vlsergey.recommend4me.universe.LayerDraft>,
    )
}
