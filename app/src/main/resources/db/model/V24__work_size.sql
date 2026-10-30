-- How many words a normalized work has to a character, spaces among the characters: of its own numbers
-- when it has them, else by the model of them; the other of a work's two sizes is worked out of it
CREATE TABLE work_size (
    work           BIGINT           NOT NULL PRIMARY KEY,
    words_per_char DOUBLE PRECISION NOT NULL
);
