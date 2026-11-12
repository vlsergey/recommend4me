-- Whether the site gives the item a value its work is shown by first (a finished book's status: StandardValue.shownFirst):
-- of a work of several items, those so shown first, then the one that says the most. Null until measured
ALTER TABLE site_work ADD COLUMN shown_first BOOLEAN;
