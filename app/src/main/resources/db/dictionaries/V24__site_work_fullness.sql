-- How much the item says of its normalized work: the characters of its texts, then how many values its facets
-- have, measured as the item changes. Of a work of several items the one that says the most is the one it is shown
-- with, its other items after it in this order: read, never worked out at a request. Null until measured
ALTER TABLE site_work ADD COLUMN fullness BIGINT;
