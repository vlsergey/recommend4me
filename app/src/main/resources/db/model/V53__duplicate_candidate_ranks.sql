-- What every sort of the pairs of duplicates orders them by, worked out when the search fills the table (owner,
-- 2026-11-20: "do it exactly when the table is filled"), each of an index of its own beside the word on the pair: a page
-- walks the index of its sort and reads its rows alone — of every sort, of 1.2 million pairs H2 read the whole table and
-- sorted it on the disk, 21 s of a page. The candidates and the search are let go: the start searches anew, as of a type
-- never searched, and the search writes them ranked.
TRUNCATE TABLE duplicate_candidate;
DELETE FROM duplicate_search;

ALTER TABLE duplicate_candidate ADD COLUMN (
    by_size         DOUBLE PRECISION,
    by_series_size  DOUBLE PRECISION,
    by_doubt        DOUBLE PRECISION,
    by_series_doubt DOUBLE PRECISION,
    by_formula      DOUBLE PRECISION,
    by_interest     DOUBLE PRECISION,
    by_grade        DOUBLE PRECISION
);

CREATE INDEX duplicate_candidate_by_chance ON duplicate_candidate (answer, same_chance DESC);
CREATE INDEX duplicate_candidate_by_series ON duplicate_candidate (answer, series_chance DESC);
CREATE INDEX duplicate_candidate_by_size ON duplicate_candidate (answer, by_size DESC);
CREATE INDEX duplicate_candidate_by_series_size ON duplicate_candidate (answer, by_series_size DESC);
CREATE INDEX duplicate_candidate_by_doubt ON duplicate_candidate (answer, by_doubt DESC);
CREATE INDEX duplicate_candidate_by_series_doubt ON duplicate_candidate (answer, by_series_doubt DESC);
CREATE INDEX duplicate_candidate_by_formula ON duplicate_candidate (answer, by_formula DESC);
CREATE INDEX duplicate_candidate_by_interest ON duplicate_candidate (answer, by_interest DESC);
CREATE INDEX duplicate_candidate_by_grade ON duplicate_candidate (answer, by_grade DESC);
