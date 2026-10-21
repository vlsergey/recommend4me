-- The vector of every paragraph of a part — a line of its text with a letter in it, counted from
-- nought — what the tags are held against to be offered by the text of the work itself, the
-- paragraph quoted beside the tag. Made beside the windows, by the same encoder, of the text of
-- the hash told: a part whose text changes is encoded again
ALTER TABLE part ADD COLUMN paragraphs_hash VARCHAR(64);
ALTER TABLE part ADD COLUMN paragraphs_encoder VARCHAR(100);

CREATE TABLE part_paragraph_vector (
    item_id      VARCHAR(200)   NOT NULL,
    part_id      VARCHAR(100)   NOT NULL,
    paragraph_no INT            NOT NULL,
    vec          BINARY VARYING NOT NULL,
    PRIMARY KEY (item_id, part_id, paragraph_no),
    FOREIGN KEY (item_id, part_id) REFERENCES part (item_id, part_id) ON DELETE CASCADE
);
