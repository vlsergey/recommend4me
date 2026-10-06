-- The vectors of the names of facet values, as a query: what a text is held against to tell
-- whether the value fits it (the suggestions of tags). name_hash — of the name encoded
CREATE TABLE facet_value_vector (
    facet     VARCHAR(100)   NOT NULL,
    value_key VARCHAR(500)   NOT NULL,
    encoder   VARCHAR(100)   NOT NULL,
    name_hash VARCHAR(64)    NOT NULL,
    vec       BINARY VARYING NOT NULL,
    PRIMARY KEY (facet, value_key)
);
