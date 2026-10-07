-- How many members of the catalogue the directions of a kind of sets were fitted on: as the
-- catalogue grows, the directions are fitted again on what it has. None for directions fitted
-- before it was kept: fitted again once.
ALTER TABLE set_embedding ADD COLUMN members INT;
