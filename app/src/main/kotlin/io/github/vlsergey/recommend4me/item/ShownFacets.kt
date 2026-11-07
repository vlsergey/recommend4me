package io.github.vlsergey.recommend4me.item

import io.github.vlsergey.recommend4me.api.model.FacetValueInfo
import io.github.vlsergey.recommend4me.api.model.ItemFacet
import io.github.vlsergey.recommend4me.api.model.ScaleStep
import io.github.vlsergey.recommend4me.api.model.SiteSaid
import io.github.vlsergey.recommend4me.correction.Corrected
import io.github.vlsergey.recommend4me.correction.FacetCorrection
import io.github.vlsergey.recommend4me.i18n.I18nKeys
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.SourceStore
import io.github.vlsergey.recommend4me.suggestion.ModelValues
import io.github.vlsergey.recommend4me.tag.TagHierarchy
import io.github.vlsergey.recommend4me.universe.UniverseFacet

/**
 * THE FACETS OF ONE WORK AS THE INTERFACE SHOWS THEM ([ItemCards.facets]): every value with its name, the user's word
 * on it, the model's chance of it, and whether the model gave it; [sources] what every site of the work says of a
 * field, for a form to show above it. What every facet of the work reads is read once here; a facet itself by [of].
 */
internal class ShownFacets(
    private val store: SourceStore,
    private val work: WorkValues,
    private val corrections: List<FacetCorrection>,
    private val names: Map<String, Map<String, String>>,
    private val sources: Map<String, List<SiteSaid>>,
    /** The facets shown with no value too: a value can be added to them. */
    private val empty: (FacetDef) -> Boolean,
    /** The facets the user said the work's values of are all named: none offered besides. */
    private val complete: Set<String>,
    /** Whether a facet of tags is among those asked for. */
    ofTags: Boolean,
) {
    private val base = ModelValues.of(store.schema, work.site, work.chances)
    // A tag under another gives the work the other too, shown as such; a tag the user said it has
    // not hides every tag under it ([TagHierarchy]) — read only of the facets of tags asked for
    private val hierarchies = if (!ofTags) emptyMap() else store.hierarchies()
    // The tags the user ignores: never given or offered to mark — shown only where the user answered on them
    private val ignoredTags = if (!ofTags) emptyMap() else store.ignoredTags()
    private val single = store.schema.facets.filter { it.single }.map { it.key }.toSet()
    private val plain = Corrected.facets(base, corrections, single)
    private val corrected = TagHierarchy.corrected(base, corrections, hierarchies, single)
    private val said = corrections.filter { it.added }.groupBy({ it.facet }, { it.key })

    /** The facet [def] as shown; null when it has nothing to show and is not one shown empty. */
    fun of(def: FacetDef): ItemFacet? = Facet(def).shown()

    /** One facet of the work: its values, those the user rejected and those offered to add. */
    private inner class Facet(private val def: FacetDef) {
        private val hierarchy = hierarchies[def.key]
        private val negated =
            hierarchy
                ?.let { h ->
                    corrections
                        .filter { it.facet == def.key && !it.added }
                        .flatMap { listOf(it.key) + h.descendants(it.key) }
                        .toSet()
                }
                .orEmpty()
        private val implied = corrected[def.key].orEmpty().toSet() - plain[def.key].orEmpty().toSet()
        // A character of the work's other facet of characters is not shown here at all
        private val out = UniverseFacet.leftOut(def.key, { corrected[it].orEmpty() }, { said[it].orEmpty() })
        private val siteKeys = work.site[def.key].orEmpty().toSet()
        private val bySite = work.bySite[def.key].orEmpty()
        private val fromSite = bySite.keys
        private val answered = corrections.filter { it.facet == def.key }.map { it.key }.toSet()
        private val ignored = ignoredTags[def.key].orEmpty()
        private val keys = (corrected[def.key].orEmpty() - out).filter(::offerable)
        private val had = base[def.key].orEmpty().toSet()
        // Every value the user rejected is shown crossed out — offered or given, it stays in its place
        private val removed = corrections.filter { it.facet == def.key && !it.added && it.key !in out }.map { it.key }
        private val added = corrections.filter { it.facet == def.key && it.added }.associate { it.key to it.name }
        // The chances of a facet the model works on now: none of one it no longer does
        private val own = if (def.suggest || def.infer) work.chances?.get(def.key).orEmpty() else emptyMap()
        private val offered = if (offers()) offeredValues() else emptyList()

        /** A value shown to mark: none the user ignores, the site's or not, but those answered on. */
        private fun offerable(key: String) = key !in ignored || key in answered

        /**
         * Whether the facet offers values to add: one of the layer the model works out, not said to have no other
         * values, and — of a facet of one value — not one that has it: another one added takes its place.
         */
        private fun offers(): Boolean {
            val worked = def.infer && def.editable
            val filled = def.single && keys.isNotEmpty()
            return worked && def.key !in complete && !filled
        }

        /**
         * Of a facet of the layer the model works out: every value the site gives the work — a tag of the site bound to
         * it — it is not shown with and the user has not answered on, whatever its chance: the site's word, none
         * hidden; then the likeliest values the work is not shown with, the user has not answered on and the site does
         * not give — to make at least so many.
         */
        private fun offeredValues(): List<String> {
            val shown = keys.toSet()
            val ofSites =
                fromSite
                    .filter { it !in shown && it !in answered && it !in out && it !in negated && it !in ignored }
                    .sortedByDescending { own[it]?.chance ?: -1.0 }
            return ofSites +
                ModelValues.offered(
                    own.filterValues { !it.had && it.key !in out && it.key !in negated && it.key !in ignored }
                        .mapValues { it.value.chance },
                    shown = shown,
                    answered = answered,
                    fromSite = fromSite,
                )
        }

        /** The facet as shown; null of one with nothing to show that is not shown empty. */
        fun shown(): ItemFacet? {
            val nothing = keys.isEmpty() && removed.isEmpty() && offered.isEmpty()
            // A facet said to have no other values is shown with none too: the answer is there to take back
            return if (!empty(def) && nothing && def.key !in complete) null else item()
        }

        /** The facet with its values, their names and descriptions read in one go. */
        private fun item(): ItemFacet {
            // The value's own name — a tag's as the user and the sites name it — the name the answer was given with
            // only of a value nothing else names
            // The names of the values the caller did not read — a site's tags bound to tags of the layer
            // offered, a tag above one of the work's — in one read
            val unnamed = (keys + offered).filter { names[def.key]?.containsKey(it) != true }
            val more = if (unnamed.isEmpty()) emptyMap() else store.names(def.key, unnamed)
            fun name(key: String) = names[def.key]?.get(key) ?: more[key] ?: added[key] ?: key
            // What the catalogue says of a universe or a character shown: one read of the facet's
            val described = store.descriptions(def.key, keys + offered + removed)
            fun info(key: String, corrected: FacetValueInfo.Corrected?, offer: Boolean = false) =
                valueInfo(key, name(key), described[key], corrected, offer)
            return ItemFacet(
                facet = def.key,
                labelKey = I18nKeys.facetLabel(store.id, def),
                editable = def.editable,
                tags = (def.key in store.tagFacets).takeIf { it },
                propertyValues =
                    ordered(
                        keys.map { k -> info(k, answerOn(k)) } +
                            offered.map { info(it, null, offer = true) } +
                            removed.map { info(it, FacetValueInfo.Corrected.REMOVED) }
                    ),
                said = sources[def.key],
                complete = (def.key in complete).takeIf { it },
                single = def.single.takeIf { it },
                completable = def.completable.takeIf { it },
                steps = steps(),
            )
        }

        /**
         * The user's word on a value the work is shown with: added, or confirmed when the site or the model gave it —
         * the model even when the user's answer has since made it the work's own, no longer the model's to give.
         */
        private fun answerOn(key: String): FacetValueInfo.Corrected? {
            val given = key in had || (own[key]?.chance ?: 0.0) > ModelValues.LIKELY
            return when {
                key !in added -> null
                given -> FacetValueInfo.Corrected.CONFIRMED
                else -> FacetValueInfo.Corrected.ADDED
            }
        }

        /** One value as shown: [corrected] the user's word on it, [offer] whether it is offered to add. */
        private fun valueInfo(
            key: String,
            name: String,
            description: String?,
            corrected: FacetValueInfo.Corrected?,
            offer: Boolean,
        ): FacetValueInfo {
            // Drafted, unanswered: the site's value it was drafted from, not the model's word
            val draftedFrom = work.chances?.get(def.key)?.get(key)?.draftedFrom?.takeIf { corrected == null && !offer }
            return FacetValueInfo(
                key = key,
                name = name,
                nameKey = store.nameKey(def.key, key),
                corrected = corrected,
                chance = own[key]?.chance,
                inferred = (key in had && key !in siteKeys && draftedFrom == null).takeIf { it },
                draftedFrom = draftedFrom,
                offered = offer.takeIf { it },
                fromSite = (def.infer && key in fromSite).takeIf { it },
                sites = bySite[key]?.takeIf { def.infer },
                implied = (key in implied).takeIf { it },
                description = description,
            )
        }

        /**
         * In the order shown: the work's values and those rejected, then the site's values only offered, then the
         * model's offers; in each the likeliest first — a value with no chance after them — and a tag above another
         * before it ([TagHierarchy.parentsFirst]); the pages keep it.
         */
        private fun ordered(values: List<FacetValueInfo>): List<FacetValueInfo> =
            values
                .sortedWith(
                    compareBy<FacetValueInfo> { if (it.offered != true) 0 else if (it.fromSite == true) 1 else 2 }
                        .thenByDescending { it.chance ?: -1.0 }
                )
                .let { sorted -> hierarchy?.parentsFirst(sorted) { it.key } ?: sorted }

        /** Of a scale every step, with the model's chance of it and whether the site lets it; null of no scale. */
        private fun steps(): List<ScaleStep>? =
            def.standard
                ?.takeIf { it.ordered }
                ?.let { scale ->
                    val allowed = work.lets[def.key].orEmpty()
                    scale.values.orEmpty().map { v ->
                        ScaleStep(
                            v.key,
                            v.modelName,
                            allowed.isEmpty() || v.key in allowed,
                            I18nKeys.standardValueOf(scale, v.key),
                            own[v.key]?.chance,
                        )
                    }
                }
    }
}
