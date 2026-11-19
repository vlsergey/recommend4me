package io.github.vlsergey.recommend4me.correction

/**
 * The user answered on a value of a facet of a work — confirmed, took away, added, took back, said no other is: what
 * the work is searched by and what the model of the grades reads changed. Not
 * [WorkChangeEvent][io.github.vlsergey.recommend4me.item.WorkChangeEvent]: the site's data is as it was, and the work's
 * chances are made apart, at once
 * ([FacetRefitEvents.answered][io.github.vlsergey.recommend4me.suggestion.FacetRefitEvents.answered]).
 */
data class WorkFieldChangeEvent(val type: String, val source: String, val itemId: String, val facet: String)
