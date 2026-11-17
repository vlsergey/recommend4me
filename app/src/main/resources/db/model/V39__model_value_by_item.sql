-- The values the model gives a few works found by their ids alone: H2 reads `source = ? AND facet = ? AND item_id IN (…)`
-- by the prefix of the key and walks every value of the facet of the source, millions of a million works' tags
DROP INDEX model_value_item;
CREATE INDEX model_value_by_item ON model_value (item_id);
