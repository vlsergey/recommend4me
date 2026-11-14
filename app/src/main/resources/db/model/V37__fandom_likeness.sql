-- The likeness of the names of the sites' fandoms learnt of the user's word on the universes: a weight of every
-- dimension of their vectors (content), the encoder's own of every weight 1; of how many pairs it was learnt, and how
-- many of the pairs of universes held out of the learning it put in the user's order, and the encoder's own
CREATE TABLE fandom_likeness (
    id          BIGINT                   PRIMARY KEY,
    fitted_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    pairs       INT                      NOT NULL,
    held_out    DOUBLE PRECISION,
    as_encoded  DOUBLE PRECISION,
    content     BLOB                     NOT NULL
);
