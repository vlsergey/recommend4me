package io.github.vlsergey.recommend4me.i18n

import io.github.vlsergey.recommend4me.contenttype.StandardFacet
import io.github.vlsergey.recommend4me.i18n.I18nKeys.STANDARD
import io.github.vlsergey.recommend4me.i18n.I18nKeys.qualified
import io.github.vlsergey.recommend4me.source.FacetDef
import io.github.vlsergey.recommend4me.source.NumberDef
import io.github.vlsergey.recommend4me.source.SignalDef

/**
 * THE QUALIFIED KEYS OF A SOURCE'S THINGS — its facets and their values, numbers, texts, signals — in the standard
 * namespace or the source's, as they are: a standard thing's in the standard one, the source's own in its own. Read as
 * [I18nKeys]'s own (`I18nKeys.facetLabel(source, def)`).
 */
sealed interface SourceLabels : PluginKeys {
    /**
     * The label of a facet of [source]: a standard facet's, or one of the application's layer over the sources
     * ([FacetDef.editable]) — the standard namespace; the source's own facet — its own.
     */
    fun facetLabel(source: String, def: FacetDef): String =
        if (def.standard != null || def.editable) qualified(STANDARD, facet(def.standard?.key ?: def.key))
        else qualified(source, facet(def.key))

    /** The name of a works without a value of the facet ([FacetDef.none]). */
    fun facetNoneLabel(source: String, def: FacetDef): String = qualified(source, facetNone(def.key))

    /** The name of a standard value of a standard facet with a closed list of them; null of any other. */
    fun standardValue(def: FacetDef, value: String): String? = def.standard?.let { standardValueOf(it, value) }

    /** The name of a value of a [standard] facet with a closed list of them; null of a value not of it. */
    fun standardValueOf(standard: StandardFacet, value: String): String? =
        standard.takeIf { s -> s.value(value) != null }?.let { qualified(STANDARD, facetValue(it.key, value)) }

    fun numberLabel(source: String, def: NumberDef): String =
        def.standard?.let { qualified(STANDARD, number(it.key)) } ?: qualified(source, number(def.key))

    fun textLabel(source: String, key: String): String = qualified(source, text(key))

    fun signalLabel(source: String, def: SignalDef): String =
        def.standard?.let { qualified(STANDARD, signal(it.key)) } ?: qualified(source, signal(def.key))

    fun signalValueLabel(source: String, def: SignalDef, value: String): String =
        def.standard?.let { qualified(STANDARD, signalValue(it.key, value)) }
            ?: qualified(source, signalValue(def.key, value))
}
