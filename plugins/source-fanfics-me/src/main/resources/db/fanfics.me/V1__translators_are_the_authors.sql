-- The author of a translation is its translator, who put it on the site; the writer of the original
-- a facet of its own. The two swapped in what is kept, no page read again.
INSERT INTO facet_value (facet, value_key, name)
SELECT 'originalAuthor', v.value_key, v.name
FROM facet_value v
WHERE v.facet = 'author' AND EXISTS (
    SELECT 1
    FROM item_facet f
    JOIN item_facet t ON t.item_id = f.item_id AND t.facet = 'translator'
    WHERE f.facet = 'author' AND f.value_key = v.value_key
);

UPDATE item_facet SET facet = 'originalAuthor'
WHERE facet = 'author' AND item_id IN (SELECT item_id FROM item_facet WHERE facet = 'translator');

MERGE INTO facet_value (facet, value_key, name) KEY (facet, value_key)
SELECT 'author', value_key, name FROM facet_value WHERE facet = 'translator';

UPDATE item_facet SET facet = 'author' WHERE facet = 'translator';

DELETE FROM facet_value WHERE facet = 'translator';

-- The writers of the originals known by their names alone are no authors of the site's any more
DELETE FROM facet_value
WHERE facet = 'author' AND NOT EXISTS (
    SELECT 1 FROM item_facet f WHERE f.facet = 'author' AND f.value_key = facet_value.value_key
);
