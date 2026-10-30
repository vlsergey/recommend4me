-- Two items of different sources the user said are one a TRANSLATION of the other: different works —
-- not linked, each its own grade and interest — of one text, never offered as a pair again. Kept as
-- the links and the different ones are (item_link, item_distinct): in the corrections of the source of
-- the smaller of the two keys "<source>/<item>", its item first
CREATE TABLE item_translation (
    item_id       VARCHAR(200)             NOT NULL,
    other_source  VARCHAR(100)             NOT NULL,
    other_item_id VARCHAR(200)             NOT NULL,
    said_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (item_id, other_source, other_item_id)
);
CREATE INDEX item_translation_other ON item_translation (other_source, other_item_id);
