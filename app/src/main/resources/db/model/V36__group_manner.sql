-- The manner of every normalized author (kind AUTHOR, group_key the author's id) and of every normalized series (kind
-- SERIES, group_key the series' key) of the content type: the mean of the manners of their works that have a text, by
-- the words words_id; made_of the hash of what it was made of — the words, every work and the texts of its items —,
-- made again when it changes; works how many works it is the mean of
CREATE TABLE group_manner (
    kind      VARCHAR(10)    NOT NULL,
    group_key VARCHAR(200)   NOT NULL,
    words_id  BIGINT         NOT NULL,
    made_of   VARCHAR(64)    NOT NULL,
    works     INT            NOT NULL,
    vec       BINARY VARYING NOT NULL,
    PRIMARY KEY (kind, group_key)
);
