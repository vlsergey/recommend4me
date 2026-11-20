-- The model of the words to a character kept in one row, its weights by the features' names packed together
-- (owner, 2026-11-20): 345 thousand rows written anew at every fitting took 5 s, and every reader read them all. None
-- is kept now: the model is fitted again at the start, as of none fitted
CREATE TABLE work_size_model (
    fitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    content   BLOB                     NOT NULL
);

DROP TABLE work_size_weight;
