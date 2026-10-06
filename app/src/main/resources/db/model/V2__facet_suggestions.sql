-- THE SUGGESTED VALUES OF FACETS: what a suggester learnt of a facet of a source, and the values it
-- worked out for every item. Made again from the other files whenever they change.

-- The fitted state of the suggester of a facet; feedback — how many corrections of the facet the
-- user had made when it was fitted: it is fitted again when they grow
CREATE TABLE facet_suggester (
    source    VARCHAR(100)             NOT NULL,
    facet     VARCHAR(100)             NOT NULL,
    suggester VARCHAR(100)             NOT NULL,
    fitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    feedback  INT                      NOT NULL,
    content   BLOB                     NOT NULL,
    PRIMARY KEY (source, facet)
);

-- What the suggestions of an item were made of — its values, the user's word on them, its texts —
-- as a hash: the suggestions are made again when it changes
CREATE TABLE facet_suggestion_basis (
    source      VARCHAR(100)             NOT NULL,
    item_id     VARCHAR(200)             NOT NULL,
    facet       VARCHAR(100)             NOT NULL,
    fingerprint BIGINT                   NOT NULL,
    made_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (source, item_id, facet)
);

-- A value suggested for an item (the item lacks it, chance high) or doubted (the item has it from
-- the site, chance low): kind 'S' or 'D'
CREATE TABLE facet_suggestion (
    source    VARCHAR(100)     NOT NULL,
    item_id   VARCHAR(200)     NOT NULL,
    facet     VARCHAR(100)     NOT NULL,
    value_key VARCHAR(500)     NOT NULL,
    kind      CHAR(1)          NOT NULL,
    chance    DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (source, item_id, facet, value_key)
);
