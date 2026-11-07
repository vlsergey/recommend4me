package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.plugin.Plugins
import io.github.vlsergey.recommend4me.textvector.PhraseVectors

/**
 * THE MEANING OF THE TAGS' NAMES of one reading ([stats]): every name of a tag — its sites' tags' and its entries' — a
 * vector, made in the background and kept in the dictionaries ([TagVectors]); a site's tag alone by its name's vector
 * the source keeps. Alike by the best cosine of their names: a synonym added finds more and blurs nothing. A tag of no
 * vectors yet is alike nothing. Read for one operation, kept nowhere.
 */
class TagMeaning(
    private val scope: TagScope,
    stats: Map<String, TagStats>,
    private val phrases: PhraseVectors,
    plugins: Plugins,
) {
    private val encoder = phrases.encoder()

    /** The vectors of every tag's names by the encoder in use, by key: those kept. */
    private val ofTags: Map<String, List<FloatArray>> =
        encoder?.let { scope.tags.vectors.vectors(plugins.pluginOf(it), it.id) }.orEmpty()

    private val siteNames: Map<SiteTagRef, String> =
        stats.values.flatMap { t -> t.variants.values }.associate { it.site to it.name }

    /** The vectors of the names of [sites] the sources keep: a site's tag alone, not its whole tag. */
    private fun ofSites(sites: Collection<SiteTagRef>): List<FloatArray> =
        sites
            .groupBy { it.source }
            .flatMap { (source, refs) ->
                val s = scope.source(source) ?: return@flatMap emptyList()
                phrases.cached(s, refs.mapNotNull { siteNames[it] }).values.mapNotNull(TagVectors::unit)
            }

    /** The vectors of the names of the tag [key]; of [sites] alone when given — a site's tag of it. */
    fun names(key: String?, sites: Collection<SiteTagRef>? = null): List<FloatArray> =
        if (sites != null) ofSites(sites) else key?.let { ofTags[it] }.orEmpty()

    /** A text typed, encoded as the names are and kept nowhere: it is not a name of the sites. */
    fun ofText(text: String): List<FloatArray> =
        encoder?.encode(listOf(text))?.firstOrNull()?.let(TagVectors::unit)?.let { listOf(it) }.orEmpty()

    /**
     * How alike the tag [key] — or its sites' tags [sites] alone — is to the names [target]: the best cosine; nought
     * when either is not known.
     */
    fun likeness(target: List<FloatArray>, key: String?, sites: Collection<SiteTagRef>? = null): Double =
        TagVectors.likeness(target, names(key, sites))
}
