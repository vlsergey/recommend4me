-- The items in the order the index of the list takes them (worklist.ListChanges): by the date of their change, then by
-- their key, a portion after the last it wrote (owner, 2026-11-22: "it picks of the database every document changed
-- after the mark, sorted, and puts them into the index in portions, moving its watermark"). The items of no date first.
DROP INDEX item_changed_at;
CREATE INDEX item_changed_at ON item (changed_at NULLS FIRST, item_id);
