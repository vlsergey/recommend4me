-- The archive's rating on the age rating's scale (AgeRating): General Audiences G, Teen And Up
-- Audiences PG-13, Mature R, Explicit NC-17; Not Rated tells nothing. Kept as the rating the site
-- lets the work have — that one alone — and as the work's own; the site's facet of its own goes
INSERT INTO item_facet (item_id, facet, value_key, position)
SELECT item_id, f.facet, CASE value_key WHEN 'general-audience' THEN 'g' WHEN 'teen' THEN 'pg-13' WHEN 'mature' THEN 'r' ELSE 'nc-17' END, 0
FROM item_facet, (VALUES ('age-rating-site'), ('age-rating')) AS f(facet)
WHERE item_facet.facet = 'rating' AND value_key IN ('general-audience', 'teen', 'mature', 'explicit');

DELETE FROM item_facet WHERE facet = 'rating';

DELETE FROM facet_value WHERE facet = 'rating';
