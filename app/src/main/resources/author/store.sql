-- THE STORE OF THE SEARCH OF THE AUTHORS' DUPLICATES OF A CONTENT TYPE (owner, 2026-11-24: "we redo it on DuckDB, the
-- file beside the works' duplicates"), a DuckDB file of its own, made by this script as it is new and made anew as its
-- tables are other than this script makes: everything in it is made again by the next search — the pairs of authors
-- and when it was made — the user's words on the authors kept in the dictionaries
-- (author.AuthorDistinctions) and copied to the pairs by every search. Read by H2 too, as jOOQ's classes are made of it.

-- The pairs of normalized authors who may be one, as the last search found them, written in the order of their key: no
-- index, no key kept — a page is a pass of the columns of its filter and its sort. Of two authors — the smaller id first
-- — whether they are bound to one entry of a catalogue, how many pairs of their works the user said are one, the
-- chances of the rest summed, the sites of their works (",a,b,"), and the user's word on them (DIFFERENT, PARTIAL, none
-- of an open pair): a pair answered struck out of the open ones at once, till the next search
CREATE TABLE author_candidate (
    first_author BIGINT NOT NULL,
    other_author BIGINT NOT NULL,
    one_entry BOOLEAN NOT NULL,
    matching INTEGER NOT NULL,
    expected DOUBLE PRECISION NOT NULL,
    sites VARCHAR(1000) NOT NULL,
    answer VARCHAR(20)
);

-- The last search: one row, of id 1 — when the authors' duplicates were looked for, of how many works
CREATE TABLE author_search (
    id INTEGER NOT NULL PRIMARY KEY,
    made_at TIMESTAMP WITH TIME ZONE NOT NULL,
    works INTEGER NOT NULL
);
