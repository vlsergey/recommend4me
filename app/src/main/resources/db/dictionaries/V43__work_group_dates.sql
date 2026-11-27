-- When which normalized authors and series a work is of, and which works those hold, last changed by the user's word
-- (work.WorkChanges, worklist.WorkDatum.GROUPS): authors merged or taken apart, works put in a series or out of it, a
-- work of theirs removed — what the manners of the authors and of the series are made anew by (manner.Manners). Null —
-- not changed since
ALTER TABLE normalized_work ADD COLUMN groups_at TIMESTAMP WITH TIME ZONE;
