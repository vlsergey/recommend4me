-- The site's genres are its tags as its tags are (user, 2026-10-27): a genre a tag of the site by its
-- name, its key the name in lower case, after the work's own tags; the facet of the genres goes
INSERT INTO facet_value (facet, value_key, name)
SELECT 'tag', LOWER(v.name), MIN(v.name) FROM facet_value v
WHERE v.facet = 'genre' AND v.name IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM facet_value t WHERE t.facet = 'tag' AND t.value_key = LOWER(v.name))
GROUP BY LOWER(v.name);

INSERT INTO item_facet (item_id, facet, value_key, position)
SELECT f.item_id, 'tag', LOWER(v.name), 1000 + MIN(f.position) FROM item_facet f
JOIN facet_value v ON v.facet = 'genre' AND v.value_key = f.value_key AND v.name IS NOT NULL
WHERE f.facet = 'genre'
  AND NOT EXISTS (SELECT 1 FROM item_facet t WHERE t.item_id = f.item_id AND t.facet = 'tag' AND t.value_key = LOWER(v.name))
GROUP BY f.item_id, LOWER(v.name);

DELETE FROM item_facet WHERE facet = 'genre';

DELETE FROM facet_value WHERE facet = 'genre';
