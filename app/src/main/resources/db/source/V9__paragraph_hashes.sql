-- The hash of a paragraph's text beside its vector: what an answer on a tag by the text is bound
-- to — the paragraph itself, whatever its number in another cutting or in a text changed
ALTER TABLE part_paragraph_vector ADD COLUMN text_hash VARCHAR(64);
