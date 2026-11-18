-- The titles and the authors of a pair compared letter by letter (user, 2026-11-18): how much of the first's title the
-- second's is and of the second's the first's; how alike the names of their authors are and their two shares — none of
-- a pair of no names compared. Of the candidates of an earlier search none: the next search writes them
ALTER TABLE duplicate_candidate ADD COLUMN title_within REAL;
ALTER TABLE duplicate_candidate ADD COLUMN title_holding REAL;
ALTER TABLE duplicate_candidate ADD COLUMN authors_alike REAL;
ALTER TABLE duplicate_candidate ADD COLUMN authors_within REAL;
ALTER TABLE duplicate_candidate ADD COLUMN authors_holding REAL;
