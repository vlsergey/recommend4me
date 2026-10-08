-- The prediction of every graded item by a model that had not seen its work: the score the
-- cross-validation gave it from the fold it was left out of, on 0..10. Written by a training only.
CREATE TABLE held_out (
    source  VARCHAR(100)     NOT NULL,
    item_id VARCHAR(200)     NOT NULL,
    score   DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (source, item_id)
);
