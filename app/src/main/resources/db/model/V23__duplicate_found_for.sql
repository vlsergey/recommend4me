-- Of a pair, the works it was found for — the work whose likeliest of the other site it is: 1 the
-- first, 2 the second, 3 both, 0 neither (an answered pair the search did not keep). The pairs are
-- shown by the score by the formula of the work they were found for: one work's many pairs, every
-- work of another site whose likeliest it is, are not all lifted by it. The pairs of before looked for anew
DELETE FROM duplicate_candidate;
DELETE FROM duplicate_search;
ALTER TABLE duplicate_candidate ADD COLUMN found_for TINYINT DEFAULT 0 NOT NULL;
