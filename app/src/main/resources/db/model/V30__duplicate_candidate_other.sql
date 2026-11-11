-- The candidates by their second work, as the key finds them by the first: the pairs of the works of an author,
-- either of a pair theirs, found by both (the window of a pair, how close two authors are)
CREATE INDEX duplicate_candidate_other ON duplicate_candidate (other_source, other_item_id);
