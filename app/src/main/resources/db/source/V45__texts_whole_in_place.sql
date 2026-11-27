-- The vectors of the texts read whole (V44) put in the place of those of before; the windows of the texts gone with
-- them: no text is read in windows any more, a description is one vector (owner, 2026-11-27).
DROP TABLE text_window;
DROP TABLE text_vector;
ALTER TABLE text_vector_whole RENAME TO text_vector;
ALTER TABLE text_vector ADD CONSTRAINT text_vector_key PRIMARY KEY(item_id, text_key);
ALTER TABLE text_vector ADD CONSTRAINT text_vector_item FOREIGN KEY(item_id) REFERENCES item(internal_id) ON DELETE CASCADE NOCHECK;
-- What is held of a vector to know it made, read of this index alone, not of the rows of the vectors: of a pass over a
-- thousand works the rows of their vectors, two kilobytes each, were a seek of the disk a work, and half of the pass
-- (2026-11-26); H2 reads the columns of an index of them all without the row
CREATE INDEX text_vector_kept ON text_vector(item_id, text_key, encoder, text_hash, pieces, full_text);
-- How far the copy of V44 got
DROP TABLE text_vector_copied;
