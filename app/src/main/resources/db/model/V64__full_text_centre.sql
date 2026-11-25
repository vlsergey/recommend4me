-- The centre of the windows' vectors of a type (meaning.FullTextCentres): of the encoder's windows, the mean of a sample
-- of the vectors kept, every window's full-text vector taken less it; its key kept with every text whose full-text vectors it made, of how
-- many vectors it was learnt
CREATE TABLE full_text_centre (
    encoder    VARCHAR(200)             PRIMARY KEY,
    centre_key VARCHAR(100)             NOT NULL,
    fitted_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    centre     BLOB                     NOT NULL,
    windows    INT                      NOT NULL
);
