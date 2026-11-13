-- The closeness of a line searched and a passage by meaning, corrected by the user's word on the works found: of the
-- encoder's own, q.p, made q.p + (q.a)(b.p) - the matrix of the corrections the outer product of the two vectors of
-- weights of the dimensions a and b, float32 little-endian. The line's vector is moved by it before it is searched,
-- nothing of the index made again. Of the encoder whose vectors it was learnt of; none while no line has a work judged
-- to answer it and one judged not to
CREATE TABLE search_query_shift (
    encoder   VARCHAR(100)             NOT NULL PRIMARY KEY,
    a         BLOB                     NOT NULL,
    b         BLOB                     NOT NULL,
    fitted_at TIMESTAMP WITH TIME ZONE NOT NULL
);
