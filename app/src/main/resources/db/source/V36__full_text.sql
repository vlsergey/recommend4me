-- The key of the centre the full-text vectors of the windows of a chapter and of a text searched by meaning were made by
-- (fulltext.FullTextVectors), the vectors themselves in the store of the parts
ALTER TABLE part ADD COLUMN full_text VARCHAR(100);
ALTER TABLE text_vector ADD COLUMN full_text VARCHAR(100);
