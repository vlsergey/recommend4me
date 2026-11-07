-- When the models of the pairs were last fitted — at every search, and a few seconds after the answers — and how long
-- it took: what the page of the models tells of them. None of models fitted before it was kept.
ALTER TABLE duplicate_search ADD COLUMN fitted_at TIMESTAMP WITH TIME ZONE;
ALTER TABLE duplicate_search ADD COLUMN fit_millis BIGINT;
