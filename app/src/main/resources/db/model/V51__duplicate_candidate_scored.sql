-- The pairs of the duplicates chosen in the database for a page (owner, 2026-11-20: "load the duplicates from the
-- database with the filters and the sorts: by the time they are read, 30 candidates for a page of 30"): of every pair
-- its chance of one work and of one series as the models last scored it, the user's word on it as the search or the
-- last answer left it — a pair answered struck out of the open ones at once, till the next search —, and whether its two
-- works have the same authors, the same title. The candidates and the search are let go: the start searches anew, as of
-- a type never searched, and the search writes them scored.
TRUNCATE TABLE duplicate_candidate;
DELETE FROM duplicate_search;

ALTER TABLE duplicate_candidate ADD COLUMN (
    same_chance    DOUBLE PRECISION,
    series_chance  DOUBLE PRECISION,
    answer         VARCHAR(20),
    answer_derived BOOLEAN DEFAULT FALSE NOT NULL,
    same_authors   BOOLEAN DEFAULT FALSE NOT NULL,
    same_title     BOOLEAN DEFAULT FALSE NOT NULL
);

CREATE INDEX duplicate_candidate_same ON duplicate_candidate (answer, same_chance DESC);
CREATE INDEX duplicate_candidate_series ON duplicate_candidate (answer, series_chance DESC);

-- The pairs of a work as the second of them: what a work's screen reads its pairs by
CREATE INDEX duplicate_candidate_other ON duplicate_candidate (other_source, other_item_id);
