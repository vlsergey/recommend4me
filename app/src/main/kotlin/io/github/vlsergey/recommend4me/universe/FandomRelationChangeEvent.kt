package io.github.vlsergey.recommend4me.universe

/**
 * What universes are to each other changed — merged, one under another, different — or a site's fandom was bound to
 * another: by the user's word, the likeness learnt of it ([FandomLikeness]) to be learnt again; told [bySite] — a
 * synonym or a parent a site gave of its fandom ([SiteFandomWords]) — a change of no word of the user's, counted beside
 * it ([FandomLikenessLearning]).
 */
data class FandomRelationChangeEvent(val bySite: Boolean)
