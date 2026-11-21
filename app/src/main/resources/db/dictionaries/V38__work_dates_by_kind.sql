-- When every kind of the user's word on a normalized work last changed (work.WorkChanges, worklist.WorkDatum): its
-- answers on values and fields, its grade and mark of interest, the items it is of (merged, taken out) — what the
-- stages of the tasks over the works take it by, each by the kinds it reads. changed_at stays when any did. Null — not
-- changed since the stages kept their marks
ALTER TABLE normalized_work ADD COLUMN (
    answers_at TIMESTAMP WITH TIME ZONE,
    grades_at TIMESTAMP WITH TIME ZONE,
    members_at TIMESTAMP WITH TIME ZONE
);
