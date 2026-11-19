-- The fourth stage of the computations goes over the works not graded in the order of their predictions, a pass marked
-- in the index of the list by when it began (owner, 2026-11-19, docs/computations.md): no cursor of a source and an id.
-- A sweep under way begins again.
DELETE FROM score_sweep;
ALTER TABLE score_sweep DROP COLUMN source;
ALTER TABLE score_sweep DROP COLUMN after_id;
