-- The user's word that whether a work has a value of a facet is not clear of it — a pairing the
-- texts do not say outright: the value is asked of it no more, nothing learnt from it, the work's
-- values as they were
CREATE TABLE facet_unknown (
    item_id   VARCHAR(200)             NOT NULL,
    facet     VARCHAR(100)             NOT NULL,
    value_key VARCHAR(500)             NOT NULL,
    said_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (item_id, facet, value_key)
);
CREATE INDEX facet_unknown_of ON facet_unknown (facet, value_key);
