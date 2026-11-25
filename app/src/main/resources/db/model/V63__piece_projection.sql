-- The projection of the pieces' vectors learnt of the type's own chapters (piece.PieceProjections): of the encoder of
-- the pieces, its rows (the directions a piece's vector is read along, every one of the head's width), the cuts and the
-- means of the codes of 2 bits a number, of how many pieces it was learnt, its key kept with every piece it made
CREATE TABLE piece_projection (
    encoder        VARCHAR(200)             PRIMARY KEY,
    projection_key VARCHAR(100)             NOT NULL,
    fitted_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    rows_count     INT                      NOT NULL,
    width          INT                      NOT NULL,
    projection     BLOB                     NOT NULL,
    codebook       BLOB                     NOT NULL,
    pieces         INT                      NOT NULL
);
