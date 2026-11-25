-- How many windows a type had as its centre of the full-text vectors was learnt (fulltext.FullTextCentres): learnt anew
-- as a search reads twice as many; of a centre learnt before it, the windows it was learnt of
ALTER TABLE full_text_centre ADD COLUMN corpus BIGINT;
