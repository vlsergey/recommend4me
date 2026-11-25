-- The random rotation of the full-text vectors of a type (fulltext.FullTextCentres), its rows one after another: a
-- centre of none, learnt before them, is learnt anew
ALTER TABLE full_text_centre ADD COLUMN rotation BLOB;
