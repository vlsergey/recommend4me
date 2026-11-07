-- The models of the pairs come from a plugin (PairModel): every kind of pair a model of its own — one work, one series
-- of works —, each kept as its plugin packs it, with the plugin's id. The weights kept before ("<feature>=<weight>" a
-- line) are what the logistic plugin packs: kept as the model of one work of it, read on as they were.
ALTER TABLE duplicate_search ADD COLUMN model VARCHAR(100);
ALTER TABLE duplicate_search ADD COLUMN same_model BLOB;
ALTER TABLE duplicate_search ADD COLUMN series_model BLOB;
UPDATE duplicate_search SET model = 'pair-logistic', same_model = STRINGTOUTF8(CAST(weights AS VARCHAR)) WHERE weights IS NOT NULL;
ALTER TABLE duplicate_search DROP COLUMN weights;
