package io.github.vlsergey.recommend4me.contenttype

import io.github.vlsergey.recommend4me.api.TypesApi
import io.github.vlsergey.recommend4me.api.model.ContentTypeInfo
import io.github.vlsergey.recommend4me.api.model.FacetInfo
import io.github.vlsergey.recommend4me.api.model.NumberInfo
import io.github.vlsergey.recommend4me.api.model.SignalInfo
import io.github.vlsergey.recommend4me.api.model.SourceInfo
import io.github.vlsergey.recommend4me.api.model.TextInfo
import io.github.vlsergey.recommend4me.model.FeatureNames
import io.github.vlsergey.recommend4me.plugin.Plugins
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import io.github.vlsergey.recommend4me.api.model.SourceMode as ApiSourceMode

/** The content types and what their sources know of their items: the interface is built of it. */
@RestController
class TypesController(private val plugins: Plugins) : TypesApi {

    override fun listTypes(): ResponseEntity<List<ContentTypeInfo>> = ResponseEntity.ok(plugins.types.map { type ->
        val sources = plugins.sourcesOf(type.id)
        ContentTypeInfo(
            id = type.id,
            title = type.title,
            grades = type.grades,
            verb = type.verb,
            sources = sources.map { s ->
                SourceInfo(
                    id = s.id,
                    title = s.title,
                    homepage = s.homepage,
                    modes = s.modes.map { ApiSourceMode.valueOf(it.name) },
                    texts = s.schema.texts.map { TextInfo(it.key, it.label, it.spoiler) },
                    facets = s.schema.facets.map { FacetInfo(FeatureNames.facetId(s, it), it.key, it.label, it.filter, it.onCard, it.suggest, it.infer, it.original) },
                    numbers = s.schema.numbers.map { NumberInfo(FeatureNames.numberId(s, it), it.key, it.label) },
                    versioned = s.schema.versioned,
                    reviewsLabel = s.schema.reviewsLabel,
                    partsLabel = s.schema.partsLabel,
                    signals = s.signals.map { SignalInfo(it.key, it.label, it.values) },
                )
            },
            numbers = sources.flatMap { s -> s.schema.numbers.filter { it.sortable }.map { NumberInfo(FeatureNames.numberId(s, it), it.key, it.label) } }
                .distinctBy { it.id },
        )
    })
}
