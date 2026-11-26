-- The works named by the application's own numbers of their items in their sources' databases, not by the sites' ids
-- (item.internal_id; the values written so by the tool of that move, tools/rekey, the application stopped): a
-- number, as every table of a work's data in its source keeps it.
ALTER TABLE work_pair_journal ALTER COLUMN item SET DATA TYPE BIGINT;
ALTER TABLE work_pair_journal ALTER COLUMN other_item SET DATA TYPE BIGINT;
ALTER TABLE work_rating ALTER COLUMN item SET DATA TYPE BIGINT;
ALTER TABLE site_work ALTER COLUMN item SET DATA TYPE BIGINT;
