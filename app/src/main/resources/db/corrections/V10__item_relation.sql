-- Two items of different sources the user said are related works, neither one work nor two apart, by
-- [kind]: TRANSLATION — one the translation of the other; PARTIAL — one holds a part of the other (a
-- volume of a series, a collection of stories): not linked, each its own grade and interest, never
-- offered as a pair again, left out of the model of the answers. The translations said before moved
-- here. Kept as the links are: in the corrections of the source of the smaller of the two keys
-- "<source>/<item>", its item first
CREATE TABLE item_relation (
    item_id       VARCHAR(200)             NOT NULL,
    other_source  VARCHAR(100)             NOT NULL,
    other_item_id VARCHAR(200)             NOT NULL,
    kind          VARCHAR(20)              NOT NULL,
    said_at       TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (item_id, other_source, other_item_id)
);
CREATE INDEX item_relation_other ON item_relation (other_source, other_item_id);
INSERT INTO item_relation (item_id, other_source, other_item_id, kind, said_at)
    SELECT item_id, other_source, other_item_id, 'TRANSLATION', said_at FROM item_translation;
DROP TABLE item_translation;
