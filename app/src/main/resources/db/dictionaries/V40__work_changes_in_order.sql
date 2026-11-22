-- The normalized works in the order the index of the list takes them (worklist.ListChanges): by the date the user's
-- word on them changed, then by their id, a portion after the last it wrote (owner, 2026-11-22).
DROP INDEX normalized_work_changed_at;
CREATE INDEX normalized_work_changed_at ON normalized_work (changed_at, id);
