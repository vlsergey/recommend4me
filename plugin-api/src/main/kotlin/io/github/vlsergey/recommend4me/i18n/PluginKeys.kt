package io.github.vlsergey.recommend4me.i18n

/**
 * THE KEYS WITHIN A PLUGIN'S NAMESPACE, unqualified: of its facets and their values, its numbers, texts, signals,
 * settings and job messages. Read as [I18nKeys]'s own (`I18nKeys.facet(key)`); the standard namespace keeps the same
 * keys of the standard things.
 */
sealed interface PluginKeys {
    fun facet(key: String) = "facet.$key"

    fun facetNone(key: String) = "facet.$key.none"

    fun facetValue(facet: String, value: String) = "facet.$facet.value.$value"

    fun number(key: String) = "number.$key"

    fun text(key: String) = "text.$key"

    fun signal(key: String) = "signal.$key"

    fun signalValue(key: String, value: String) = "signal.$key.value.$value"

    fun setting(key: String) = "setting.$key"

    fun settingHint(key: String) = "setting.$key.hint"

    /** A message of a job ([Job.message][io.github.vlsergey.recommend4me.source.Job.message]): "job.noCookie". */
    fun job(key: String) = "job.$key"
}
