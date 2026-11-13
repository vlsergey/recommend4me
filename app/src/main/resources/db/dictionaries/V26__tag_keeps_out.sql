-- The user's word that a tag keeps its works out of the library (user, 2026-11-13): a work a site tags with it - or
-- with a tag under it - is not kept, of every site alike: written, it is removed at once, with everything of it
ALTER TABLE normalized_tag ADD COLUMN keeps_out BOOLEAN DEFAULT FALSE NOT NULL;
