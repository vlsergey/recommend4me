package io.github.vlsergey.recommend4me.i18n

import io.github.vlsergey.recommend4me.source.Source

/**
 * THE KEYS OF THE INTERFACE'S TRANSLATIONS. The server never words what it sends: it sends keys, and the interface
 * translates them. A key is "<namespace>:<key>":
 *
 * - [STANDARD] — the words of the application itself, every content type's alike: the types and their grades, the
 *   standard facets, numbers, signals and their values, the layer of universes, the parts of the model, the errors. The
 *   web interface keeps them, every language checked by its compiler;
 * - a plugin's id — a source's ("ficbook"), a scorer's ("pairwise"), a catalogue's ("wikidata") — the words of that
 *   plugin alone: its title, its own facets, numbers, texts, signals, settings, job messages. The plugin carries them,
 *   `i18n/<id>.<language>.json` among its resources, and the interface reads them as they are
 *   ("/i18n/<id>/<language>.json"). [requiredKeys] are what a source's files must have; the test fixture `Translations`
 *   checks them.
 *
 * Keys within a namespace are flat — "facet.direction" is one key — and never hold ":". The keys within a plugin's
 * namespace are [PluginKeys], the qualified keys of a source's things [SourceLabels]: this object's own, as all of it.
 */
object I18nKeys : PluginKeys, SourceLabels {
    const val STANDARD = "standard"

    /** The namespace of the languages, shown by their codes ([language]). */
    const val LANGUAGE = "language"

    /** The languages of the interface, by their codes; the first is the fallback. */
    val LANGUAGES = listOf("en", "ru", "sr")

    fun qualified(namespace: String, key: String) = "$namespace:$key"

    // --- Within a plugin's namespace ---

    const val TITLE = "title"

    /**
     * A source's title cut short — "КФ", "AO3" — where many sites are named in little room: a list of a tag's sites.
     */
    const val SHORT_TITLE = "title.short"
    const val REVIEWS = "reviews"
    const val PARTS = "parts"

    /**
     * The key a language is shown by, of its ISO 639 code ("language:ru"): the interface names it in the language it
     * speaks itself (Intl.DisplayNames), no word file holds them.
     */
    fun language(code: String) = "$LANGUAGE:$code"

    // --- Of the standard namespace ---

    fun typeTitle(type: String) = qualified(STANDARD, "type.$type.title")

    fun typeVerb(type: String) = qualified(STANDARD, "type.$type.verb")

    fun typeGrade(type: String, grade: Int) = qualified(STANDARD, "type.$type.grade.$grade")

    /**
     * The keys a source's own translations must have, every language alike: its title; the label of every facet, number
     * and signal of its own (not a standard one) and the named values of its signals; every text; every setting and its
     * hint; the name of the works without a value of a facet that lists them; its reviews and parts when it keeps them;
     * and the messages of its jobs.
     */
    fun requiredKeys(source: Source): Set<String> {
        val schema = source.schema
        val keys = LinkedHashSet<String>()
        keys += TITLE
        keys += SHORT_TITLE
        schema.facets.filter { it.standard == null && !it.editable }.forEach { keys += facet(it.key) }
        schema.facets.filter { it.none }.forEach { keys += facetNone(it.key) }
        schema.numbers.filter { it.standard == null }.forEach { keys += number(it.key) }
        schema.texts.forEach { keys += text(it.key) }
        source.signals
            .filter { it.standard == null }
            .forEach { s ->
                keys += signal(s.key)
                s.values.forEach { keys += signalValue(s.key, it) }
            }
        source.settings.forEach { s ->
            keys += setting(s.key)
            if (s.hint) keys += settingHint(s.key)
        }
        if (schema.reviews) keys += REVIEWS
        if (schema.parts) keys += PARTS
        source.messages.forEach { keys += job(it) }
        return keys
    }
}
