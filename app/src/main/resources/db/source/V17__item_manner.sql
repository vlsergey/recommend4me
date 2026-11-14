-- The manner of an item's text: the share in it of every word of the words its content type measures a manner by
-- (words_id, the model database's manner_words), each standardised over the works; made of the texts of its parts as
-- text_hash tells them (the hash of their hashes), made again when they change
CREATE TABLE item_manner (
    item_id   VARCHAR(200) PRIMARY KEY REFERENCES item (item_id) ON DELETE CASCADE,
    words_id  BIGINT       NOT NULL,
    text_hash VARCHAR(64)  NOT NULL,
    vec       BINARY VARYING NOT NULL
);
