-- Two authors neither one nor quite apart: a part of the works of one is the other's, as of an account
-- putting up the works of others or of two writing together. Not merged, not offered again
ALTER TABLE normalized_author_distinct ADD COLUMN partly BOOLEAN DEFAULT FALSE NOT NULL;

-- An entry of a catalogue (Wikidata) a normalized author is bound to by the user
CREATE TABLE normalized_author_entry (
    author    BIGINT                   NOT NULL REFERENCES normalized_author (id) ON DELETE CASCADE,
    catalogue VARCHAR(100)             NOT NULL,
    entry     VARCHAR(100)             NOT NULL,
    bound_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (author, catalogue, entry),
    FOREIGN KEY (catalogue, entry) REFERENCES tag_entry (catalogue, entry)
);
CREATE INDEX normalized_author_entry_entry ON normalized_author_entry (catalogue, entry);
