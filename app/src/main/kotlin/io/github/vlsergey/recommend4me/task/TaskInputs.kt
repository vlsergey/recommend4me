package io.github.vlsergey.recommend4me.task

import io.github.vlsergey.recommend4me.api.model.TaskKind
import io.github.vlsergey.recommend4me.database.WholeReadRefused

/**
 * HOW A KIND OF WORK OF THE QUEUE FINDS WHAT IT IS TO DO (owner, 2026-11-27: "every task that has inputs remembers the
 * date of the inputs it was run for, and the works — the next portion — are chosen by that date"; "do it at the level
 * of the framework of the tasks, to fix such behaviour"). A kind that takes its work by dates or by keys
 * ([refusesWhole]) runs with every reading of a whole table refused to it ([WholeReadRefused]): a reading of every row
 * to find what changed fails at once, in the tests as on the user's machine.
 */
enum class TaskInput(val refusesWhole: Boolean) {
    /**
     * A stage of the tasks over the works: the works whose dated data changed past its mark
     * ([io.github.vlsergey.recommend4me.worklist.StageSweeps]).
     */
    STAGE(true),

    /**
     * A consumer of the changes in their order: what changed after where it stands, by the date and the key, a portion
     * at a time ([io.github.vlsergey.recommend4me.worklist.ListIndexing],
     * [io.github.vlsergey.recommend4me.manner.GroupMannerChanges]).
     */
    CHANGES(true),

    /** The keys kept with it as it was planned ([Tasks.keys]), or the one item or source its task names. */
    KEYS(true),

    /** A model fitted on everything it learns of: whole by its meaning. */
    FIT(false),

    /** What the user asked of everything: a search or a comparison over every work. */
    ASKED(false),

    /**
     * Reads everything to find what changed — to be made one of the above, one kind after another, and gone from here
     * (owner, 2026-11-27).
     */
    WHOLE(false),
}

/** THE INPUT OF EVERY KIND OF WORK OF THE QUEUE ([TaskInput]), and its works run by it. */
object TaskInputs {

    /** Of every kind of work, how it finds what it is to do; a kind not told fails the tests. */
    val of: Map<TaskKind, TaskInput> =
        mapOf(
            TaskKind.ENCODE_SITE_ITEM_TEXTS to TaskInput.STAGE,
            TaskKind.ENCODE_SITE_ITEM_CHAPTERS to TaskInput.STAGE,
            TaskKind.ENCODE_SITE_ITEM_CASTS to TaskInput.STAGE,
            TaskKind.MAKE_SITE_ITEM_SET_VECTORS to TaskInput.STAGE,
            TaskKind.FILL_WORK_FIELDS to TaskInput.STAGE,
            TaskKind.PREDICT_WORKS to TaskInput.STAGE,
            TaskKind.INDEX_WORK_TEXTS to TaskInput.STAGE,
            TaskKind.MAKE_WORK_MANNERS to TaskInput.STAGE,
            TaskKind.WRITE_LIST_INDEX to TaskInput.CHANGES,
            TaskKind.MAKE_GROUP_MANNERS to TaskInput.CHANGES,
            TaskKind.COUNT_FANDOM_WORKS to TaskInput.KEYS,
            TaskKind.ANALYSE_SITE_ITEM_PICTURES to TaskInput.KEYS,
            TaskKind.REMOVE_SITE_ITEM_TEXTS to TaskInput.KEYS,
            TaskKind.WRITE_WORK_VALUES to TaskInput.KEYS,
            TaskKind.KEEP_OUT_WORKS to TaskInput.KEYS,
            TaskKind.FIT_FIELD_MODELS to TaskInput.FIT,
            TaskKind.FIT_GRADE_MODELS to TaskInput.FIT,
            TaskKind.FIT_DUPLICATE_MODEL to TaskInput.FIT,
            TaskKind.FIT_FANDOM_LIKENESS to TaskInput.FIT,
            TaskKind.FIT_TAG_TEXT_MODEL to TaskInput.FIT,
            TaskKind.FIT_SEARCH_WEIGHTS to TaskInput.FIT,
            TaskKind.FIT_FULL_TEXT_CENTRE to TaskInput.FIT,
            TaskKind.FIT_PIECE_PROJECTION to TaskInput.FIT,
            TaskKind.FIT_SET_DIRECTIONS to TaskInput.FIT,
            TaskKind.FIT_HELD_OUT_MODEL to TaskInput.FIT,
            TaskKind.FIT_MANNER_WORDS to TaskInput.FIT,
            TaskKind.MEASURE_WORK_SIZES to TaskInput.FIT,
            TaskKind.READ_SCORER_INPUT to TaskInput.FIT,
            TaskKind.MEASURE_PART_WORTH to TaskInput.FIT,
            TaskKind.WEIGH_PARTS to TaskInput.FIT,
            TaskKind.SEARCH_FIELD_MODEL_ANEW to TaskInput.ASKED,
            TaskKind.COMPARE_FIELD_METHODS to TaskInput.ASKED,
            TaskKind.COMPARE_GRADE_SCORERS to TaskInput.ASKED,
            TaskKind.COMPARE_INTEREST_SCORERS to TaskInput.ASKED,
            TaskKind.COMPARE_FANDOM_WAYS to TaskInput.ASKED,
            TaskKind.SEARCH_DUPLICATES to TaskInput.ASKED,
            TaskKind.SEARCH_AUTHOR_DUPLICATES to TaskInput.ASKED,
            TaskKind.LET_GO_UNUSED_FANDOMS to TaskInput.WHOLE,
            TaskKind.NAME_FANDOMS to TaskInput.WHOLE,
            TaskKind.ENCODE_FANDOM_NAMES to TaskInput.WHOLE,
            TaskKind.RENAME_FANDOMS to TaskInput.WHOLE,
            TaskKind.NAME_TAGS to TaskInput.WHOLE,
            TaskKind.COUNT_TAGS to TaskInput.WHOLE,
            TaskKind.ENCODE_TAG_NAMES to TaskInput.WHOLE,
            TaskKind.RELATE_TAGS_BY_SITES to TaskInput.WHOLE,
            TaskKind.ERASE_DIFFERENT_IN_SERIES to TaskInput.WHOLE,
            // Of its keys, but the items it writes bind their sites' tags of every binding read whole
            // (TagNormalization.bind): with the tags
            TaskKind.KEY_NAMED_VALUES to TaskInput.WHOLE,
        )

    /** The [body] of a work of the [kind], every reading of a whole table refused to it of a kind that refuses one. */
    fun <T> run(kind: TaskKind, body: () -> T): T =
        if (of.getValue(kind).refusesWhole) WholeReadRefused.during("the work $kind of the queue", body)
        else WholeReadRefused.lifted(body)
}
