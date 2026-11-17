-- The texts of the parts and the vectors of their windows and paragraphs are kept outside the database, in the
-- source's store of parts (PartStore, user 2026-11-17): moved there by the migration before this one (V19, a migration
-- of the application's own code, PartsMovedOut). What a part has stays: its text's hash says it has a text
ALTER TABLE part DROP COLUMN content;
DROP TABLE part_vector;
DROP TABLE part_paragraph_vector;
