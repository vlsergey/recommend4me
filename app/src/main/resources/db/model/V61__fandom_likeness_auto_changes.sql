-- Of how many universes the likeness of the fandoms' names was learnt, and how many changes of no word of the user's
-- came since — the names of the sites' fandoms encoded, what a site told of its fandoms —: learnt again once they come
-- to a hundredth of those universes (owner, 2026-11-24). A likeness learnt before tells no universes: the first change
-- learns it again
ALTER TABLE fandom_likeness ADD COLUMN universes INT;
ALTER TABLE fandom_likeness ADD COLUMN auto_changes INT DEFAULT 0 NOT NULL;
