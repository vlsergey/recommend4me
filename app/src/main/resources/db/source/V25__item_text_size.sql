-- The words and the characters of an item's kept texts, every part's summed: written as its parts are written, each
-- part's own counted as its text is (part.words, part.chars) — read as they lie, never counted at a reading. Of an item
-- of no text counted, none; the items kept before are filled by a tool run once
ALTER TABLE item ADD COLUMN text_words INTEGER;
ALTER TABLE item ADD COLUMN text_chars INTEGER;
