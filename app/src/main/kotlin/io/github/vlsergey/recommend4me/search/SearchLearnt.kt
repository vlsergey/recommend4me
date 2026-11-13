package io.github.vlsergey.recommend4me.search

/**
 * What a search learnt of the user's judgments: the correction of the closeness by meaning the line is searched with
 * ([QueryShift]) and the weights the works found are put in order by ([SearchWeights]); either none while not learnt.
 */
class SearchLearnt(val shift: QueryShift?, val weights: SearchWeights?)
