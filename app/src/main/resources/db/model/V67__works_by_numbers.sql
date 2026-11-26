-- The works named by the application's own numbers of their items in their sources' databases, not by the sites' ids
-- (item.internal_id; the values written so by the tool of that move, tools/rekey, the application stopped): a
-- number, as every table of a work's data in its source keeps it.
ALTER TABLE held_out ALTER COLUMN item_id SET DATA TYPE BIGINT;
ALTER TABLE mark_likeness_strength ALTER COLUMN item_id SET DATA TYPE BIGINT;
ALTER TABLE mark_likeness_axes ALTER COLUMN item_id SET DATA TYPE BIGINT;
ALTER TABLE held_out_explained ALTER COLUMN item_id SET DATA TYPE BIGINT;
ALTER TABLE search_score ALTER COLUMN item_id SET DATA TYPE BIGINT;
