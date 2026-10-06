-- Two items of different sources of a content type that are one work: one card, one grade. The
-- smaller of the two keys ("<source>/<item>") first
CREATE TABLE item_link (
    source        VARCHAR(100)             NOT NULL,
    item_id       VARCHAR(200)             NOT NULL,
    other_source  VARCHAR(100)             NOT NULL,
    other_item_id VARCHAR(200)             NOT NULL,
    linked_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (source, item_id, other_source, other_item_id)
);
