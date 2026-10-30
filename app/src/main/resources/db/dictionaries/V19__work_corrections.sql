-- THE USER'S WORD ON A NORMALIZED WORK, over what its sites say: read on top of their data wherever
-- the work is shown, searched or learnt from.

-- A value of a facet of the application's layer added to a work (added = TRUE) or taken from it
-- (FALSE); name — the name of a value the user made up
CREATE TABLE work_facet_correction (
    work         BIGINT                   NOT NULL REFERENCES normalized_work (id) ON DELETE CASCADE,
    facet        VARCHAR(100)             NOT NULL,
    value_key    VARCHAR(500)             NOT NULL,
    added        BOOLEAN                  NOT NULL,
    name         VARCHAR(1000),
    corrected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (work, facet, value_key)
);
CREATE INDEX work_facet_correction_value ON work_facet_correction (facet, value_key);

-- A field overridden: "title", "text:<key>", "number:<key>"
CREATE TABLE work_field_correction (
    work         BIGINT                   NOT NULL REFERENCES normalized_work (id) ON DELETE CASCADE,
    field        VARCHAR(120)             NOT NULL,
    content      CLOB                     NOT NULL,
    corrected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (work, field)
);

-- The user's word that a work's values of a facet are all named: every other value of the facet is
-- taken from it, as if rejected one by one
CREATE TABLE work_facet_complete (
    work         BIGINT                   NOT NULL REFERENCES normalized_work (id) ON DELETE CASCADE,
    facet        VARCHAR(100)             NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (work, facet)
);

-- The user's word that whether a work has a value of a facet is not clear of it: the value is asked
-- of it no more, nothing learnt from it, the work's values as they were
CREATE TABLE work_facet_unknown (
    work      BIGINT                   NOT NULL REFERENCES normalized_work (id) ON DELETE CASCADE,
    facet     VARCHAR(100)             NOT NULL,
    value_key VARCHAR(500)             NOT NULL,
    said_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (work, facet, value_key)
);
CREATE INDEX work_facet_unknown_of ON work_facet_unknown (facet, value_key);
