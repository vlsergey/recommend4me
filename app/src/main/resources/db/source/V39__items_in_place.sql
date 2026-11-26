-- The items keyed by the application's own number (V37, V38) in the place of item: the texts are in their rows now, and
-- the tables of an item's data bound to it by the site's id as before - every foreign key of item dropped with it, made
-- again on its unique site_id.
DROP TABLE item_text;
DROP TABLE item CASCADE;
ALTER TABLE item_new RENAME TO item;
ALTER TABLE item ADD CONSTRAINT item_site_id UNIQUE(site_id);
CREATE INDEX item_updated_at ON item(updated_at DESC);
CREATE INDEX item_changed_at ON item(changed_at, site_id);
ALTER TABLE item_version ADD CONSTRAINT item_version_item FOREIGN KEY(item_id) REFERENCES item(site_id) ON DELETE CASCADE NOCHECK;
ALTER TABLE item_picture_vector ADD CONSTRAINT item_picture_vector_item FOREIGN KEY(item_id) REFERENCES item(site_id) ON DELETE CASCADE NOCHECK;
ALTER TABLE set_vector ADD CONSTRAINT set_vector_item FOREIGN KEY(item_id) REFERENCES item(site_id) ON DELETE CASCADE NOCHECK;
ALTER TABLE review ADD CONSTRAINT review_item FOREIGN KEY(item_id) REFERENCES item(site_id) ON DELETE CASCADE NOCHECK;
ALTER TABLE picture ADD CONSTRAINT picture_item FOREIGN KEY(item_id) REFERENCES item(site_id) ON DELETE CASCADE NOCHECK;
ALTER TABLE item_facet ADD CONSTRAINT item_facet_item FOREIGN KEY(item_id) REFERENCES item(site_id) ON DELETE CASCADE NOCHECK;
ALTER TABLE item_number ADD CONSTRAINT item_number_item FOREIGN KEY(item_id) REFERENCES item(site_id) ON DELETE CASCADE NOCHECK;
ALTER TABLE part ADD CONSTRAINT part_item FOREIGN KEY(item_id) REFERENCES item(site_id) ON DELETE CASCADE NOCHECK;
ALTER TABLE item_manner ADD CONSTRAINT item_manner_item FOREIGN KEY(item_id) REFERENCES item(site_id) ON DELETE CASCADE NOCHECK;
ALTER TABLE text_window ADD CONSTRAINT text_window_item FOREIGN KEY(item_id) REFERENCES item(site_id) ON DELETE CASCADE NOCHECK;
ALTER TABLE series_place ADD CONSTRAINT series_place_item FOREIGN KEY(item_id) REFERENCES item(site_id) ON DELETE CASCADE NOCHECK;
ALTER TABLE text_vector ADD CONSTRAINT text_vector_item FOREIGN KEY(item_id) REFERENCES item(site_id) ON DELETE CASCADE NOCHECK;
