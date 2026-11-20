-- The pairs of normalized authors who may be one, as the last search of the authors' duplicates found them (owner,
-- 2026-11-20: "the tab of the authors' duplicates reads a bounded amount of the database"; searched by a button, as the
-- works' duplicates are): of two authors whether they are bound to one entry of a catalogue, how many pairs of their
-- works the user said are one, the chances of the rest summed, the sites of their works, and the user's word on them —
-- a pair answered struck out of the open ones at once, till the next search
CREATE TABLE author_candidate (
    first_author BIGINT           NOT NULL,
    other_author BIGINT           NOT NULL,
    one_entry    BOOLEAN          NOT NULL,
    matching     INTEGER          NOT NULL,
    expected     DOUBLE PRECISION NOT NULL,
    sites        VARCHAR(1000)    NOT NULL,
    answer       VARCHAR(20),
    PRIMARY KEY (first_author, other_author)
);

-- The open pairs in the order a page shows them, and an author's pairs as the second of them
CREATE INDEX author_candidate_order ON author_candidate (answer, one_entry DESC, matching DESC, expected DESC);
CREATE INDEX author_candidate_other ON author_candidate (other_author);

-- When the authors' duplicates were last looked for, of how many works
CREATE TABLE author_search (
    id      INTEGER                  NOT NULL PRIMARY KEY,
    made_at TIMESTAMP WITH TIME ZONE NOT NULL,
    works   INTEGER                  NOT NULL
);
