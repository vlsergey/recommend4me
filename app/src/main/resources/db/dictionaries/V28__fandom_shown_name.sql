-- The name a universe is shown by, worked out ahead and kept: the user's own, else the name of the first entry of the
-- catalogue it is bound to in the user's languages, else the one worked out of its sites' fandoms (calculated_name) —
-- a page of the list names its few universes by their ids alone, no dictionary read (user, 2026-11-17)
ALTER TABLE fandom ADD COLUMN shown_name VARCHAR(1000);
