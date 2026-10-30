-- Two items of different sources the user said are DIFFERENT works: never offered as one again. Kept
-- as the links across the sources are (item_link) — in the corrections of the source of the smaller
-- of the two keys "<source>/<item>", its item first
CREATE TABLE item_distinct (
    item_id       VARCHAR(200)             NOT NULL,
    other_source  VARCHAR(100)             NOT NULL,
    other_item_id VARCHAR(200)             NOT NULL,
    said_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (item_id, other_source, other_item_id)
);
CREATE INDEX item_distinct_other ON item_distinct (other_source, other_item_id);
