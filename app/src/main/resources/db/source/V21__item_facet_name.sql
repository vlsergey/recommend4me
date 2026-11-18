-- The name a work's value had as the site's page wrote it, beside the value's key (user, 2026-11-18: "ideally a work
-- keeps both — the tags and the names: who knows what the site's authors change in their next version"); the key's
-- name of every work, the site's last word on it, stays in facet_value. Null: not written yet (V22 writes the names
-- facet_value has of the works written before)
ALTER TABLE item_facet ADD COLUMN name CHARACTER VARYING(1000);
