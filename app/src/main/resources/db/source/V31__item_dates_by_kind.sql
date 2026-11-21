-- When every outer kind of data of an item last changed (item.ItemChanges, worklist.WorkDatum), set by the write that
-- changes it: what the site says of it (its head, values, numbers, texts, and what is drafted of it), its chapters'
-- texts, its reviews, its pictures analysed — what the stages of the tasks over the works take it by, each by the kinds
-- it reads (owner, 2026-11-21: "a self-evident decomposition of dependencies"). changed_at stays when any did: what the
-- index of the list writes anew. Null — not changed since the stages kept their marks. In one statement: H2 copies the
-- table once
ALTER TABLE item ADD COLUMN (
    data_at TIMESTAMP WITH TIME ZONE,
    chapters_at TIMESTAMP WITH TIME ZONE,
    reviews_at TIMESTAMP WITH TIME ZONE,
    pictures_at TIMESTAMP WITH TIME ZONE
);
