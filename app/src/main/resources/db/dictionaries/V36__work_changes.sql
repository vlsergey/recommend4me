-- When the user's word on a normalized work last changed — a value or a field answered or taken back, a grade, a mark
-- of interest — or its items did, merged or taken out (work.WorkChanges): the documents of its items in the index of
-- the list written anew by the next writing of it after it (owner, 2026-11-21: a work has the date it was last
-- changed). Null — not changed since the index was written by its names
ALTER TABLE normalized_work ADD COLUMN changed_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX normalized_work_changed_at ON normalized_work (changed_at);
