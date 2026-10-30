-- The pairs compared by every field of a work every site has alike, the text aside, and one pair
-- kept of every work and every other site: the pairs of before are of the titles and descriptions
-- alone, looked for anew. [facets]: the cosine of the values of the facets the sites share, weighed
-- by rarity; [size]: the mean of the smaller over the larger of the numbers of their size; none of a
-- layer either has not. How likely a pair is one work is made when read, by the model of the user's
-- answers (duplicate_search.weights): no similarity kept
DELETE FROM duplicate_candidate;
DELETE FROM duplicate_search;
DROP INDEX duplicate_candidate_similarity;
ALTER TABLE duplicate_candidate DROP COLUMN similarity;
ALTER TABLE duplicate_candidate ADD COLUMN facets REAL;
ALTER TABLE duplicate_candidate ADD COLUMN size REAL;

-- The model of the user's answers the chances are made by: its weights, one number a line; none
-- before the user said any pair is one work
ALTER TABLE duplicate_search ADD COLUMN weights CLOB;
