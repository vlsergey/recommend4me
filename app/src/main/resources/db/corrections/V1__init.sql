-- THE USER'S CORRECTIONS of what a site says: read on top of the source's data wherever an item is
-- shown, searched or learnt from.

-- A value of a facet added to an item (added = TRUE) or taken from it (FALSE); name — the name of
-- a value the user made up
CREATE TABLE facet_correction (
    item_id      VARCHAR(200)             NOT NULL,
    facet        VARCHAR(100)             NOT NULL,
    value_key    VARCHAR(500)             NOT NULL,
    added        BOOLEAN                  NOT NULL,
    name         VARCHAR(1000),
    corrected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (item_id, facet, value_key)
);

-- A field overridden: "title", "text:<key>", "number:<key>"
CREATE TABLE field_correction (
    item_id      VARCHAR(200)             NOT NULL,
    field        VARCHAR(120)             NOT NULL,
    content      CLOB                     NOT NULL,
    corrected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (item_id, field)
);

-- Two items of this source that are one work (a re-upload, a moved thread); the smaller id first
CREATE TABLE item_link (
    item_id       VARCHAR(200)             NOT NULL,
    other_item_id VARCHAR(200)             NOT NULL,
    linked_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (item_id, other_item_id)
);
