-- THE STORE OF THE SEARCH OF DUPLICATES OF A CONTENT TYPE (owner, 2026-11-22: "let us move to DuckDB"), a DuckDB file of
-- its own, made by this script as it is new and made anew as its tables are other than this script makes: everything
-- in it is made again by the next search — the candidates, the models of the answers, how rare every value is — the
-- user's words kept elsewhere. Read by H2 too, as jOOQ's classes are made of it.

-- The candidates of the last search, written in the order of their key: no index, no key kept — a page is chosen by a
-- pass of the columns of its filter and its sort, a pair found by the blocks of rows of its first work
CREATE TABLE duplicate_candidate (
    source VARCHAR(100) NOT NULL,
    item_id VARCHAR(200) NOT NULL,
    other_source VARCHAR(100) NOT NULL,
    other_item_id VARCHAR(200) NOT NULL,
    title REAL NOT NULL,
    description REAL,
    facets VARCHAR(4000),
    size REAL,
    found_for TINYINT DEFAULT 0 NOT NULL,
    same_author BOOLEAN DEFAULT FALSE NOT NULL,
    title_within REAL,
    title_holding REAL,
    authors_alike REAL,
    authors_within REAL,
    authors_holding REAL,
    title_vector REAL,
    description_letters REAL,
    fandom_letters REAL,
    character_letters REAL,
    partly_same_author BOOLEAN DEFAULT FALSE NOT NULL,
    words REAL,
    other_words REAL,
    same_chance DOUBLE PRECISION,
    series_chance DOUBLE PRECISION,
    answer VARCHAR(20),
    answer_derived BOOLEAN DEFAULT FALSE NOT NULL,
    same_authors BOOLEAN DEFAULT FALSE NOT NULL,
    same_title BOOLEAN DEFAULT FALSE NOT NULL,
    by_size DOUBLE PRECISION,
    by_series_size DOUBLE PRECISION,
    by_doubt DOUBLE PRECISION,
    by_series_doubt DOUBLE PRECISION,
    by_formula DOUBLE PRECISION,
    by_interest DOUBLE PRECISION,
    by_grade DOUBLE PRECISION
);

-- The last search: one row, of id 1, with the models of the answers fitted on its candidates
CREATE TABLE duplicate_search (
    id INTEGER NOT NULL PRIMARY KEY,
    made_at TIMESTAMP WITH TIME ZONE NOT NULL,
    encoder VARCHAR(100) NOT NULL,
    works INTEGER NOT NULL,
    model VARCHAR(100),
    same_model BLOB,
    series_model BLOB,
    fitted_at TIMESTAMP WITH TIME ZONE,
    fit_millis BIGINT
);

-- How rare every value the search compares is among every work of the type; no key kept: the values are written anew
-- whole by every search, and a key emptied and filled again in one transaction cost a search of 2.6 million pairs 61 s
-- of its writing and 57 s of its commit, 15 ms and 0.5 s without (2026-11-22)
CREATE TABLE duplicate_value (
    name VARCHAR(1000) NOT NULL,
    rarity REAL NOT NULL
);
