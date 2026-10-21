-- The cutting of the texts into paragraphs an answer on a tag by the text was given on: a paragraph
-- is known by its number in its part, and another cutting numbers other paragraphs. The first one
-- cut every line apart; the answers given before the second (lines shorter than 200 characters in a
-- row joined), from 2026-10-21 14:12:51 UTC, are of the first: kept, but neither hiding a paragraph
-- nor teaching the model any more
ALTER TABLE tag_text_answer ADD COLUMN cutting INT DEFAULT 1 NOT NULL;
UPDATE tag_text_answer SET cutting = 2 WHERE said_at >= TIMESTAMP WITH TIME ZONE '2026-10-21 14:12:51+00';
