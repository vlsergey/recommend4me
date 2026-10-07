-- THE TEXTS OF THE DICTIONARY BY LANGUAGE: every label, alias and description the catalogue gave
-- in each language it was asked in, a line each, "<language><TAB><text>". What is shown is chosen
-- among them by the user's languages when read; a universe asked in other languages than the
-- user's now is asked again. The columns of one text (name, names, description) stay as what was
-- shown when the universe was saved: what a universe not asked again since is read by.

ALTER TABLE universe ADD COLUMN labels CLOB;
ALTER TABLE universe ADD COLUMN descriptions CLOB;
-- The languages the universe was asked in, in the user's order, comma-separated
ALTER TABLE universe ADD COLUMN languages VARCHAR(200);

ALTER TABLE universe_character ADD COLUMN labels CLOB;
ALTER TABLE universe_character ADD COLUMN aliases CLOB;
ALTER TABLE universe_character ADD COLUMN descriptions CLOB;

ALTER TABLE universe_class ADD COLUMN labels CLOB;
