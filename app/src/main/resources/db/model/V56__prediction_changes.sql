-- When an item's prediction last changed as the index of the list keeps it — its grade, its chance of interest, to
-- three digits (owner, 2026-11-21: "not every change is to reach the index: the grade and the interest are three
-- digits") — set by the write that changes it (model.PredictionRepository.write): the values of its document written
-- in place by the next writing of them after it. Null — not changed since they were written by their names
ALTER TABLE prediction ADD COLUMN changed_at TIMESTAMP WITH TIME ZONE;
CREATE INDEX prediction_changed_at ON prediction (changed_at);
