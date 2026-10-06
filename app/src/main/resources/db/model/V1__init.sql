-- WHAT IS LEARNT FOR A CONTENT TYPE: all of it can be made again from the other files.

-- The current model: the scorer that made it, its description as JSON and its packed state
CREATE TABLE model (
    id         INT                      PRIMARY KEY,
    scorer     VARCHAR(100)             NOT NULL,
    trained_at TIMESTAMP WITH TIME ZONE NOT NULL,
    meta       CLOB                     NOT NULL,
    weights    BLOB                     NOT NULL
);

-- The score of every item on the user's 0..10. A narrow table of its own: rewritten at every training
CREATE TABLE prediction (
    source  VARCHAR(100)     NOT NULL,
    item_id VARCHAR(200)     NOT NULL,
    score   DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (source, item_id)
);
CREATE INDEX prediction_score ON prediction (score DESC);

-- The directions of the set embeddings, fitted once on a sample of the content type's catalogue
-- and kept: fitted again, every stored set vector would be of another space (embedding_id)
CREATE TABLE set_embedding (
    kind         VARCHAR(20)              PRIMARY KEY,
    embedding_id BIGINT                   NOT NULL,
    encoder      VARCHAR(100)             NOT NULL,
    fitted_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    content      BLOB                     NOT NULL
);

-- The likeness of every item to what the user marked, made again when the marks or the words on
-- their matches change (the fingerprint) or the catalogue has grown by a few percent (items)
CREATE TABLE mark_likeness (
    mark_kind   VARCHAR(16)              PRIMARY KEY,
    made_at     TIMESTAMP WITH TIME ZONE NOT NULL,
    fingerprint BIGINT                   NOT NULL,
    items       BIGINT                   NOT NULL,
    -- The marks' directions and the catalogue's cosines to them, deflated
    calibration BLOB                     NOT NULL
);

-- Every item's strength of likeness to every mark, float32 in the order of the calibration's marks
CREATE TABLE mark_likeness_strength (
    mark_kind VARCHAR(16)    NOT NULL,
    source    VARCHAR(100)   NOT NULL,
    item_id   VARCHAR(200)   NOT NULL,
    strength  BINARY VARYING NOT NULL,
    PRIMARY KEY (mark_kind, source, item_id)
);

-- An item's four axes of likeness to the marks (its own marks not counted), apart from the
-- strengths: a training needs the strengths of the graded items only, the axes of all the others
CREATE TABLE mark_likeness_axes (
    mark_kind VARCHAR(16)    NOT NULL,
    source    VARCHAR(100)   NOT NULL,
    item_id   VARCHAR(200)   NOT NULL,
    axes      BINARY VARYING NOT NULL,
    PRIMARY KEY (mark_kind, source, item_id)
);
