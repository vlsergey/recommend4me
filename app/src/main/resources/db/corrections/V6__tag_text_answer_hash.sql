-- An answer on a tag by the text bound to its paragraph by the hash of the paragraph's text: the
-- paragraph is the same whatever its number in another cutting or in a text changed. The part and
-- the number it had stay as where it was answered. Of the answers given before, filled by the
-- application at its start, of the texts and the cutting they were given on
ALTER TABLE tag_text_answer ADD COLUMN paragraph_hash VARCHAR(64);
CREATE INDEX tag_text_answer_by_hash ON tag_text_answer (item_id, tag, paragraph_hash);
