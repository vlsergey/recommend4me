-- THE INDEX OF THE LIST OF WORKS OF A CONTENT TYPE (owner, 2026-11-22: "study replacing the index, not the full-text
-- one, by DuckDB"; "work"), a DuckDB file of its own (worklist.ListIndex), made by this script as it is new and made
-- anew as its tables are other than this script makes: everything in it is made of the databases again
-- (worklist.ListIndexing, from the first item of every source). Read by H2 too, as jOOQ's classes are made of it.

-- Every item: the work it is of and its rank among the work's items, the shown one 0; whether the work is of several;
-- its dates; what changes of its work without it — graded, marked, the prediction, the chances of interest, their
-- product, to three digits —; its title as the user corrected it; when every kind of data of its work last changed
-- (worklist.WorkDatum, `<datum>_at`, milliseconds) and the sweep of every stage that last did it (worklist.WorkStage,
-- `<stage>_done`, milliseconds; none of never)
CREATE TABLE item (
    item_key VARCHAR(400) NOT NULL PRIMARY KEY,
    source VARCHAR(100) NOT NULL,
    work VARCHAR(400) NOT NULL,
    work_rank INTEGER NOT NULL,
    linked BOOLEAN NOT NULL,
    updated BIGINT NOT NULL,
    added BIGINT NOT NULL,
    graded BOOLEAN NOT NULL,
    marked BOOLEAN NOT NULL,
    prediction DOUBLE PRECISION,
    interest DOUBLE PRECISION,
    model_interest DOUBLE PRECISION,
    expected DOUBLE PRECISION,
    title VARCHAR NOT NULL,
    data_at BIGINT,
    chapters_at BIGINT,
    reviews_at BIGINT,
    pictures_at BIGINT,
    answers_at BIGINT,
    grades_at BIGINT,
    members_at BIGINT,
    vectors_at BIGINT,
    windows_at BIGINT,
    cast_at BIGINT,
    sets_at BIGINT,
    fields_at BIGINT,
    vectors_done BIGINT,
    windows_done BIGINT,
    cast_done BIGINT,
    sets_done BIGINT,
    fields_done BIGINT,
    predicted_done BIGINT,
    texts_done BIGINT
);

-- The values of every filter facet of an item, of its authors and of the site's fandoms, by the facet's filter id, as
-- the user sees them — the site's, the model's kept, the user's word on top —, in their order
CREATE TABLE item_value (
    item_key VARCHAR(400) NOT NULL,
    facet VARCHAR(200) NOT NULL,
    pos INTEGER NOT NULL,
    value_key VARCHAR(1000) NOT NULL
);
CREATE INDEX item_value_key ON item_value (item_key);

-- Every number of an item by the number's id, the user's word on top
CREATE TABLE item_number (
    item_key VARCHAR(400) NOT NULL,
    number_id VARCHAR(200) NOT NULL,
    content DOUBLE PRECISION NOT NULL
);
CREATE INDEX item_number_key ON item_number (item_key);

-- The values the models give an item beyond the site's own, by the facet's key, in their order: kept here alone
CREATE TABLE item_model (
    item_key VARCHAR(400) NOT NULL,
    facet VARCHAR(200) NOT NULL,
    pos INTEGER NOT NULL,
    value_key VARCHAR(1000) NOT NULL
);
CREATE INDEX item_model_key ON item_model (item_key);

-- Every text of an item's own held whole, as the user corrected it, by the text's key
CREATE TABLE item_text (
    item_key VARCHAR(400) NOT NULL,
    text_key VARCHAR(200) NOT NULL,
    content VARCHAR NOT NULL
);
CREATE INDEX item_text_key ON item_text (item_key);

-- The vectors of an item's title and description by an encoder: of unit length, a byte a number with its scale
CREATE TABLE item_vector (
    item_key VARCHAR(400) NOT NULL,
    encoder VARCHAR(100) NOT NULL,
    text_key VARCHAR(200) NOT NULL,
    vec BLOB NOT NULL
);
CREATE INDEX item_vector_key ON item_vector (item_key);

-- Where the writing of the index stands and up to when every stage did every change it reads (worklist.StageTimes):
-- kept with the transaction of what it tells of
CREATE TABLE list_mark (
    name VARCHAR(400) NOT NULL PRIMARY KEY,
    content VARCHAR(400) NOT NULL
);

-- What a writing sets of many items at once — the sweep of a stage, the date of what a stage made, the values of their
-- works —: appended here and set by one statement, emptied as it is (a statement an item took a million of them)
CREATE TABLE pending_date (
    item_key VARCHAR(400) NOT NULL,
    content BIGINT NOT NULL
);
CREATE TABLE pending_values (
    item_key VARCHAR(400) NOT NULL,
    graded BOOLEAN NOT NULL,
    marked BOOLEAN NOT NULL,
    prediction DOUBLE PRECISION,
    interest DOUBLE PRECISION,
    model_interest DOUBLE PRECISION,
    expected DOUBLE PRECISION
);
