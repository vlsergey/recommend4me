-- The other names a site gives one of its facet's values — the synonyms of a tag of ficbook: each a
-- value of its own, named in facet_value, and bound to the tag the value is bound to
CREATE TABLE facet_value_synonym (
    facet       VARCHAR(100) NOT NULL,
    synonym_key VARCHAR(500) NOT NULL,
    value_key   VARCHAR(500) NOT NULL,
    PRIMARY KEY (facet, synonym_key)
);
CREATE INDEX facet_value_synonym_of ON facet_value_synonym (facet, value_key);
