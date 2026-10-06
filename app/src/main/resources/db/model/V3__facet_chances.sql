-- WHAT THE MODEL SAYS OF THE VALUES OF FACETS: the chance of every value an item has, and of every
-- value it lacks that it more likely has than not. Recomputable: what was kept before is dropped.
DELETE FROM facet_suggestion;
DELETE FROM facet_suggestion_basis;
DELETE FROM facet_suggester;

-- The suggester is fitted again whenever what it learns from changes: basis is the fingerprint of
-- the whole facet — every item's values, the user's answers and texts — it was fitted on
ALTER TABLE facet_suggester DROP COLUMN feedback;
ALTER TABLE facet_suggester ADD COLUMN basis BIGINT NOT NULL;

-- kind: 'A' a value the item had when worked out, 'S' one it lacked
COMMENT ON COLUMN facet_suggestion.kind IS 'A - a value the item had when worked out; S - one it lacked, more likely than not';
