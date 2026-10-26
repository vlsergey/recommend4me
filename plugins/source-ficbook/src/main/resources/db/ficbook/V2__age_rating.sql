-- The rating the site states is a value of the age rating's scale (AgeRating): its keys are the
-- site's in lower case. Kept as the ratings the site lets the work have — that one alone — and as
-- the work's own rating; the site's facet of its own goes
INSERT INTO item_facet (item_id, facet, value_key, position)
SELECT item_id, 'age-rating-site', LOWER(value_key), 0 FROM item_facet
WHERE facet = 'rating' AND LOWER(value_key) IN ('g', 'pg-13', 'r', 'nc-17', 'nc-21');

INSERT INTO item_facet (item_id, facet, value_key, position)
SELECT item_id, 'age-rating', LOWER(value_key), 0 FROM item_facet
WHERE facet = 'rating' AND LOWER(value_key) IN ('g', 'pg-13', 'r', 'nc-17', 'nc-21');

DELETE FROM item_facet WHERE facet = 'rating';

DELETE FROM facet_value WHERE facet = 'rating';
