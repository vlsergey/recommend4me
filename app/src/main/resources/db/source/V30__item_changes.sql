-- When what the index of the list holds of an item last changed (item.ItemChanges) — its head, values, texts, the
-- counts of its chapters' texts, set by the write that changes them; the vectors of its chapters, its cast, what is
-- drafted of it — its document written anew by the next writing of the index after it (owner, 2026-11-21: "why name
-- them and keep the names at all? the index has the date it was last built, a work the date it was last changed").
-- Null — not changed since the index was written by its names
ALTER TABLE item ADD COLUMN changed_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX item_changed_at ON item (changed_at);

-- When every item of the source last changed in what the index of the list holds of it — a dictionary its values are
-- drafted by: one row, a date of the source as a whole
CREATE TABLE every_item_change (
    id INT NOT NULL PRIMARY KEY,
    changed_at TIMESTAMP WITH TIME ZONE NOT NULL
);
