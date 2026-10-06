-- WHAT A SITE SAYS OF ITS ITEMS, and the vectors made of it: a vector is a compressed form of the
-- same scrape. Nothing of the user's is here: their grades, marks and corrections live in files of
-- their own beside this one.
--
-- WIDE TEXTS LIVE IN TABLES OF THEIR OWN: H2 (MVStore, with MVCC) writes a whole row anew to change
-- one column of it, and copies the CLOBs with the row.

CREATE TABLE item (
    item_id       VARCHAR(200)             PRIMARY KEY,
    url           VARCHAR(2000)            NOT NULL,
    title         VARCHAR(1000)            NOT NULL,
    -- The release the item is at; the same for an item without versions
    version       VARCHAR(500)             NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    first_seen_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX item_updated_at ON item (updated_at DESC);

-- Every version of an item seen
CREATE TABLE item_version (
    item_id    VARCHAR(200)             NOT NULL REFERENCES item (item_id) ON DELETE CASCADE,
    version    VARCHAR(500)             NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    seen_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (item_id, version)
);

-- Categorical values: tags, engine, status, developer, fandom… by a stable key
CREATE TABLE item_facet (
    item_id   VARCHAR(200) NOT NULL REFERENCES item (item_id) ON DELETE CASCADE,
    facet     VARCHAR(100) NOT NULL,
    value_key VARCHAR(500) NOT NULL,
    -- The order the site lists them in
    position  INT          NOT NULL,
    PRIMARY KEY (item_id, facet, value_key)
);
CREATE INDEX item_facet_value ON item_facet (facet, value_key);

-- The names of the values a site names by key (tag ids)
CREATE TABLE facet_value (
    facet     VARCHAR(100)  NOT NULL,
    value_key VARCHAR(500)  NOT NULL,
    name      VARCHAR(1000) NOT NULL,
    PRIMARY KEY (facet, value_key)
);

CREATE TABLE item_number (
    item_id    VARCHAR(200)     NOT NULL REFERENCES item (item_id) ON DELETE CASCADE,
    number_key VARCHAR(100)     NOT NULL,
    content    DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (item_id, number_key)
);

CREATE TABLE item_text (
    item_id  VARCHAR(200) NOT NULL REFERENCES item (item_id) ON DELETE CASCADE,
    text_key VARCHAR(100) NOT NULL,
    content  CLOB         NOT NULL,
    PRIMARY KEY (item_id, text_key)
);

-- The pictures: position 0 the cover, 1.. the screenshots. Each is analysed by the picture encoder;
-- the files lie in the source's images folder, not here: a preview always, the original only for
-- works graded "can be played" or more
CREATE TABLE picture (
    item_id      VARCHAR(200)  NOT NULL REFERENCES item (item_id) ON DELETE CASCADE,
    position     INT           NOT NULL,
    url          VARCHAR(2000) NOT NULL,
    preview_file VARCHAR(300),
    full_file    VARCHAR(300),
    vec          BINARY VARYING,
    encoder      VARCHAR(100),
    analyzed_at  TIMESTAMP WITH TIME ZONE,
    -- Why the picture could not be analysed; not tried again until its address changes
    error        VARCHAR(500),
    PRIMARY KEY (item_id, position)
);
-- The encoder is written and cleared together with the vector: counting it counts the analysed pictures
CREATE INDEX picture_by_encoder ON picture (encoder);

-- What the model reads of an item's pictures, kept per item: the cover's vector and the mean
-- direction of the screenshots, float16 — two rows per item instead of every picture
CREATE TABLE item_picture_vector (
    item_id VARCHAR(200)   NOT NULL REFERENCES item (item_id) ON DELETE CASCADE,
    block   VARCHAR(32)    NOT NULL,
    encoder VARCHAR(100)   NOT NULL,
    vec     BINARY VARYING NOT NULL,
    PRIMARY KEY (item_id, block)
);

-- Opinions of other people: reviews, comments. text_key is the key their text is encoded under
-- (the first 64 bits of the SHA-1 of the text as the model reads it)
CREATE TABLE review (
    item_id   VARCHAR(200) NOT NULL REFERENCES item (item_id) ON DELETE CASCADE,
    review_id VARCHAR(100) NOT NULL,
    author    VARCHAR(300),
    stars     DOUBLE PRECISION,
    content   CLOB         NOT NULL,
    posted_at TIMESTAMP WITH TIME ZONE,
    text_key  BIGINT,
    PRIMARY KEY (item_id, review_id)
);
CREATE INDEX review_by_text_key ON review (text_key);

-- The parts of a work's text: chapters. The text is kept, and a vector of every window of it
CREATE TABLE part (
    item_id      VARCHAR(200)  NOT NULL REFERENCES item (item_id) ON DELETE CASCADE,
    part_id      VARCHAR(100)  NOT NULL,
    position     INT           NOT NULL,
    title        VARCHAR(1000),
    content      CLOB,
    published_at TIMESTAMP WITH TIME ZONE,
    -- The hash of the text the windows' vectors were made of, and by which encoder
    encoded_hash VARCHAR(64),
    encoder      VARCHAR(100),
    PRIMARY KEY (item_id, part_id)
);

CREATE TABLE part_vector (
    item_id     VARCHAR(200)   NOT NULL,
    part_id     VARCHAR(100)   NOT NULL,
    window_no   INT            NOT NULL,
    vec         BINARY VARYING NOT NULL,
    PRIMARY KEY (item_id, part_id, window_no),
    FOREIGN KEY (item_id, part_id) REFERENCES part (item_id, part_id) ON DELETE CASCADE
);

-- Vectors of the texts, with the hash of the text they encode and the encoder that made them
CREATE TABLE text_vector (
    item_id   VARCHAR(200)   NOT NULL REFERENCES item (item_id) ON DELETE CASCADE,
    text_key  VARCHAR(100)   NOT NULL,
    encoder   VARCHAR(100)   NOT NULL,
    text_hash VARCHAR(64)    NOT NULL,
    vec       BINARY VARYING NOT NULL,
    PRIMARY KEY (item_id, text_key)
);

-- Vectors of short texts — whole reviews, phrases of the search — by the key of the text, float16
CREATE TABLE phrase_vector (
    text_key BIGINT         PRIMARY KEY,
    encoder  VARCHAR(100)   NOT NULL,
    vec      BINARY VARYING NOT NULL
);

-- The sets of an item — its screenshots, its reviews, the windows of its text — as vectors of fixed
-- length (sliced-Wasserstein embeddings) by the directions of the content type (embedding_id);
-- source: how many members the set had when it was made
CREATE TABLE set_vector (
    item_id      VARCHAR(200)   NOT NULL REFERENCES item (item_id) ON DELETE CASCADE,
    kind         VARCHAR(20)    NOT NULL,
    embedding_id BIGINT         NOT NULL,
    source       INT            NOT NULL,
    vec          BINARY VARYING NOT NULL,
    PRIMARY KEY (item_id, kind)
);

-- Pages kept to be read again when the source's parser changes: those the browser sent and those
-- the source downloaded; gzip
CREATE TABLE captured_page (
    url            VARCHAR(2000)            PRIMARY KEY,
    item_id        VARCHAR(200),
    captured_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    parser_version INT                      NOT NULL,
    html           BLOB                     NOT NULL
);
CREATE INDEX captured_page_by_parser ON captured_page (parser_version);
