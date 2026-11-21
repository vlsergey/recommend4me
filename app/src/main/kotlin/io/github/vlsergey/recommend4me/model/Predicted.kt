package io.github.vlsergey.recommend4me.model

/**
 * An item's score on 0..10, and its chance of interest, 0..1, when there is a model of interest: its work's, as the
 * index of the list keeps it ([io.github.vlsergey.recommend4me.worklist.ListPredictions]).
 */
class Predicted(val score: Double, val interest: Double?)
