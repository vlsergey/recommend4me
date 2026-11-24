package io.github.vlsergey.recommend4me.universe

import io.github.vlsergey.recommend4me.api.model.UniverseRelation
import io.github.vlsergey.recommend4me.source.Stores
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

private val log = LoggerFactory.getLogger(SiteFandomWords::class.java)

/**
 * WHAT A SITE SAYS OF ITS FANDOMS BETWEEN THEM, of a fandom's own page — the tags of AO3 (owner, 2026-11-23): a synonym
 * of a fandom, another name the site wrangled into it, is of the fandom's universe; a fandom the site puts under
 * another — a metatag, "… & Related Fandoms" — has its universe under the other's. Done of what the site tells anew and
 * of a site's fandom bound anew alone, once: what the user says after it stands, a page read again changing nothing.
 */
@Component
class SiteFandomWords(private val stores: Stores, private val editing: FandomEditing) {

    private fun fandoms(): FandomRepository? = stores.types.firstOrNull { it.type.universes }?.universes?.fandoms

    /**
     * A synonym told of a fandom, both bound to universes of their own: one — the synonym's universe of it alone, with
     * all the user said of that universe, merged into the fandom's; else the synonym alone bound to the fandom's
     * universe. A synonym no work has is bound when a work has it ([FandomNormalization]).
     */
    @EventListener
    fun synonymsTold(event: SiteFandomSynonymChangeEvent) {
        val fandoms = fandoms() ?: return
        event.told.forEach { (synonym, key) ->
            val site = SiteFandomRef(event.source, synonym)
            val universe = fandoms.sites.fandomOf(SiteFandomRef(event.source, key)) ?: return@forEach
            val was = fandoms.sites.fandomOf(site) ?: return@forEach
            if (was == universe) return@forEach
            if (fandoms.sites.sitesOf(was) == listOf(site))
                editing.relate(universe, was, UniverseRelation.MERGED, bySite = true)
            else editing.bind(site, universe, bySite = true)
            log.info("{} of {}, a synonym of {}, of universe {} now, was {}", synonym, event.source, key, universe, was)
        }
    }

    /** The fandoms told to be under others: their universes put under the others'. */
    @EventListener fun parentsTold(event: SiteFandomParentChangeEvent) = under(event.source, event.told)

    /**
     * The sites' fandoms bound anew ([SiteFandomBindingChangeEvent]): what their site told of them before — the fandoms
     * they are under and those under them — done now that they have universes.
     */
    @EventListener
    fun bound(event: SiteFandomBindingChangeEvent) {
        val s = stores.source(event.source) ?: return
        val facet = UniverseFacet.fandomFacet(s.schema) ?: return
        val above = s.items.parentsOf(facet, event.keys).flatMap { (key, of) -> of.map { key to it } }
        val below = s.items.childrenOf(facet, event.keys).flatMap { (key, of) -> of.map { it to key } }
        under(event.source, (above + below).toSet())
    }

    /**
     * Of every pair [told] of the [source] — a fandom and one above it — both bound, the universe of the first put
     * under the universe of the second: of universes not one, not one above or under the other already, not told apart
     * by the user.
     */
    private fun under(source: String, told: Collection<Pair<String, String>>) {
        val fandoms = fandoms() ?: return
        told.forEach { (key, parent) ->
            val child = fandoms.sites.fandomOf(SiteFandomRef(source, key)) ?: return@forEach
            val above = fandoms.sites.fandomOf(SiteFandomRef(source, parent)) ?: return@forEach
            if (child == above || fandoms.relations.apart(child, above)) return@forEach
            val dictionary = FandomDictionary.load(fandoms)
            if (above in dictionary.ancestors(child) || child in dictionary.ancestors(above)) return@forEach
            editing.relate(above, child, UniverseRelation.CHILD, bySite = true)
            log.info("{} of {} under {}: universe {} under {}", key, source, parent, child, above)
        }
    }
}
