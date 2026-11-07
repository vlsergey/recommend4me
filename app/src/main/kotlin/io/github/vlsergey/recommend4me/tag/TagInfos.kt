package io.github.vlsergey.recommend4me.tag

import io.github.vlsergey.recommend4me.api.model.CatalogueOffer as ApiCatalogueOffer
import io.github.vlsergey.recommend4me.api.model.TagDescription
import io.github.vlsergey.recommend4me.api.model.TagEntryInfo
import io.github.vlsergey.recommend4me.api.model.TagInfo
import io.github.vlsergey.recommend4me.api.model.TagNameRule
import io.github.vlsergey.recommend4me.api.model.TagRef
import io.github.vlsergey.recommend4me.api.model.TagRelation as ApiTagRelation
import io.github.vlsergey.recommend4me.api.model.TagVariantInfo
import io.github.vlsergey.recommend4me.refusal.Refusal
import org.springframework.http.HttpStatus

/* A TAG AS THE API SHOWS IT, to the screens of the tags and of the catalogues alike. */

/** What the tags are read over; refused as not found when no type has free tags. */
fun Tags.requiredScope(): TagScope =
    scope() ?: throw Refusal.NO_SITE_TAGS.refuse(HttpStatus.NOT_FOUND, "No type has site tags")

/** The text of the first of the user's [languages] that has one. */
fun Map<String, String>.inFirstOf(languages: List<String>): String? = languages.firstNotNullOfOrNull { this[it] }

/** The tag as the API shows it, its entries' texts in the first of the user's [languages] that has them. */
fun NamedTag.toApi(languages: List<String>) =
    TagInfo(
        key = stats.key,
        name = name.name,
        rule = TagNameRule.valueOf(name.rule.name),
        explicitName = explicit,
        works = stats.counts.works,
        graded = stats.counts.graded,
        liked = stats.counts.liked,
        variants = stats.variants.values.sortedByDescending { it.uses.works }.map { it.toApi() },
        names = TagNaming.names(stats).map { it.first },
        parents = parents.map { (key, name) -> TagRef(key, name) },
        children = children.map { (key, name) -> TagRef(key, name) },
        catalogueEntries =
            entries.map { b ->
                TagEntryInfo(
                    b.catalogue,
                    b.entry.id,
                    b.entry.url,
                    b.names,
                    b.entry.labels.inFirstOf(languages),
                    b.entry.descriptions.inFirstOf(languages),
                )
            },
        // The sites' words first, then the entries'
        descriptions =
            descriptions.map { d -> TagDescription(d.site.source, d.text, stats.variants[d.site]?.name) } +
                entries.mapNotNull { b ->
                    b.entry.descriptions.inFirstOf(languages)?.let {
                        TagDescription(b.catalogue, it, b.entry.labels.inFirstOf(languages))
                    }
                },
        awaitedDescriptions = awaitedDescriptions,
        ignored = ignored,
        catalogueOffers =
            offers.map { o ->
                ApiCatalogueOffer(o.catalogue, TagRef(o.tag, o.name), ApiTagRelation.valueOf(o.relation.name), o.path)
            },
    )

/** A site's tag of a tag as the API shows it. */
fun TagVariant.toApi() = TagVariantInfo(site.source, site.tag, name, uses.works, uses.graded, uses.liked)
