-- Two works of different sites that may be one, as the last search of the type found them: of every
-- work, the works of every other site whose title and description are nearest to its own — the
-- smaller key "<source>/<item>" first. [title]: the cosine of the vectors of their titles;
-- [description]: of their descriptions, none when either has none; [similarity]: what the pairs are
-- shown by, the mean of the two (the title's alone when there is no description)
CREATE TABLE duplicate_candidate (
    source        VARCHAR(100) NOT NULL,
    item_id       VARCHAR(200) NOT NULL,
    other_source  VARCHAR(100) NOT NULL,
    other_item_id VARCHAR(200) NOT NULL,
    title         REAL         NOT NULL,
    description   REAL,
    similarity    REAL         NOT NULL,
    PRIMARY KEY (source, item_id, other_source, other_item_id)
);
CREATE INDEX duplicate_candidate_similarity ON duplicate_candidate (similarity DESC);

-- When the candidates were last searched for, by which encoder of texts: one row
CREATE TABLE duplicate_search (
    id       INT                      NOT NULL PRIMARY KEY,
    made_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    encoder  VARCHAR(100)             NOT NULL,
    works    INT                      NOT NULL
);
