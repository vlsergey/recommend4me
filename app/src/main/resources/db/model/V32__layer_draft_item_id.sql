-- The drafts of a few works found by their items alone: H2 takes neither `source = ? AND item_id IN (…)` nor a row
-- of the two `IN ((…), …)` by an index of both — it walked every draft, comparing each with every item asked — but
-- `item_id IN (…)` by an index whose first column it is, the source told apart of the rows found
DROP INDEX layer_draft_item;
CREATE INDEX layer_draft_item ON layer_draft (item_id);
