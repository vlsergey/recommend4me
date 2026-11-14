-- The words a manner is measured by, of the content type: the most frequent words of the texts of its works, with the
-- mean and the spread of every one's share over them; fitted again when the number of the items with a text changes,
-- every item's manner then made again by them (the source database's item_manner, by the id)
CREATE TABLE manner_words (
    id        BIGINT                   PRIMARY KEY,
    texts     INT                      NOT NULL,
    fitted_at TIMESTAMP WITH TIME ZONE NOT NULL,
    content   BLOB                     NOT NULL
);
