-- The order of what a search finds, learnt from the user's word on the works found for the lines searched
-- (search_judgment, in the preferences): of every feature a work is found by — how close by meaning, how its words
-- matched, what the reranker said — its weight, of the feature read on a scale of its own, (value - mean) / scale.
-- None while no line has a work judged to answer it and one judged not to: the order is the reranker's
CREATE TABLE search_weight (
    feature   VARCHAR(100)             NOT NULL PRIMARY KEY,
    weight    DOUBLE PRECISION         NOT NULL,
    mean      DOUBLE PRECISION         NOT NULL,
    scale     DOUBLE PRECISION         NOT NULL,
    fitted_at TIMESTAMP WITH TIME ZONE NOT NULL
);
